package com.example.chat.remote

import android.util.Log
import com.example.BuildConfig
import com.example.model.ChatAttachmentType
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed class WorkerResult<out T> {
    data class Success<T>(val data: T) : WorkerResult<T>()
    data object NotConfigured : WorkerResult<Nothing>()
    data class RateLimited(val retryAfterSeconds: Int) : WorkerResult<Nothing>()
    data class Error(val message: String, val code: Int = 0) : WorkerResult<Nothing>()
}

data class CloudflareUploadResult(
    val provider: String,
    val bucket: String,
    val storagePath: String,
    val byteSize: Long,
    val mimeType: String,
    val sha256: String
)

data class WorkerHealthStatus(
    val ok: Boolean,
    val plan: String,
    val storageProvider: String,
    val firebaseProjectId: String,
    val firestoreDbId: String
)

data class SignedUploadGrant(
    val signedUrl: String,
    val storagePath: String,
    val bucket: String,
    val expiresInSec: Int
)

data class SignedDownloadGrant(
    val signedUrl: String,
    val expiresInSec: Int
)

data class ResetChunkResult(
    val completed: Boolean,
    val nextCollection: String,
    val nextCursorDocId: String,
    val deletedDocsInChunk: Int,
    val deletedStorageInChunk: Int
)

/**
 * Stateless Android client for the free-tier Cloudflare Worker + Cloudflare R2 backend.
 * Zero Cloudflare R2 or FCM secrets exist inside the Android APK.
 * Every storage and push operation is authenticated via the active user's Firebase Auth ID token.
 */
