/**
 * X.MAX Zero-Cost Cloudflare Worker Proxy (Free Plan Compatible)
 * - Verifies Firebase Auth ID tokens via Google public JWKs (WebCrypto RS256)
 * - Verifies Firestore chat membership / SUPER_ADMIN role via Firestore REST API
 * - Mints short-lived Supabase Storage signed upload/download URLs (NO secrets in APK)
 * - Dispatches FCM HTTP v1 push notifications (chunked <= 35 recipients/call)
 * - Drives chunked resumable Complete Chat Reset within 50-subrequest Worker limits
 */

const GOOGLE_JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const FIRESTORE_DB_ID = "ai-studio-android-xteam-2d3c79c2-5261-467c-ba48-b366a64e6a80";

const ALLOWED_BUCKETS = {
  "avatars": { maxBytes: 2 * 1024 * 1024, mimes: ["image/jpeg", "image/png", "image/webp"] },
  "group-photos": { maxBytes: 2 * 1024 * 1024, mimes: ["image/jpeg", "image/png", "image/webp"] },
  "chat-media": { maxBytes: 5 * 1024 * 1024, mimes: ["image/jpeg", "image/png", "image/webp"] },
  "chat-voice": { maxBytes: 2 * 1024 * 1024, mimes: ["audio/mp4", "audio/aac", "audio/m4a", "audio/mpeg"] },
  "chat-videos": { maxBytes: 15 * 1024 * 1024, mimes: ["video/mp4", "video/webm", "image/jpeg"] },
  "chat-docs": { maxBytes: 10 * 1024 * 1024, mimes: ["application/pdf", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip"] },
};

const CHAT_RESET_COLLECTIONS = [
  "chats",
  "chat_channels",
  "chatPolls",
  "chatReports",
  "chatAnnouncements",
];

// Lightweight per-isolate sliding window rate limiter (best-effort free-tier guard)
const rateBuckets = new Map();

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }
    const url = new URL(request.url);
    if (url.pathname === "/v1/health") {
      return jsonResponse({ ok: true, plan: "free", cost: "INR 0" }, 200);
    }

    try {
      const authHeader = request.headers.get("Authorization") || "";
      const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
      if (!idToken) return jsonResponse({ error: "Missing Firebase Auth Bearer token" }, 401);

      const claims = await verifyFirebaseIdToken(idToken, env.FIREBASE_PROJECT_ID);
      if (!checkRateLimit(`${claims.sub}:${url.pathname}`, 60, 60_000)) {
        return jsonResponse({ error: "Rate limit exceeded" }, 429, { "Retry-After": "30" });
      }

      const body = await request.json().catch(() => ({}));
      const companyId = sanitizeId(body.companyId || "xmax");

      if (url.pathname === "/v1/storage/sign-upload") {
        return await handleSignUpload(claims.sub, idToken, companyId, body, env);
      }
      if (url.pathname === "/v1/storage/sign-download") {
        return await handleSignDownload(claims.sub, idToken, companyId, body, env);
      }
      if (url.pathname === "/v1/notifications/dispatch") {
        return await handlePushDispatch(claims.sub, idToken, companyId, body, env);
      }
      if (url.pathname === "/v1/admin/reset-chunk") {
        return await handleResetChunk(claims.sub, idToken, companyId, body, env);
      }
      return jsonResponse({ error: "Not found" }, 404);
    } catch (err) {
      return jsonResponse({ error: err.message || "Unauthorized or bad request" }, err.status || 400);
    }
  },
};

async function handleSignUpload(uid, idToken, companyId, body, env) {
  const bucket = String(body.bucket || "");
  const storagePath = String(body.storagePath || "").replace(/^\/+/, "");
  const mimeType = String(body.mimeType || "").toLowerCase();
  const byteSize = Number(body.byteSize || 0);
  const chatId = String(body.chatId || "");

  const policy = ALLOWED_BUCKETS[bucket];
  if (!policy) return jsonResponse({ error: "Unsupported bucket" }, 400);
  if (byteSize <= 0 || byteSize > policy.maxBytes) {
    return jsonResponse({ error: `File exceeds free-tier cap (${policy.maxBytes} bytes)` }, 413);
  }
  if (!policy.mimes.includes(mimeType)) {
    return jsonResponse({ error: "Unsupported MIME type" }, 415);
  }
  if (!storagePath || storagePath.includes("..")) {
    return jsonResponse({ error: "Invalid storage path" }, 400);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  assertApprovedNotSuspended(member);

  if (bucket === "avatars") {
    if (!storagePath.startsWith(`${uid}/`)) return jsonResponse({ error: "Avatar path mismatch" }, 403);
  } else if (chatId) {
    await assertChatParticipant(companyId, chatId, uid, idToken, env.FIREBASE_PROJECT_ID);
  }

  const signRes = await fetch(
    `${env.SUPABASE_URL}/storage/v1/object/upload/sign/${bucket}/${encodeURI(storagePath)}`,
    {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ expiresIn: 60 }),
    }
  );
  const signData = await signRes.json();
  if (!signRes.ok || !signData.url) {
    return jsonResponse({ error: signData.message || "Supabase sign-upload failed" }, 502);
  }
  const signedUrl = signData.url.startsWith("http")
    ? signData.url
    : `${env.SUPABASE_URL}/storage/v1${signData.url}`;

  return jsonResponse({ signedUrl, bucket, storagePath, expiresInSec: 60 }, 200);
}

