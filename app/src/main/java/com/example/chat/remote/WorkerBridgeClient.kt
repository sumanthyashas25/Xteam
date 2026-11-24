package com.example.chat.remote

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
 * Stateless Android client for the free Cloudflare Worker backend proxy.
 * Zero Supabase or FCM Service Account secrets exist inside the Android APK.
 * Every request is authenticated via the active user's Firebase Auth ID token.
 */
object WorkerBridgeClient {
    private const val TAG = "XMaxWorkerBridge"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(): Boolean = BuildConfig.CHAT_WORKER_BASE_URL.trim().startsWith("https://")

    private fun baseUrl(): String = BuildConfig.CHAT_WORKER_BASE_URL.trim().trimEnd('/')

    private suspend fun fetchFirebaseIdToken(): String? {
        return runCatching {
            val user = FirebaseAuth.getInstance().currentUser ?: return null
            user.getIdToken(false).await()?.token
        }.getOrNull()
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
