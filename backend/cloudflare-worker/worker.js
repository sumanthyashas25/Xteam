/**
 * X.MAX Free-Tier Firebase + Cloudflare R2 Chat Storage & Push Proxy
 * - Runs 100% on Cloudflare Workers Free Plan + Cloudflare R2 Free Tier (10 GB/mo, $0 egress)
 * - Verifies Firebase Auth ID tokens via Google public JWKs (WebCrypto RS256 with cached JWKs)
 * - Verifies Firestore company membership & chat_channels access against named Firestore DB
 * - Streams uploads & authenticated downloads directly to/from Cloudflare R2 (CHAT_BUCKET)
 * - Dispatches optional FCM HTTP v1 push notifications & Super Admin R2+Firestore cleanup
 */

const GOOGLE_JWKS_URL =
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const DEFAULT_FIRESTORE_DB_ID =
  "ai-studio-android-xteam-28ae82d9-933c-4b9e-8445-3ca11186cacb";

const ALLOWED_BUCKETS = {
  "avatars": {
    maxBytes: 2 * 1024 * 1024,
    mimes: ["image/jpeg", "image/png", "image/webp"],
  },
  "group-photos": {
    maxBytes: 2 * 1024 * 1024,
    mimes: ["image/jpeg", "image/png", "image/webp"],
  },
  "chat-media": {
    maxBytes: 5 * 1024 * 1024,
    mimes: ["image/jpeg", "image/png", "image/webp", "image/gif"],
  },
  "chat-voice": {
    maxBytes: 3 * 1024 * 1024,
    mimes: [
      "audio/mp4",
      "audio/aac",
      "audio/m4a",
      "audio/x-m4a",
      "audio/mpeg",
      "audio/wav",
      "audio/ogg",
    ],
  },
  "chat-videos": {
    maxBytes: 15 * 1024 * 1024,
    mimes: ["video/mp4", "video/webm", "image/jpeg"],
  },
  "chat-docs": {
    maxBytes: 10 * 1024 * 1024,
    mimes: [
      "application/pdf",
      "text/plain",
      "text/csv",
      "application/msword",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.ms-excel",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "application/vnd.ms-powerpoint",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "application/zip",
      "application/x-zip-compressed",
    ],
  },
};

const CHAT_RESET_COLLECTIONS = ["messages", "reactions", "receipts", "polls", "reports"];

let cachedJwks = null;
let jwksFetchedAt = 0;
const JWKS_TTL_MS = 3600_000;

const rateBuckets = new Map();
const MAX_RATE_BUCKETS = 10000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const cors = buildCorsHeaders(request, env);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: cors });
    }

    if (request.method === "GET" && url.pathname === "/v1/health") {
      return jsonResponse(
        {
          ok: true,
          plan: "free",
          storageProvider: env.CHAT_BUCKET ? "CLOUDFLARE_R2" : "UNCONFIGURED",
          firebaseProjectId: env.FIREBASE_PROJECT_ID || "",
          firestoreDbId: env.FIRESTORE_DB_ID || DEFAULT_FIRESTORE_DB_ID,
        },
        200,
        cors
      );
    }

    try {
      const projectId = env.FIREBASE_PROJECT_ID;
      if (!projectId) {
        return jsonResponse({ error: "FIREBASE_PROJECT_ID is not configured" }, 500, cors);
      }
      const firestoreDbId = env.FIRESTORE_DB_ID || DEFAULT_FIRESTORE_DB_ID;

      const authHeader = request.headers.get("Authorization") || "";
      const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
      if (!idToken) {
        return jsonResponse({ error: "Missing Firebase Auth Bearer token" }, 401, cors);
      }

      const claims = await verifyFirebaseIdToken(idToken, projectId);
      const uid = claims.sub;

      const clientIp = request.headers.get("CF-Connecting-IP") || "global";
      if (!checkRateLimit(`${clientIp}:${uid}:${url.pathname}`, 60, 60_000)) {
        return jsonResponse(
          { error: "Rate limit exceeded" },
          429,
          { ...cors, "Retry-After": "30" }
        );
      }

      // Direct Cloudflare R2 Upload (streamed binary, zero base64 bloat)
      if (request.method === "POST" && url.pathname === "/v1/storage/upload") {
        return await handleR2Upload(request, url, uid, idToken, projectId, firestoreDbId, env, cors);
      }

      // Authenticated Cloudflare R2 Download
      if (request.method === "GET" && url.pathname === "/v1/storage/download") {
        return await handleR2Download(url, uid, idToken, projectId, firestoreDbId, env, cors);
      }

      // Authenticated Cloudflare R2 Delete
      if (request.method === "DELETE" && url.pathname === "/v1/storage/object") {
        const body = await request.json().catch(() => ({}));
        return await handleR2Delete(uid, idToken, body, projectId, firestoreDbId, env, cors);
      }

      if (request.method !== "POST") {
        return jsonResponse({ error: "Method not allowed" }, 405, cors);
      }

      const body = await request.json().catch(() => ({}));
      const companyId = sanitizeId(body.companyId || "");
      if (!companyId) {
        return jsonResponse({ error: "Missing companyId" }, 400, cors);
      }

      if (url.pathname === "/v1/notifications/dispatch") {
        return await handlePushDispatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }
      if (url.pathname === "/v1/admin/reset-chunk") {
        return await handleResetChunk(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors);
      }

      return jsonResponse({ error: "Not found" }, 404, cors);
    } catch (err) {
      return jsonResponse(
        { error: err.message || "Unauthorized or bad request" },
        err.status || 400,
        cors
      );
    }
  },
};

