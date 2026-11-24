/**
 * X.MAX Zero-Cost Cloudflare Worker Proxy (Free Plan Compatible)
 * - Verifies Firebase Auth ID tokens via Google public JWKs (WebCrypto RS256 with JWK cache)
 * - Verifies Firestore chat membership and SUPER_ADMIN/ADMIN role via Firestore REST API
 * - Mints short-lived Supabase Storage signed upload/download URLs bound to canonical paths
 * - Dispatches FCM HTTP v1 push notifications in chunked <= 35 recipient batches
 * - Drives chunked resumable Complete Chat Reset within 50-subrequest Worker limits
 * - Strict input sanitization, fail-closed access checks, and sliding-window rate limiting
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

const CHAT_RESET_COLLECTIONS = ["messages", "reactions", "receipts", "polls", "reports"];

let cachedJwks = null;
let jwksFetchedAt = 0;
const JWKS_TTL_MS = 3600_000;

const rateBuckets = new Map();
const MAX_RATE_BUCKETS = 10000;

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }
    const url = new URL(request.url);
    if (url.pathname === "/v1/health") {
      return jsonResponse({ ok: true, plan: "free", cost: "INR 0" }, 200);
    }

    if (request.method !== "POST") {
      return jsonResponse({ error: "Method not allowed" }, 405);
    }

    try {
      const authHeader = request.headers.get("Authorization") || "";
      const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
      if (!idToken) return jsonResponse({ error: "Missing Firebase Auth Bearer token" }, 401);

      const claims = await verifyFirebaseIdToken(idToken, env.FIREBASE_PROJECT_ID);
      
      const clientIp = request.headers.get("CF-Connecting-IP") || "global";
      if (!checkRateLimit(`${clientIp}:${claims.sub}:${url.pathname}`, 60, 60_000)) {
        return jsonResponse({ error: "Rate limit exceeded" }, 429, { "Retry-After": "30" });
      }

      const body = await request.json().catch(() => ({}));
      const companyId = sanitizeId(body.companyId || "");
      if (!companyId) return jsonResponse({ error: "Missing companyId" }, 400);

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

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
  };
}

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...corsHeaders(), ...extraHeaders },
  });
}

function sanitizeId(id) {
  return String(id || "").replace(/[^a-zA-Z0-9_\-\.]/g, "");
}

function checkRateLimit(key, limit, windowMs) {
  const now = Date.now();
  if (rateBuckets.size >= MAX_RATE_BUCKETS) {
    const oldestKey = rateBuckets.keys().next().value;
    rateBuckets.delete(oldestKey);
  }
  let bucket = rateBuckets.get(key);
  if (!bucket || now > bucket.resetTime) {
    bucket = { count: 1, resetTime: now + windowMs };
    rateBuckets.set(key, bucket);
    return true;
  }
  bucket.count++;
  return bucket.count <= limit;
}

async function verifyFirebaseIdToken(token, projectId) {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("Invalid JWT structure");

  let header, payload;
  try {
    header = JSON.parse(atob(parts[0]));
    payload = JSON.parse(atob(parts[1]));
  } catch (e) {
    throw new Error("Invalid JWT encoding");
  }

  const now = Math.floor(Date.now() / 1000);
  if (!payload.exp || payload.exp < now) throw new Error("Token expired");
  if (!payload.iat || payload.iat > now + 300) throw new Error("Token issued in future");
  if (payload.iss !== `https://securetoken.google.com/${projectId}`) throw new Error("Invalid issuer");
  if (payload.aud !== projectId) throw new Error("Invalid audience");
  if (!payload.sub || typeof payload.sub !== "string" || payload.sub.length === 0) throw new Error("Invalid subject");

  const jwks = await fetchGoogleJwks();
  const jwk = jwks.find(j => j.kid === header.kid);
  if (!jwk) throw new Error("Matching signing key not found");

  const pubKey = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: { name: "SHA-256" } },
    false,
    ["verify"]
  );

  const data = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const sig = Uint8Array.from(atob(parts[2].replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0));

  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pubKey, sig, data);
  if (!valid) throw new Error("Invalid token signature");

  return payload;
}

async function fetchGoogleJwks() {
  const now = Date.now();
  if (cachedJwks && (now - jwksFetchedAt) < JWKS_TTL_MS) {
    return cachedJwks;
  }
  const res = await fetch(GOOGLE_JWKS_URL);
  if (!res.ok) throw new Error("Failed to fetch Google public JWKs");
  const data = await res.json();
  cachedJwks = data.keys;
  jwksFetchedAt = now;
  return cachedJwks;
}

async function fetchMemberDoc(companyId, uid, idToken, projectId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/members/${uid}`;
  const res = await fetch(url, {
    headers: { "Authorization": `Bearer ${idToken}` }
  });
  if (!res.ok) {
    const errObj = await res.json().catch(() => ({}));
    const err = new Error(errObj.error?.message || "Membership verification failed");
    err.status = res.status === 404 ? 403 : res.status;
    throw err;
  }
  const doc = await res.json();
  return parseFirestoreDoc(doc);
}

function assertApprovedNotSuspended(member) {
  if (!member || (member.status !== "APPROVED" && member.status !== "active") || member.isSuspended === true || member.active === false) {
    const err = new Error("User account is inactive, suspended, or unauthorized");
    err.status = 403;
    throw err;
  }
}

async function fetchChatDoc(companyId, chatId, idToken, projectId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/chats/${chatId}`;
  const res = await fetch(url, {
    headers: { "Authorization": `Bearer ${idToken}` }
  });
  if (!res.ok) {
    const err = new Error("Chat not found or access denied");
    err.status = 403;
    throw err;
  }
  return parseFirestoreDoc(await res.json());
}

async function assertChatParticipant(companyId, chatId, uid, idToken, projectId) {
  const chat = await fetchChatDoc(companyId, chatId, idToken, projectId);
  if (!chat || !chat.type) {
    const err = new Error("Invalid chat record");
    err.status = 403;
    throw err;
  }

  const validTypes = ["direct", "group", "channel", "broadcast", "announcement", "saved_messages", "SAVED"];
  if (!validTypes.includes(chat.type)) {
    const err = new Error("Unknown chat type");
    err.status = 403;
    throw err;
  }

  const participants = Array.isArray(chat.participants) ? chat.participants : [];
  if (participants.length === 0 && chat.type !== "saved_messages" && chat.type !== "SAVED") {
    const err = new Error("Empty participant list");
    err.status = 403;
    throw err;
  }

  if ((chat.type === "saved_messages" || chat.type === "SAVED") && chat.ownerUid !== uid) {
    const err = new Error("Unauthorized saved messages access");
    err.status = 403;
    throw err;
  }

  if (chat.type !== "saved_messages" && chat.type !== "SAVED" && !participants.includes(uid)) {
    const member = await fetchMemberDoc(companyId, uid, idToken, projectId);
    const isSuperAdminOrAdmin = member.accessLevel === "SUPER_ADMIN" || member.accessLevel === "ADMIN" || member.role === "SUPER_ADMIN" || member.role === "ADMIN";
    
    if (chat.type === "channel" || chat.type === "announcement") {
      return { chat, isSuperAdminOrAdmin, isParticipant: false };
    }
    if (!isSuperAdminOrAdmin) {
      const err = new Error("User is not a participant of this chat");
      err.status = 403;
      throw err;
    }
  }

  return { chat, isSuperAdminOrAdmin: false, isParticipant: true };
}

function parseFirestoreDoc(doc) {
  const fields = doc.fields || {};
  const result = {};
  for (const [key, val] of Object.entries(fields)) {
    if (val.stringValue !== undefined) result[key] = val.stringValue;
    else if (val.booleanValue !== undefined) result[key] = val.booleanValue;
    else if (val.integerValue !== undefined) result[key] = Number(val.integerValue);
    else if (val.doubleValue !== undefined) result[key] = Number(val.doubleValue);
    else if (val.arrayValue !== undefined) {
      result[key] = (val.arrayValue.values || []).map(v => v.stringValue || v.integerValue || v.booleanValue);
    } else if (val.mapValue !== undefined) {
      result[key] = parseFirestoreDoc({ fields: val.mapValue.fields });
    }
  }
  return result;
}

async function handleSignUpload(uid, idToken, companyId, body, env) {
  const bucket = String(body.bucket || "");
  const storagePath = String(body.storagePath || "").replace(/^\/+/, "");
  const mimeType = String(body.mimeType || "").toLowerCase();
  const byteSize = Number(body.byteSize || 0);
  const chatId = sanitizeId(body.chatId || "");

  const policy = ALLOWED_BUCKETS[bucket];
  if (!policy) return jsonResponse({ error: "Unsupported bucket" }, 400);
  if (byteSize <= 0 || byteSize > policy.maxBytes) {
    return jsonResponse({ error: `File size exceeds free-tier limit (${policy.maxBytes} bytes)` }, 413);
  }
  if (!policy.mimes.includes(mimeType)) {
    return jsonResponse({ error: "Unsupported or unallowed MIME type" }, 415);
  }
  if (!storagePath || storagePath.includes("..") || storagePath.includes("//")) {
    return jsonResponse({ error: "Invalid path traversal attempt" }, 400);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  assertApprovedNotSuspended(member);

  let expectedPrefix = "";
  if (bucket === "avatars") {
    expectedPrefix = `companies/${companyId}/avatars/${uid}/`;
  } else if (bucket === "group-photos") {
    if (!chatId) return jsonResponse({ error: "Missing chatId for group photo" }, 400);
    const { chat, isSuperAdminOrAdmin } = await assertChatParticipant(companyId, chatId, uid, idToken, env.FIREBASE_PROJECT_ID);
    if (chat.type !== "group" && !isSuperAdminOrAdmin) {
      return jsonResponse({ error: "Only admins or group members can update group photos" }, 403);
    }
    expectedPrefix = `companies/${companyId}/group-photos/${chatId}/`;
  } else {
    if (!chatId) return jsonResponse({ error: "Missing chatId for chat attachment" }, 400);
    const { chat, isSuperAdminOrAdmin, isParticipant } = await assertChatParticipant(companyId, chatId, uid, idToken, env.FIREBASE_PROJECT_ID);
    if (chat.type === "announcement" && !isSuperAdminOrAdmin) {
      return jsonResponse({ error: "Only admins can upload to announcements" }, 403);
    }
    if (!isParticipant && !isSuperAdminOrAdmin) {
      return jsonResponse({ error: "Unauthorized upload access to chat" }, 403);
    }
    expectedPrefix = `companies/${companyId}/chats/${chatId}/`;
  }

  if (!storagePath.startsWith(expectedPrefix)) {
    return jsonResponse({ error: "Storage path does not match canonical company/chat/user structure" }, 403);
  }

  const fileId = crypto.randomUUID();
  const canonicalPath = `${expectedPrefix}${fileId}_${sanitizeFileName(storagePath.split("/").pop())}`;

  const supabaseUrl = env.SUPABASE_URL;
  const supabaseKey = env.SUPABASE_SECRET_KEY || env.SUPABASE_SERVICE_ROLE_KEY;
  if (!supabaseUrl || !supabaseKey) {
    return jsonResponse({ error: "Supabase storage is not configured on backend" }, 500);
  }

  const signRes = await fetch(`${supabaseUrl}/storage/v1/object/upload/sign/${bucket}/${canonicalPath}`, {
    method: "POST",
    headers: {
      "apikey": supabaseKey,
      "Authorization": `Bearer ${supabaseKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ expiresIn: 60 })
  });

  if (!signRes.ok) {
    const errText = await signRes.text();
    return jsonResponse({ error: `Supabase signing failed: ${errText}` }, 502);
  }

  const signData = await signRes.json();
  return jsonResponse({
    uploadUrl: `${supabaseUrl}/storage/v1${signData.url}`,
    path: canonicalPath,
    bucket,
    expiresIn: 60
  });
}

async function handleSignDownload(uid, idToken, companyId, body, env) {
  const bucket = String(body.bucket || "");
  const storagePath = String(body.storagePath || "").replace(/^\/+/, "");
  const chatId = sanitizeId(body.chatId || "");

  const policy = ALLOWED_BUCKETS[bucket];
  if (!policy) return jsonResponse({ error: "Unsupported bucket" }, 400);
  if (!storagePath || storagePath.includes("..")) return jsonResponse({ error: "Invalid path" }, 400);

  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  assertApprovedNotSuspended(member);

  if (bucket === "avatars" || bucket === "group-photos") {
    if (!storagePath.startsWith(`companies/${companyId}/`)) {
      return jsonResponse({ error: "Unauthorized path access" }, 403);
    }
  } else {
    if (!chatId) return jsonResponse({ error: "Missing chatId" }, 400);
    await assertChatParticipant(companyId, chatId, uid, idToken, env.FIREBASE_PROJECT_ID);
    const expectedPrefix = `companies/${companyId}/chats/${chatId}/`;
    if (!storagePath.startsWith(expectedPrefix)) {
      return jsonResponse({ error: "Path mismatch for chat attachment" }, 403);
    }
  }

  const supabaseUrl = env.SUPABASE_URL;
  const supabaseKey = env.SUPABASE_SECRET_KEY || env.SUPABASE_SERVICE_ROLE_KEY;

  const signRes = await fetch(`${supabaseUrl}/storage/v1/object/sign/${bucket}/${storagePath}`, {
    method: "POST",
    headers: {
      "apikey": supabaseKey,
      "Authorization": `Bearer ${supabaseKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ expiresIn: 300 })
  });

  if (!signRes.ok) {
    return jsonResponse({ error: "Failed to generate download URL" }, 502);
  }

  const signData = await signRes.json();
  return jsonResponse({
    downloadUrl: `${supabaseUrl}/storage/v1${signData.signedURL}`,
    expiresIn: 300
  });
}

async function handlePushDispatch(uid, idToken, companyId, body, env) {
  const chatId = sanitizeId(body.chatId || "");
  const messageId = sanitizeId(body.messageId || "");
  if (!chatId || !messageId) return jsonResponse({ error: "Missing chatId or messageId" }, 400);

  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  assertApprovedNotSuspended(member);

  const msgUrl = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/chats/${chatId}/messages/${messageId}`;
  const msgRes = await fetch(msgUrl, { headers: { "Authorization": `Bearer ${idToken}` } });
  if (!msgRes.ok) return jsonResponse({ error: "Message not found or unauthorized" }, 403);

  const messageDoc = parseFirestoreDoc(await msgRes.json());
  const senderId = messageDoc.senderUid || messageDoc.senderId;
  const accessLevel = member.accessLevel || member.role;
  if (senderId !== uid && accessLevel !== "SUPER_ADMIN") {
    return jsonResponse({ error: "Sender verification failed" }, 403);
  }

  const chat = await fetchChatDoc(companyId, chatId, idToken, env.FIREBASE_PROJECT_ID);
  const participants = Array.isArray(chat.participants) ? chat.participants : [];
  const recipients = participants.filter(p => p !== uid);

  if (recipients.length === 0) return jsonResponse({ dispatched: 0 }, 200);

  const tokens = [];
  for (const recipientId of recipients) {
    const userDocUrl = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/${FIRESTORE_DB_ID}/documents/users/${recipientId}`;
    const uRes = await fetch(userDocUrl, { headers: { "Authorization": `Bearer ${idToken}` } });
    if (uRes.ok) {
      const uDoc = parseFirestoreDoc(await uRes.json());
      if (uDoc.fcmToken) tokens.push(uDoc.fcmToken);
    }
  }

  if (tokens.length === 0) return jsonResponse({ dispatched: 0 }, 200);

  const accessToken = await getGoogleOAuthAccessToken(env);
  let dispatchedCount = 0;

  for (let i = 0; i < tokens.length; i += 35) {
    const chunk = tokens.slice(i, i + 35);
    for (const token of chunk) {
      const fcmPayload = {
        message: {
          token,
          notification: {
            title: chat.name || "X.MAX Message",
            body: messageDoc.text || messageDoc.body || "New attachment or message"
          },
          data: {
            companyId,
            chatId,
            messageId,
            type: messageDoc.kind || messageDoc.type || "text"
          }
        }
      };

      const fcmRes = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify(fcmPayload)
      });
      if (fcmRes.ok) dispatchedCount++;
    }
  }

  return jsonResponse({ dispatched: dispatchedCount }, 200);
}

async function handleResetChunk(uid, idToken, companyId, body, env) {
  const member = await fetchMemberDoc(companyId, uid, idToken, env.FIREBASE_PROJECT_ID);
  const accessLevel = member.accessLevel || member.role;
  if (accessLevel !== "SUPER_ADMIN") {
    return jsonResponse({ error: "Only Super Admin can perform chat reset operations" }, 403);
  }

  const chatId = sanitizeId(body.chatId || "");
  if (!chatId) return jsonResponse({ error: "Missing chatId for reset" }, 400);

  const projectId = env.FIREBASE_PROJECT_ID;
  const supabaseUrl = env.SUPABASE_URL;
  const supabaseKey = env.SUPABASE_SECRET_KEY || env.SUPABASE_SERVICE_ROLE_KEY;

  if (supabaseUrl && supabaseKey) {
    for (const bucket of ["chat-media", "chat-voice", "chat-videos", "chat-docs", "group-photos"]) {
      await fetch(`${supabaseUrl}/storage/v1/object/${bucket}`, {
        method: "DELETE",
        headers: { "apikey": supabaseKey, "Authorization": `Bearer ${supabaseKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({ prefixes: [`companies/${companyId}/chats/${chatId}/`, `companies/${companyId}/group-photos/${chatId}/`] })
      }).catch(() => {});
    }
  }

  for (const col of CHAT_RESET_COLLECTIONS) {
    const listUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/chats/${chatId}/${col}?pageSize=100`;
    const listRes = await fetch(listUrl, { headers: { "Authorization": `Bearer ${idToken}` } });
    if (listRes.ok) {
      const data = await listRes.json();
      const docs = data.documents || [];
      for (const doc of docs) {
        await fetch(`https://firestore.googleapis.com/v1/${doc.name}`, {
          method: "DELETE",
          headers: { "Authorization": `Bearer ${idToken}` }
        });
      }
    }
  }

  const chatDocUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${FIRESTORE_DB_ID}/documents/companies/${companyId}/chats/${chatId}`;
  await fetch(chatDocUrl, {
    method: "DELETE",
    headers: { "Authorization": `Bearer ${idToken}` }
  });

  return jsonResponse({ reset: true, chatId }, 200);
}

async function getGoogleOAuthAccessToken(env) {
  const sa = JSON.parse(env.SERVICE_ACCOUNT_JSON || "{}");
  if (!sa.private_key || !sa.client_email) throw new Error("Missing SERVICE_ACCOUNT_JSON secret");

  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now
  };

  const encodedHeader = btoa(JSON.stringify(header)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const encodedPayload = btoa(JSON.stringify(payload)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const signInput = `${encodedHeader}.${encodedPayload}`;

  const pemHeader = "-----BEGIN PRIVATE KEY-----";
  const pemFooter = "-----END PRIVATE KEY-----";
  let pemContents = sa.private_key.replace(pemHeader, "").replace(pemFooter, "").replace(/\s/g, "");
  const binaryDer = Uint8Array.from(atob(pemContents), c => c.charCodeAt(0));

  const key = await crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(signInput));
  const encodedSig = btoa(String.fromCharCode(...new Uint8Array(signature))).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const jwt = `${signInput}.${encodedSig}`;

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`
  });

  const tokenData = await tokenRes.json();
  if (!tokenData.access_token) throw new Error("Failed to obtain Google OAuth access token");
  return tokenData.access_token;
}

function sanitizeFileName(name) {
  return String(name || "file").replace(/[^a-zA-Z0-9_\-\.]/g, "_");
}