object WorkerBridgeClient {
    private const val TAG = "XMaxWorkerBridge"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val _transferProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())
    val transferProgress: kotlinx.coroutines.flow.StateFlow<Map<String, Float>> = _transferProgress

    fun updateTransferProgress(key: String, progress: Float?) {
        if (key.isBlank()) return
        val next = _transferProgress.value.toMutableMap()
        if (progress == null) next.remove(key) else next[key] = progress.coerceIn(0f, 1f)
        _transferProgress.value = next
    }

    fun isConfigured(): Boolean {
        val url = BuildConfig.CHAT_WORKER_BASE_URL.trim()
        return url.startsWith("https://") && !url.contains("your-subdomain")
    }

    internal fun baseUrl(): String = BuildConfig.CHAT_WORKER_BASE_URL.trim().trimEnd('/')

    fun bucketForAttachmentType(type: ChatAttachmentType): String = when (type) {
        ChatAttachmentType.IMAGE -> "chat-media"
        ChatAttachmentType.VIDEO -> "chat-videos"
        ChatAttachmentType.AUDIO -> "chat-voice"
        ChatAttachmentType.FILE, ChatAttachmentType.NONE -> "chat-docs"
    }

    internal fun buildUploadUrl(
        base: String,
        companyId: String,
        chatId: String,
        bucket: String,
        fileName: String,
        sha256: String
    ): String? {
        val parsed = base.trimEnd('/').toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .addPathSegments("v1/storage/upload")
            .addQueryParameter("companyId", companyId)
            .addQueryParameter("chatId", chatId)
            .addQueryParameter("bucket", bucket)
            .addQueryParameter("fileName", fileName)
            .addQueryParameter("sha256", sha256)
            .build()
            .toString()
    }

    internal fun buildDownloadUrl(
        base: String,
        companyId: String,
        chatId: String,
        storagePath: String
    ): String? {
        val parsed = base.trimEnd('/').toHttpUrlOrNull() ?: return null
        return parsed.newBuilder()
            .addPathSegments("v1/storage/download")
            .addQueryParameter("companyId", companyId)
            .addQueryParameter("chatId", chatId)
            .addQueryParameter("storagePath", storagePath)
            .build()
            .toString()
    }

    internal fun parseUploadResponse(json: JSONObject, fallbackBucket: String): CloudflareUploadResult {
        return CloudflareUploadResult(
            provider = json.optString("provider", "CLOUDFLARE_R2"),
            bucket = json.optString("bucket", fallbackBucket),
            storagePath = json.optString("storagePath"),
            byteSize = json.optLong("byteSize", 0L),
            mimeType = json.optString("mimeType"),
            sha256 = json.optString("sha256")
        )
    }

    private suspend fun fetchFirebaseIdToken(forceRefresh: Boolean = false): String? {
        return runCatching {
            val user = FirebaseAuth.getInstance().currentUser ?: return null
            user.getIdToken(forceRefresh).await()?.token
        }.getOrNull()
    }

    suspend fun checkHealth(): WorkerResult<WorkerHealthStatus> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        runCatching {
            val req = Request.Builder()
                .url("${baseUrl()}/v1/health")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use WorkerResult.Error("Health check failed (${resp.code})", resp.code)
                }
                val json = JSONObject(text)
                WorkerResult.Success(
                    WorkerHealthStatus(
                        ok = json.optBoolean("ok", false),
                        plan = json.optString("plan", "free"),
                        storageProvider = json.optString("storageProvider", "UNKNOWN"),
                        firebaseProjectId = json.optString("firebaseProjectId"),
                        firestoreDbId = json.optString("firestoreDbId")
                    )
                )
            }
        }.getOrElse { err ->
            WorkerResult.Error(err.message ?: "Worker health check error")
        }
    }

    suspend fun uploadChatFileToCloudflare(
        companyId: String,
        chatId: String,
        bucket: String,
        fileName: String,
        mimeType: String,
        file: File,
        sha256: String,
        progressKey: String = sha256.ifBlank { fileName }
    ): WorkerResult<CloudflareUploadResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (!file.exists() || file.length() <= 0L) {
            return@withContext WorkerResult.Error("Attachment file does not exist locally.")
        }
        val uploadUrl = buildUploadUrl(baseUrl(), companyId, chatId, bucket, fileName, sha256)
            ?: return@withContext WorkerResult.NotConfigured

        val initialToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required to authorize upload.")

        updateTransferProgress(progressKey, 0.05f)
        val firstAttempt = executeFileUpload(uploadUrl, initialToken, mimeType, file, bucket, progressKey)
        val finalResult = if (firstAttempt is WorkerResult.Error && firstAttempt.code == 401) {
            val refreshed = fetchFirebaseIdToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != initialToken) {
                executeFileUpload(uploadUrl, refreshed, mimeType, file, bucket, progressKey)
            } else firstAttempt
        } else firstAttempt
        updateTransferProgress(progressKey, null)
        finalResult
    }

    private fun executeFileUpload(
        uploadUrl: String,
        idToken: String,
        mimeType: String,
        file: File,
        fallbackBucket: String,
        progressKey: String = ""
    ): WorkerResult<CloudflareUploadResult> {
        return runCatching {
            val mediaType = mimeType.ifBlank { "application/octet-stream" }.toMediaType()
            val totalBytes = file.length().coerceAtLeast(1L)
            val countingBody = object : okhttp3.RequestBody() {
                override fun contentType() = mediaType
                override fun contentLength() = totalBytes
                override fun writeTo(sink: okio.BufferedSink) {
                    val buf = ByteArray(16 * 1024)
                    var uploaded = 0L
                    file.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            sink.write(buf, 0, read)
                            uploaded += read
                            updateTransferProgress(progressKey, uploaded.toFloat() / totalBytes.toFloat())
                        }
                    }
                }
            }
            val req = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $idToken")
                .post(countingBody)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
                    return@use WorkerResult.RateLimited(retry)
                }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        .orEmpty().ifBlank { "Cloudflare upload failed (${resp.code})" }
                    return@use WorkerResult.Error(msg, resp.code)
                }
                val json = JSONObject(text)
                val parsed = parseUploadResponse(json, fallbackBucket)
                if (parsed.storagePath.isBlank()) {
                    return@use WorkerResult.Error("Worker returned empty Cloudflare R2 storage path.", 502)
                }
                updateTransferProgress(progressKey, 1f)
                WorkerResult.Success(parsed)
            }
        }.getOrElse { err ->
            Log.w(TAG, "Cloudflare R2 upload failed: ${err.message}")
            WorkerResult.Error(err.message ?: "Network upload failure")
        }
    }

    suspend fun downloadChatFileFromCloudflare(
        companyId: String,
        chatId: String,
        storagePath: String,
        destination: File,
        progressKey: String = storagePath
    ): WorkerResult<File> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (storagePath.isBlank()) {
            return@withContext WorkerResult.Error("Missing Cloudflare storage path.", 400)
        }
        val downloadUrl = buildDownloadUrl(baseUrl(), companyId, chatId, storagePath)
            ?: return@withContext WorkerResult.NotConfigured

        val initialToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required to download attachment.")

        updateTransferProgress(progressKey, 0.05f)
        val firstAttempt = executeAuthenticatedDownload(downloadUrl, initialToken, destination, progressKey)
        val finalResult = if (firstAttempt is WorkerResult.Error && firstAttempt.code == 401) {
            val refreshed = fetchFirebaseIdToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != initialToken) {
                executeAuthenticatedDownload(downloadUrl, refreshed, destination, progressKey)
            } else firstAttempt
        } else firstAttempt
        updateTransferProgress(progressKey, null)
        finalResult
    }

    private fun executeAuthenticatedDownload(
        downloadUrl: String,
        idToken: String,
        destination: File,
        progressKey: String = ""
    ): WorkerResult<File> {
        return runCatching {
            destination.parentFile?.mkdirs()
            val tempFile = File(destination.parentFile, "${destination.name}.part")
            val req = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
                    return@use WorkerResult.RateLimited(retry)
                }
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        .orEmpty().ifBlank { "Cloudflare download failed (${resp.code})" }
                    return@use WorkerResult.Error(msg, resp.code)
                }
                val body = resp.body ?: return@use WorkerResult.Error("Empty response body", 502)
                val total = body.contentLength()
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0L) {
                                updateTransferProgress(progressKey, downloaded.toFloat() / total.toFloat())
                            }
                        }
                    }
                }
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    return@use WorkerResult.Error("Downloaded empty attachment", 502)
                }
                if (destination.exists()) destination.delete()
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
                updateTransferProgress(progressKey, 1f)
                WorkerResult.Success(destination)
            }
        }.getOrElse { err ->
            Log.w(TAG, "Cloudflare R2 download failed: ${err.message}")
            WorkerResult.Error(err.message ?: "File download failed")
        }
    }

    suspend fun deleteChatFileFromCloudflare(
        companyId: String,
        chatId: String,
        storagePath: String
    ): WorkerResult<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured() || storagePath.isBlank()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken(forceRefresh = false)
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("chatId", chatId)
            put("storagePath", storagePath)
        }
        runCatching {
            val req = Request.Builder()
                .url("${baseUrl()}/v1/storage/object")
                .header("Authorization", "Bearer $idToken")
                .delete(payload.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    WorkerResult.Success(Unit)
                } else {
                    WorkerResult.Error("R2 delete failed (${resp.code})", resp.code)
                }
            }
        }.getOrElse { err ->
            WorkerResult.Error(err.message ?: "Worker delete failure")
        }
    }

    suspend fun requestSignedUploadUrl(
        companyId: String,
        bucket: String,
        storagePath: String,
        mimeType: String,
        byteSize: Long,
        sha256: String,
        chatId: String = ""
    ): WorkerResult<SignedUploadGrant> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken()
            ?: return@withContext WorkerResult.Error("Sign in required to authorize upload.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("bucket", bucket)
            put("storagePath", storagePath)
            put("mimeType", mimeType)
            put("byteSize", byteSize)
            put("sha256", sha256)
            put("chatId", chatId)
        }
        executeWorkerPost("/v1/storage/sign-upload", idToken, payload) { json ->
            SignedUploadGrant(
                signedUrl = json.optString("signedUrl"),
                storagePath = json.optString("storagePath", storagePath),
                bucket = json.optString("bucket", bucket),
                expiresInSec = json.optInt("expiresInSec", 60)
            )
        }
    }

    suspend fun uploadFileToSignedUrl(
        signedUrl: String,
        file: File,
        mimeType: String
    ): WorkerResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(signedUrl)
                .put(file.asRequestBody(mimeType.toMediaType()))
                .header("x-upsert", "true")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    WorkerResult.Success(Unit)
                } else {
                    WorkerResult.Error("Storage upload failed (${resp.code})", resp.code)
                }
            }
        }.getOrElse { err ->
            WorkerResult.Error(err.message ?: "Network upload failure")
        }
    }

    suspend fun requestSignedDownloadUrl(
        companyId: String,
        bucket: String,
        storagePath: String,
        chatId: String = ""
    ): WorkerResult<SignedDownloadGrant> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken()
            ?: return@withContext WorkerResult.Error("Sign in required to read attachment.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("bucket", bucket)
            put("storagePath", storagePath)
            put("chatId", chatId)
        }
        executeWorkerPost("/v1/storage/sign-download", idToken, payload) { json ->
            SignedDownloadGrant(
                signedUrl = json.optString("signedUrl"),
                expiresInSec = json.optInt("expiresInSec", 300)
            )
        }
    }

    suspend fun downloadSignedFile(signedUrl: String, destination: File): WorkerResult<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                destination.parentFile?.mkdirs()
                val req = Request.Builder().url(signedUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext WorkerResult.Error("Download failed (${resp.code})", resp.code)
                    }
                    val body = resp.body ?: return@withContext WorkerResult.Error("Empty response body")
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    WorkerResult.Success(destination)
                }
            }.getOrElse { err ->
                WorkerResult.Error(err.message ?: "File download failed")
            }
        }

    suspend fun dispatchPushNotification(
        companyId: String,
        chatId: String,
        title: String,
        body: String,
        targetUids: List<String>,
        businessKind: String = "",
        businessId: String = "",
        priority: String = "NORMAL"
    ): WorkerResult<Int> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        if (targetUids.isEmpty()) return@withContext WorkerResult.Success(0)
        val idToken = fetchFirebaseIdToken()
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("chatId", chatId)
            put("title", title.take(120))
            put("body", body.take(240))
            put("targetUids", JSONArray(targetUids.take(35)))
            put("businessKind", businessKind)
            put("businessId", businessId)
            put("priority", priority)
        }
        executeWorkerPost("/v1/notifications/dispatch", idToken, payload) { json ->
            json.optInt("sentCount", 0)
        }
    }

    suspend fun executeResetChunk(
        companyId: String,
        operationId: String,
        cursorCollection: String,
        cursorDocId: String
    ): WorkerResult<ResetChunkResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext WorkerResult.NotConfigured
        val idToken = fetchFirebaseIdToken()
            ?: return@withContext WorkerResult.Error("Sign in required.")

        val payload = JSONObject().apply {
            put("companyId", companyId)
            put("operationId", operationId)
            put("cursorCollection", cursorCollection)
            put("cursorDocId", cursorDocId)
        }
        executeWorkerPost("/v1/admin/reset-chunk", idToken, payload) { json ->
            ResetChunkResult(
                completed = json.optBoolean("completed", false),
                nextCollection = json.optString("nextCollection", ""),
                nextCursorDocId = json.optString("nextCursorDocId", ""),
                deletedDocsInChunk = json.optInt("deletedDocsInChunk", 0),
                deletedStorageInChunk = json.optInt("deletedStorageInChunk", 0)
            )
        }
    }

    private fun <T> executeWorkerPost(
        path: String,
        idToken: String,
        payload: JSONObject,
        parser: (JSONObject) -> T
    ): WorkerResult<T> {
        return runCatching {
            val req = Request.Builder()
                .url("${baseUrl()}$path")
                .header("Authorization", "Bearer $idToken")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
                    return@use WorkerResult.RateLimited(retry)
                }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        .orEmpty().ifBlank { "Worker error ${resp.code}" }
                    return@use WorkerResult.Error(msg, resp.code)
                }
                val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                WorkerResult.Success(parser(json))
            }
        }.getOrElse { err ->
            Log.w(TAG, "Worker call $path failed: ${err.message}")
            WorkerResult.Error(err.message ?: "Worker connection error")
        }
    }
}