function buildCorsHeaders(request, env) {
  const origin = request.headers.get("Origin") || "";
  const allowedRaw = String(env.ALLOWED_ORIGINS || "").trim();
  if (!origin || !allowedRaw) {
    return { "Vary": "Origin" };
  }
  const allowedList = allowedRaw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (!allowedList.includes(origin)) {
    return { "Vary": "Origin" };
  }
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Vary": "Origin",
  };
}

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}

function sanitizeId(id) {
  return String(id || "").replace(/[^a-zA-Z0-9_\-\.]/g, "");
}

function sanitizeFileName(name) {
  const base = String(name || "file")
    .split(/[\\/]/)
    .pop()
    .replace(/[^a-zA-Z0-9._\-]/g, "_")
    .replace(/^\.+/, "")
    .slice(0, 120);
  return base || "file";
}

function isSafeStoragePath(path) {
  if (!path || typeof path !== "string") return false;
  if (path.startsWith("/") || path.includes("..") || path.includes("//") || path.includes("\\")) {
    return false;
  }
  return /^[a-zA-Z0-9_\-\.\/]+$/.test(path) && path.length <= 500;
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

function decodeBase64UrlToUint8Array(b64url) {
  let b64 = String(b64url || "").replace(/-/g, "+").replace(/_/g, "/");
  while (b64.length % 4 !== 0) {
    b64 += "=";
  }
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function decodeBase64UrlJson(b64url) {
  const bytes = decodeBase64UrlToUint8Array(b64url);
  return JSON.parse(new TextDecoder().decode(bytes));
}

async function verifyFirebaseIdToken(token, projectId) {
  const parts = token.split(".");
  if (parts.length !== 3) {
    const err = new Error("Invalid JWT structure");
    err.status = 401;
    throw err;
  }

  let header, payload;
  try {
    header = decodeBase64UrlJson(parts[0]);
    payload = decodeBase64UrlJson(parts[1]);
  } catch (e) {
    const err = new Error("Invalid JWT encoding");
    err.status = 401;
    throw err;
  }

  if (header.alg !== "RS256" || !header.kid) {
    const err = new Error("Unsupported JWT algorithm or missing kid");
    err.status = 401;
    throw err;
  }

  const now = Math.floor(Date.now() / 1000);
  if (!payload.exp || payload.exp < now) {
    const err = new Error("Firebase token expired");
    err.status = 401;
    throw err;
  }
  if (!payload.iat || payload.iat > now + 300) {
    const err = new Error("Firebase token issued in future");
    err.status = 401;
    throw err;
  }
  if (payload.iss !== `https://securetoken.google.com/${projectId}`) {
    const err = new Error("Invalid Firebase token issuer");
    err.status = 401;
    throw err;
  }
  if (payload.aud !== projectId) {
    const err = new Error("Invalid Firebase token audience");
    err.status = 401;
    throw err;
  }
  if (!payload.sub || typeof payload.sub !== "string" || payload.sub.length === 0) {
    const err = new Error("Invalid Firebase token subject");
    err.status = 401;
    throw err;
  }

  let jwks = await fetchGoogleJwks(false);
  let jwk = jwks.find((j) => j.kid === header.kid);
  if (!jwk) {
    jwks = await fetchGoogleJwks(true);
    jwk = jwks.find((j) => j.kid === header.kid);
  }
  if (!jwk) {
    const err = new Error("Matching Firebase signing key not found");
    err.status = 401;
    throw err;
  }

  const pubKey = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: { name: "SHA-256" } },
    false,
    ["verify"]
  );

  const data = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const sig = decodeBase64UrlToUint8Array(parts[2]);

  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pubKey, sig, data);
  if (!valid) {
    const err = new Error("Invalid Firebase token signature");
    err.status = 401;
    throw err;
  }

  return payload;
}