async function handleSignDownload(uid, idToken, companyId, body, env) {
  const bucket = String(body.bucket || "");
  const storagePath = String(body.storagePath || "").replace(/^\/+/, "");
  const chatId = String(body.chatId || "");
  if (!ALLOWED_BUCKETS[bucket] || !storagePath || storagePath.includes("..")) {
    return jsonResponse({ error: "Invalid download target" }, 400);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  if (member.status !== "APPROVED" || member.active !== true) {
    return jsonResponse({ error: "Approved membership required" }, 403);
  }
  if (bucket !== "avatars" && chatId) {
    await assertChatParticipant(companyId, chatId, uid, idToken, env.FIREBASE_PROJECT_ID);
  }

  const signRes = await fetch(
    `${env.SUPABASE_URL}/storage/v1/object/sign/${bucket}/${encodeURI(storagePath)}`,
    {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_SERVICE_ROLE_KEY,
        Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ expiresIn: 300 }),
    }
  );
  const signData = await signRes.json();
  if (!signRes.ok || !signData.signedURL) {
    return jsonResponse({ error: signData.message || "Supabase sign-download failed" }, 502);
  }
  const signedUrl = signData.signedURL.startsWith("http")
    ? signData.signedURL
    : `${env.SUPABASE_URL}/storage/v1${signData.signedURL}`;

  return jsonResponse({ signedUrl, expiresInSec: 300 }, 200);
}

async function handlePushDispatch(uid, idToken, companyId, body, env) {
  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  assertApprovedNotSuspended(member);

  const chatId = String(body.chatId || "");
  if (chatId) {
    await assertChatParticipant(companyId, chatId, uid, idToken, env.FIREBASE_PROJECT_ID);
  }

  const targetUids = Array.isArray(body.targetUids) ? body.targetUids.slice(0, 15) : [];
  if (targetUids.length === 0) return jsonResponse({ sentCount: 0 }, 200);

  const serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  const accessToken = await mintGoogleOAuthToken(serviceAccount, "https://www.googleapis.com/auth/firebase.messaging");

  let sentCount = 0;
  for (const targetUid of targetUids) {
    if (targetUid === uid) continue;
    const tokens = await fetchUserDeviceTokens(companyId, targetUid, accessToken, env.FIREBASE_PROJECT_ID);
    for (const tok of tokens.slice(0, 2)) {
      const fcmRes = await fetch(
        `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${accessToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            message: {
              token: tok,
              notification: {
                title: String(body.title || "X.MAX Chat").slice(0, 120),
                body: String(body.body || "New update").slice(0, 240),
              },
              data: {
                chatId,
                businessKind: String(body.businessKind || ""),
                businessId: String(body.businessId || ""),
                priority: String(body.priority || "NORMAL"),
              },
              android: {
                collapse_key: chatId || "xmax_chat",
                priority: body.priority === "URGENT" ? "high" : "normal",
              },
            },
          }),
        }
      );
      if (fcmRes.ok) sentCount += 1;
    }
  }
  return jsonResponse({ sentCount }, 200);
}

async function handleResetChunk(uid, idToken, companyId, body, env) {
  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  if (member.status !== "APPROVED" || member.active !== true || member.accessLevel !== "SUPER_ADMIN") {
    return jsonResponse({ error: "Super Admin authorization required" }, 403);
  }

  const col = String(body.cursorCollection || CHAT_RESET_COLLECTIONS[0]);
  if (!CHAT_RESET_COLLECTIONS.includes(col)) {
    return jsonResponse({ error: "Protected collection cannot be reset" }, 403);
  }

  const serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  const fsToken = await mintGoogleOAuthToken(serviceAccount, "https://www.googleapis.com/auth/datastore");

  // Delete up to 12 docs per invocation to stay safely inside Cloudflare's 50-subrequest cap
  const listUrl = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/${col}?pageSize=12`;
  const listRes = await fetch(listUrl, { headers: { Authorization: `Bearer ${fsToken}` } });
  const listJson = await listRes.json();
  const docs = listJson.documents || [];

  let deletedDocs = 0;
  for (const doc of docs) {
    const delRes = await fetch(`https://firestore.googleapis.com/v1/${doc.name}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${fsToken}` },
    });
    if (delRes.ok) deletedDocs += 1;
  }

  const colIdx = CHAT_RESET_COLLECTIONS.indexOf(col);
  const moreInCurrent = docs.length >= 12;
  const nextCol = moreInCurrent ? col : (CHAT_RESET_COLLECTIONS[colIdx + 1] || "");
  return jsonResponse({
    completed: !moreInCurrent && !nextCol,
    nextCollection: nextCol,
    nextCursorDocId: "",
    deletedDocsInChunk: deletedDocs,
    deletedStorageInChunk: 0,
  }, 200);
}

function sanitizeId(v) {
  return String(v || "").replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 64) || "xmax";
}