async function fetchGoogleJwks(forceRefresh) {
  const now = Date.now();
  if (!forceRefresh && cachedJwks && now - jwksFetchedAt < JWKS_TTL_MS) {
    return cachedJwks;
  }
  const res = await fetch(GOOGLE_JWKS_URL);
  if (!res.ok) throw new Error("Failed to fetch Google public JWKs");
  const data = await res.json();
  cachedJwks = data.keys || [];
  jwksFetchedAt = now;
  return cachedJwks;
}

async function fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId) {
  const url = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/members/${uid}`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (!res.ok) {
    const errObj = await res.json().catch(() => ({}));
    const err = new Error(errObj.error?.message || "Membership verification failed");
    err.status = res.status === 404 ? 403 : res.status;
    throw err;
  }
  return parseFirestoreDoc(await res.json());
}

function assertApprovedNotSuspended(member) {
  if (
    !member ||
    (member.status !== "APPROVED" && member.status !== "active") ||
    member.isSuspended === true ||
    member.active === false ||
    member.messagingSuspended === true
  ) {
    const err = new Error("User account is inactive, suspended, or unauthorized");
    err.status = 403;
    throw err;
  }
}

async function fetchChannelOrChatDoc(companyId, chatId, idToken, projectId, firestoreDbId) {
  // Primary chat collection used by ChatRepository.kt
  const channelUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_channels/${chatId}`;
  const chRes = await fetch(channelUrl, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (chRes.ok) {
    return { source: "chat_channels", doc: parseFirestoreDoc(await chRes.json()) };
  }

  // Fallback legacy chats collection
  const legacyUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chats/${chatId}`;
  const legRes = await fetch(legacyUrl, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (legRes.ok) {
    return { source: "chats", doc: parseFirestoreDoc(await legRes.json()) };
  }

  const err = new Error("Chat channel not found or access denied");
  err.status = 403;
  throw err;
}

async function assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId) {
  const { source, doc } = await fetchChannelOrChatDoc(
    companyId,
    chatId,
    idToken,
    projectId,
    firestoreDbId
  );

  const isSuperOrAdmin =
    member.accessLevel === "SUPER_ADMIN" ||
    member.accessLevel === "ADMIN" ||
    member.role === "SUPER_ADMIN" ||
    member.role === "ADMIN";

  if (source === "chat_channels") {
    const channelType = String(doc.channelType || "GENERAL").toUpperCase();
    const participants = Array.isArray(doc.participantUids) ? doc.participantUids : [];
    if (channelType === "DIRECT") {
      if (!participants.includes(uid)) {
        const err = new Error("User is not a participant in this direct chat");
        err.status = 403;
        throw err;
      }
      return { chat: doc, isSuperOrAdmin, isParticipant: true };
    }
    if (["GENERAL", "PROJECT", "DEPARTMENT"].includes(channelType)) {
      return { chat: doc, isSuperOrAdmin, isParticipant: true };
    }
    const err = new Error("Unsupported chat channel type");
    err.status = 403;
    throw err;
  }

  // Legacy chats validation
  const legacyType = String(doc.type || "").toUpperCase();
  const participants = Array.isArray(doc.participantIds)
    ? doc.participantIds
    : Array.isArray(doc.participants)
      ? doc.participants
      : [];

  if (legacyType === "SAVED" || legacyType === "SAVED_MESSAGES") {
    if (doc.ownerUid !== uid) {
      const err = new Error("Unauthorized saved messages access");
      err.status = 403;
      throw err;
    }
    return { chat: doc, isSuperOrAdmin, isParticipant: true };
  }

  if (!participants.includes(uid) && !["ANNOUNCEMENT", "CHANNEL"].includes(legacyType) && !isSuperOrAdmin) {
    const err = new Error("User is not a participant of this chat");
    err.status = 403;
    throw err;
  }

  return { chat: doc, isSuperOrAdmin, isParticipant: participants.includes(uid) };
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
      result[key] = (val.arrayValue.values || []).map(
        (v) => v.stringValue ?? v.integerValue ?? v.booleanValue ?? ""
      );
    } else if (val.mapValue !== undefined) {
      result[key] = parseFirestoreDoc({ fields: val.mapValue.fields });
    }
  }
  return result;
}

async function handleR2Upload(request, url, uid, idToken, projectId, firestoreDbId, env, cors) {
  if (!env.CHAT_BUCKET) {
    return jsonResponse(
      { error: "Cloudflare R2 binding (CHAT_BUCKET) is not configured in wrangler.toml" },
      500,
      cors
    );
  }

  const companyId = sanitizeId(url.searchParams.get("companyId") || "");
  const chatId = sanitizeId(url.searchParams.get("chatId") || "");
  const bucket = String(url.searchParams.get("bucket") || "chat-docs");
  const rawName = url.searchParams.get("fileName") || "attachment";
  const sha256 = sanitizeId(url.searchParams.get("sha256") || "").slice(0, 128);
  const rawContentType = (request.headers.get("Content-Type") || "").split(";")[0].trim().toLowerCase();

  if (!companyId) return jsonResponse({ error: "Missing companyId" }, 400, cors);

  const policy = ALLOWED_BUCKETS[bucket];
  if (!policy) return jsonResponse({ error: "Unsupported storage bucket" }, 400, cors);

  if (!policy.mimes.includes(rawContentType)) {
    return jsonResponse({ error: `Unsupported MIME type: ${rawContentType || "unknown"}` }, 415, cors);
  }

  const contentLenHeader = Number(request.headers.get("Content-Length") || 0);
  if (contentLenHeader > policy.maxBytes) {
    return jsonResponse(
      { error: `File size exceeds free-tier limit (${policy.maxBytes} bytes)` },
      413,
      cors
    );
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  let prefix = "";
  if (bucket === "avatars") {
    prefix = `companies/${companyId}/avatars/${uid}/`;
  } else if (bucket === "group-photos") {
    if (!chatId) return jsonResponse({ error: "Missing chatId for group photo" }, 400, cors);
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
    prefix = `companies/${companyId}/group-photos/${chatId}/`;
  } else {
    if (!chatId) return jsonResponse({ error: "Missing chatId for chat attachment" }, 400, cors);
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
    prefix = `companies/${companyId}/chats/${chatId}/`;
  }

  const safeName = sanitizeFileName(rawName);
  const storagePath = `${prefix}${crypto.randomUUID()}_${safeName}`;
  if (!isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid storage path" }, 400, cors);
  }

  const bytes = await request.arrayBuffer();
  if (bytes.byteLength <= 0) {
    return jsonResponse({ error: "Empty file payload" }, 400, cors);
  }
  if (bytes.byteLength > policy.maxBytes) {
    return jsonResponse(
      { error: `File size exceeds free-tier limit (${policy.maxBytes} bytes)` },
      413,
      cors
    );
  }

  await env.CHAT_BUCKET.put(storagePath, bytes, {
    httpMetadata: {
      contentType: rawContentType,
    },
    customMetadata: {
      companyId,
      chatId,
      bucket,
      uploadedBy: uid,
      sha256,
      originalName: safeName,
    },
  });

  return jsonResponse(
    {
      ok: true,
      provider: "CLOUDFLARE_R2",
      bucket,
      storagePath,
      byteSize: bytes.byteLength,
      mimeType: rawContentType,
      sha256,
    },
    200,
    cors
  );
}

async function handleR2Download(url, uid, idToken, projectId, firestoreDbId, env, cors) {
  if (!env.CHAT_BUCKET) {
    return jsonResponse(
      { error: "Cloudflare R2 binding (CHAT_BUCKET) is not configured" },
      500,
      cors
    );
  }

  const companyId = sanitizeId(url.searchParams.get("companyId") || "");
  const chatId = sanitizeId(url.searchParams.get("chatId") || "");
  const storagePath = String(url.searchParams.get("storagePath") || "").replace(/^\/+/, "");

  if (!companyId) return jsonResponse({ error: "Missing companyId" }, 400, cors);
  if (!isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid storagePath" }, 400, cors);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  const isAvatarOrGroup =
    storagePath.startsWith(`companies/${companyId}/avatars/`) ||
    storagePath.startsWith(`companies/${companyId}/group-photos/`);

  if (!isAvatarOrGroup) {
    if (!chatId) return jsonResponse({ error: "Missing chatId" }, 400, cors);
    const expectedPrefix = `companies/${companyId}/chats/${chatId}/`;
    if (!storagePath.startsWith(expectedPrefix)) {
      return jsonResponse({ error: "Path mismatch for chat attachment" }, 403, cors);
    }
    await assertChatParticipant(companyId, chatId, uid, member, idToken, projectId, firestoreDbId);
  }

  const object = await env.CHAT_BUCKET.get(storagePath);
  if (!object) {
    return jsonResponse({ error: "File not found in Cloudflare R2" }, 404, cors);
  }

  const headers = new Headers(cors);
  object.writeHttpMetadata(headers);
  const downloadName = sanitizeFileName(
    object.customMetadata?.originalName || storagePath.split("/").pop() || "download"
  );
  headers.set("Content-Disposition", `attachment; filename="${downloadName}"`);
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("Cache-Control", "private, max-age=3600");
  headers.set("ETag", object.httpEtag);
  if (typeof object.size === "number") {
    headers.set("Content-Length", String(object.size));
  }

  return new Response(object.body, {
    status: 200,
    headers,
  });
}

async function handleR2Delete(uid, idToken, body, projectId, firestoreDbId, env, cors) {
  if (!env.CHAT_BUCKET) {
    return jsonResponse({ error: "Cloudflare R2 binding (CHAT_BUCKET) is not configured" }, 500, cors);
  }

  const companyId = sanitizeId(body.companyId || "");
  const chatId = sanitizeId(body.chatId || "");
  const storagePath = String(body.storagePath || "").replace(/^\/+/, "");

  if (!companyId || !isSafeStoragePath(storagePath)) {
    return jsonResponse({ error: "Invalid companyId or storagePath" }, 400, cors);
  }
  if (!storagePath.startsWith(`companies/${companyId}/`)) {
    return jsonResponse({ error: "Unauthorized company storage path" }, 403, cors);
  }

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);
  const isSuperAdmin = member.accessLevel === "SUPER_ADMIN" || member.role === "SUPER_ADMIN";

  const head = await env.CHAT_BUCKET.head(storagePath);
  if (!head) {
    return jsonResponse({ deleted: true, alreadyMissing: true }, 200, cors);
  }

  const isUploader = head.customMetadata?.uploadedBy === uid;
  if (!isSuperAdmin && !isUploader) {
    return jsonResponse({ error: "Only the uploader or Super Admin can delete this file" }, 403, cors);
  }

  if (chatId && storagePath.includes("/chats/") && !storagePath.startsWith(`companies/${companyId}/chats/${chatId}/`)) {
    return jsonResponse({ error: "Chat ID mismatch" }, 403, cors);
  }

  await env.CHAT_BUCKET.delete(storagePath);
  return jsonResponse({ deleted: true, storagePath }, 200, cors);
}

async function handlePushDispatch(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  const chatId = sanitizeId(body.chatId || "");
  if (!chatId) return jsonResponse({ error: "Missing chatId" }, 400, cors);

  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  assertApprovedNotSuspended(member);

  const { doc: chat } = await fetchChannelOrChatDoc(companyId, chatId, idToken, projectId, firestoreDbId);
  const targetUids = Array.isArray(body.targetUids)
    ? body.targetUids.map(sanitizeId).filter((p) => p && p !== uid).slice(0, 35)
    : [];

  if (targetUids.length === 0) return jsonResponse({ sentCount: 0 }, 200, cors);
  if (!env.SERVICE_ACCOUNT_JSON) {
    return jsonResponse({ sentCount: 0, skipped: "SERVICE_ACCOUNT_JSON secret not set" }, 200, cors);
  }

  const tokens = [];
  for (const recipientId of targetUids) {
    const tokenListUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/deviceTokens/${recipientId}/tokens?pageSize=5`;
    const tRes = await fetch(tokenListUrl, { headers: { Authorization: `Bearer ${idToken}` } });
    if (tRes.ok) {
      const data = await tRes.json();
      for (const doc of data.documents || []) {
        const parsed = parseFirestoreDoc(doc);
        if (parsed.token) tokens.push(parsed.token);
      }
    }
  }

  if (tokens.length === 0) return jsonResponse({ sentCount: 0 }, 200, cors);

  const accessToken = await getGoogleOAuthAccessToken(env);
  let sentCount = 0;

  for (const token of tokens.slice(0, 35)) {
    const fcmPayload = {
      message: {
        token,
        notification: {
          title: String(body.title || chat.title || chat.name || "X.MAX Chat").slice(0, 120),
          body: String(body.body || "New attachment or message").slice(0, 240),
        },
        data: {
          companyId,
          chatId,
          businessKind: String(body.businessKind || ""),
          businessId: String(body.businessId || ""),
        },
      },
    };

    const fcmRes = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(fcmPayload),
    });
    if (fcmRes.ok) sentCount++;
  }

  return jsonResponse({ sentCount }, 200, cors);
}

async function handleResetChunk(uid, idToken, companyId, body, projectId, firestoreDbId, env, cors) {
  const member = await fetchMemberDoc(companyId, uid, idToken, projectId, firestoreDbId);
  const accessLevel = member.accessLevel || member.role;
  if (accessLevel !== "SUPER_ADMIN") {
    return jsonResponse({ error: "Only Super Admin can perform chat reset operations" }, 403, cors);
  }

  const chatId = sanitizeId(body.chatId || "");
  if (!chatId) return jsonResponse({ error: "Missing chatId for reset" }, 400, cors);

  let deletedStorageInChunk = 0;
  if (env.CHAT_BUCKET) {
    const prefixes = [
      `companies/${companyId}/chats/${chatId}/`,
      `companies/${companyId}/group-photos/${chatId}/`,
    ];
    for (const prefix of prefixes) {
      const listed = await env.CHAT_BUCKET.list({ prefix, limit: 200 });
      const keys = listed.objects.map((obj) => obj.key);
      if (keys.length > 0) {
        await env.CHAT_BUCKET.delete(keys);
        deletedStorageInChunk += keys.length;
      }
    }
  }

  let deletedDocsInChunk = 0;
  for (const col of CHAT_RESET_COLLECTIONS) {
    const listUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_channels/${chatId}/${col}?pageSize=100`;
    const listRes = await fetch(listUrl, { headers: { Authorization: `Bearer ${idToken}` } });
    if (listRes.ok) {
      const data = await listRes.json();
      const docs = data.documents || [];
      for (const doc of docs) {
        const delRes = await fetch(`https://firestore.googleapis.com/v1/${doc.name}`, {
          method: "DELETE",
          headers: { Authorization: `Bearer ${idToken}` },
        });
        if (delRes.ok) deletedDocsInChunk++;
      }
    }
  }

  const chatDocUrl = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/${firestoreDbId}/documents/companies/${companyId}/chat_channels/${chatId}`;
  await fetch(chatDocUrl, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${idToken}` },
  });

  return jsonResponse(
    {
      completed: true,
      nextCollection: "",
      nextCursorDocId: "",
      deletedDocsInChunk,
      deletedStorageInChunk,
    },
    200,
    cors
  );
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
    iat: now,
  };

  const encodedHeader = btoa(JSON.stringify(header)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const encodedPayload = btoa(JSON.stringify(payload)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  const signInput = `${encodedHeader}.${encodedPayload}`;

  const pemContents = sa.private_key
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");
  const binaryDer = Uint8Array.from(atob(pemContents), (c) => c.charCodeAt(0));

  const key = await crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(signInput));
  const encodedSig = btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
  const jwt = `${signInput}.${encodedSig}`;

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`,
  });

  const tokenData = await tokenRes.json();
  if (!tokenData.access_token) throw new Error("Failed to obtain Google OAuth access token");
  return tokenData.access_token;
}