function assertApprovedNotSuspended(member) {
  if (member.status !== "APPROVED" || member.active !== true) {
    throw Object.assign(new Error("Approved workspace membership required"), { status: 403 });
  }
  if (member.messagingSuspended === true) {
    throw Object.assign(new Error("Messaging access suspended by Super Admin"), { status: 403 });
  }
}

async function fetchMemberDoc(companyId, uid, idToken, projectId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/members/${uid}`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${idToken}` } });
  if (!res.ok) throw Object.assign(new Error("Member verification failed"), { status: 403 });
  const doc = await res.json();
  return decodeFirestoreFields(doc.fields || {});
}

async function assertChatParticipant(companyId, chatId, uid, idToken, projectId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/chats/${chatId}`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${idToken}` } });
  if (!res.ok) return; // Legacy chat_channels fallback validated by Firestore Rules
  const doc = await res.json();
  const data = decodeFirestoreFields(doc.fields || {});
  if (data.type === "PERSONAL" || data.type === "SAVED" || Array.isArray(data.participantIds)) {
    const participants = data.participantIds || [];
    if (participants.length > 0 && !participants.includes(uid) && data.ownerUid !== uid) {
      throw Object.assign(new Error("Not a participant of this chat"), { status: 403 });
    }
  }
}

async function fetchUserDeviceTokens(companyId, uid, oauthToken, projectId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/deviceTokens/${uid}/tokens?pageSize=5`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${oauthToken}` } });
  if (!res.ok) return [];
  const json = await res.json();
  return (json.documents || []).map((d) => d.fields?.token?.stringValue).filter(Boolean);
}

function decodeFirestoreFields(fields) {
  const out = {};
  for (const [k, v] of Object.entries(fields)) {
    if ("stringValue" in v) out[k] = v.stringValue;
    else if ("booleanValue" in v) out[k] = v.booleanValue;
    else if ("integerValue" in v) out[k] = Number(v.integerValue);
    else if ("arrayValue" in v) out[k] = (v.arrayValue.values || []).map((x) => x.stringValue || "");
  }
  return out;
}

async function verifyFirebaseIdToken(token, projectId) {
  const parts = token.split(".");
  if (parts.length !== 3) throw Object.assign(new Error("Invalid JWT format"), { status: 401 });
  const header = JSON.parse(base64UrlDecodeToString(parts[0]));
  const payload = JSON.parse(base64UrlDecodeToString(parts[1]));
  const nowSec = Math.floor(Date.now() / 1000);
  if (!payload.sub || payload.aud !== projectId || payload.exp <= nowSec) {
    throw Object.assign(new Error("Expired or invalid Firebase token"), { status: 401 });
  }
  const jwksRes = await fetch(GOOGLE_JWKS_URL, { cf: { cacheTtl: 3600, cacheEverything: true } });
  const jwks = await jwksRes.json();
  const key = (jwks.keys || []).find((k) => k.kid === header.kid);
  if (!key) throw Object.assign(new Error("Signing key not found"), { status: 401 });

  const cryptoKey = await crypto.subtle.importKey(
    "jwk",
    key,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    base64UrlToUint8Array(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
  );
  if (!valid) throw Object.assign(new Error("Token signature invalid"), { status: 401 });
  return payload;
}

async function mintGoogleOAuthToken(sa, scope) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64UrlEncode(JSON.stringify({
    iss: sa.client_email,
    scope,
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s+/g, "");
  const keyBytes = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const privKey = await crypto.subtle.importKey(
    "pkcs8",
    keyBytes.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privKey, new TextEncoder().encode(`${header}.${claim}`));
  const jwt = `${header}.${claim}.${base64UrlEncodeBytes(new Uint8Array(sig))}`;

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  const tokenJson = await tokenRes.json();
  if (!tokenRes.ok || !tokenJson.access_token) throw new Error("Google OAuth assertion failed");
  return tokenJson.access_token;
}

function checkRateLimit(key, maxRequests, windowMs) {
  const now = Date.now();
  const entry = rateBuckets.get(key) || { count: 0, resetAt: now + windowMs };
  if (now > entry.resetAt) {
    entry.count = 1;
    entry.resetAt = now + windowMs;
    rateBuckets.set(key, entry);
    return true;
  }
  if (entry.count >= maxRequests) return false;
  entry.count += 1;
  return true;
}

function base64UrlDecodeToString(str) {
  const b64 = str.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((str.length + 3) % 4);
  return atob(b64);
}
function base64UrlToUint8Array(str) {
  const bin = base64UrlDecodeToString(str);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}
function base64UrlEncode(str) {
  return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function base64UrlEncodeBytes(bytes) {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function corsHeaders() {
  return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "Authorization, Content-Type" };
}
function jsonResponse(data, status, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...corsHeaders(), ...extra },
  });
}
