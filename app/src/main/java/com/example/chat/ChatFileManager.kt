package com.example.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.chat.local.MediaCacheEntity
import com.example.chat.local.XMaxChatDatabase
import com.example.chat.remote.WorkerBridgeClient
import com.example.chat.remote.WorkerResult
import com.example.domain.CatalogueSeedData
import com.example.model.ChatAttachmentType
import com.example.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ProfilePhotoPreview(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val byteSize: Long
)

data class StagedChatAttachment(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: ChatAttachmentType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localCachePath: String,
    val base64Payload: String,
    val sha256: String = "",
    val bucket: String = WorkerBridgeClient.bucketForAttachmentType(type),
    val isHd: Boolean = false,
    val durationMs: Long = 0L,
    val waveform: List<Int> = emptyList(),
    val transcript: String = ""
)

data class PickedContactInfo(
    val name: String,
    val phone: String
)

object ChatFileManager {

    const val MAX_INLINE_RAW_BYTES = 520_000 // ~693 KB Base64 fallback under Firestore 1 MiB doc limit
    private const val MAX_CLOUDFLARE_IMAGE_BYTES = 5 * 1024 * 1024
    private const val MAX_CLOUDFLARE_VIDEO_BYTES = 15 * 1024 * 1024
    private const val MAX_CLOUDFLARE_AUDIO_BYTES = 3 * 1024 * 1024
    private const val MAX_CLOUDFLARE_DOC_BYTES = 10 * 1024 * 1024
    private const val TARGET_IMAGE_BYTES = 680_000
    private const val TARGET_HD_IMAGE_BYTES = 2_400_000
    private const val MAX_IMAGE_EDGE_PX = 1600
    private const val MAX_HD_IMAGE_EDGE_PX = 2560

    fun createCameraTempUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun extractHttpsUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("""https://[^\s<>"'\)]+""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?') }.distinct().take(3).toList()
    }

    fun extractPickedContact(context: Context, uri: Uri): PickedContactInfo? {
        return runCatching {
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty().trim() else ""
                    val num = if (numIdx >= 0) c.getString(numIdx).orEmpty().trim() else ""
                    if (name.isNotBlank() || num.isNotBlank()) PickedContactInfo(name.ifBlank { num }, num) else null
                } else null
            }
        }.getOrNull()
    }

    suspend fun prepareVoiceAttachmentFromFile(
        file: File,
        durationMs: Long,
        waveform: List<Int>,
        transcript: String
    ): Result<StagedChatAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.exists() && file.length() > 0L) { "Voice recording file is empty." }
            val bytes = file.readBytes()
            val cloudflareReady = WorkerBridgeClient.isConfigured()
            val maxAllowed = if (cloudflareReady) MAX_CLOUDFLARE_AUDIO_BYTES else MAX_INLINE_RAW_BYTES
            if (bytes.size > maxAllowed) {
                error("Voice note too large (${formatFileSize(bytes.size.toLong())}). Maximum is ${formatFileSize(maxAllowed.toLong())}.")
            }
            StagedChatAttachment(
                type = ChatAttachmentType.AUDIO,
                fileName = file.name,
                mimeType = "audio/mp4",
                sizeBytes = bytes.size.toLong(),
                localCachePath = file.absolutePath,
                base64Payload = if (bytes.size <= MAX_INLINE_RAW_BYTES) Base64.encodeToString(bytes, Base64.NO_WRAP) else "",
                sha256 = sha256Hex(bytes),
                bucket = WorkerBridgeClient.bucketForAttachmentType(ChatAttachmentType.AUDIO),
                durationMs = durationMs,
                waveform = waveform,
                transcript = transcript
            )
        }
    }

    suspend fun prepareAttachmentFromUri(
        context: Context,
        uri: Uri,
        preferredType: ChatAttachmentType? = null,
        hdMode: Boolean = false
    ): Result<StagedChatAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val detectedMime = resolver.getType(uri)?.trim().orEmpty()
            val resolvedName = queryDisplayName(context, uri)
                ?: inferFallbackName(preferredType, detectedMime)

            val resolvedType = when {
                preferredType == ChatAttachmentType.VIDEO || detectedMime.startsWith("video/") -> ChatAttachmentType.VIDEO
                preferredType == ChatAttachmentType.IMAGE || detectedMime.startsWith("image/") -> ChatAttachmentType.IMAGE
                preferredType == ChatAttachmentType.AUDIO || detectedMime.startsWith("audio/") -> ChatAttachmentType.AUDIO
                else -> ChatAttachmentType.FILE
            }

            val rawBytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read selected file.")

            if (rawBytes.isEmpty()) {
                error("The selected file is empty.")
            }

            val uploadsDir = File(context.filesDir, "chat_uploads").apply { mkdirs() }
            val safeFileName = sanitizeFileName(resolvedName)
            val cloudflareReady = WorkerBridgeClient.isConfigured()

            if (resolvedType == ChatAttachmentType.IMAGE) {
                val compressed = compressImageForChat(rawBytes, hdMode)
                val finalBytes = compressed ?: rawBytes
                val maxAllowed = if (cloudflareReady) MAX_CLOUDFLARE_IMAGE_BYTES else MAX_INLINE_RAW_BYTES
                if (finalBytes.size > maxAllowed) {
                    error("Image is too large even after compression (${formatFileSize(finalBytes.size.toLong())}). Maximum is ${formatFileSize(maxAllowed.toLong())}.")
                }
                val finalName = if (compressed != null && !safeFileName.endsWith(".jpg", ignoreCase = true) && !safeFileName.endsWith(".jpeg", ignoreCase = true)) {
                    safeFileName.substringBeforeLast('.') + ".jpg"
                } else safeFileName
                val finalMime = if (compressed != null) "image/jpeg" else detectedMime.ifBlank { "image/jpeg" }

                val cachedFile = File(uploadsDir, "${UUID.randomUUID().toString().take(8)}_$finalName")
                cachedFile.writeBytes(finalBytes)

                StagedChatAttachment(
                    type = ChatAttachmentType.IMAGE,
                    fileName = finalName,
                    mimeType = finalMime,
                    sizeBytes = finalBytes.size.toLong(),
                    localCachePath = cachedFile.absolutePath,
                    base64Payload = if (finalBytes.size <= MAX_INLINE_RAW_BYTES) Base64.encodeToString(finalBytes, Base64.NO_WRAP) else "",
                    sha256 = sha256Hex(finalBytes),
                    bucket = WorkerBridgeClient.bucketForAttachmentType(ChatAttachmentType.IMAGE),
                    isHd = hdMode
                )
            } else {
                val maxAllowed = when {
                    !cloudflareReady -> MAX_INLINE_RAW_BYTES
                    resolvedType == ChatAttachmentType.VIDEO -> MAX_CLOUDFLARE_VIDEO_BYTES
                    resolvedType == ChatAttachmentType.AUDIO -> MAX_CLOUDFLARE_AUDIO_BYTES
                    else -> MAX_CLOUDFLARE_DOC_BYTES
                }
                if (rawBytes.size > maxAllowed) {
                    error("File is too large (${formatFileSize(rawBytes.size.toLong())}). Maximum free-tier upload size is ${formatFileSize(maxAllowed.toLong())}.")
                }
                val finalMime = detectedMime.ifBlank {
                    when (resolvedType) {
                        ChatAttachmentType.VIDEO -> "video/mp4"
                        ChatAttachmentType.AUDIO -> "audio/mp4"
                        else -> inferMimeFromExtension(safeFileName)
                    }
                }
                val cachedFile = File(uploadsDir, "${UUID.randomUUID().toString().take(8)}_$safeFileName")
                cachedFile.writeBytes(rawBytes)

                StagedChatAttachment(
                    type = resolvedType,
                    fileName = safeFileName,
                    mimeType = finalMime,
                    sizeBytes = rawBytes.size.toLong(),
                    localCachePath = cachedFile.absolutePath,
                    base64Payload = if (rawBytes.size <= MAX_INLINE_RAW_BYTES) Base64.encodeToString(rawBytes, Base64.NO_WRAP) else "",
                    sha256 = sha256Hex(rawBytes),
                    bucket = WorkerBridgeClient.bucketForAttachmentType(resolvedType),
                    isHd = hdMode
                )
            }
        }
    }

    suspend fun resolveLocalFile(context: Context, message: ChatMessage): File? = withContext(Dispatchers.IO) {
        if (message.attachmentLocalPath.isNotBlank()) {
            val local = File(message.attachmentLocalPath)
            if (local.exists() && local.length() > 0L) {
                return@withContext local
            }
        }
        val uploadsDir = File(context.filesDir, "chat_uploads").apply { mkdirs() }
        val safeName = sanitizeFileName(message.attachmentName.ifBlank { "attachment_${message.id}" })
        val deterministicCache = File(uploadsDir, "${message.id.ifBlank { message.attachmentSha256.take(12) }}_$safeName")
        if (deterministicCache.exists() && deterministicCache.length() > 0L) {
            return@withContext deterministicCache
        }
        if (message.attachmentStoragePath.isNotBlank()) {
            val cachedRecord = runCatching {
                XMaxChatDatabase.getInstance(context).chatDao().getMediaCache(message.attachmentStoragePath)
            }.getOrNull()
            val cachedFile = cachedRecord?.localFilePath?.takeIf { it.isNotBlank() }?.let(::File)
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0L) {
                return@withContext cachedFile
            }
        }
        if (message.attachmentDataBase64.isBlank()) return@withContext null

        runCatching {
            val bytes = Base64.decode(message.attachmentDataBase64, Base64.DEFAULT)
            if (bytes.isEmpty()) return@runCatching null
            if (!deterministicCache.exists() || deterministicCache.length() == 0L) {
                deterministicCache.writeBytes(bytes)
            }
            deterministicCache
        }.getOrNull()
    }

    suspend fun resolveOrDownloadAttachment(
        context: Context,
        message: ChatMessage,
        companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID
    ): Result<File> = withContext(Dispatchers.IO) {
        resolveLocalFile(context, message)?.let { return@withContext Result.success(it) }

        if (message.attachmentStoragePath.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Attachment bytes are unavailable offline."))
        }
        if (!WorkerBridgeClient.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Cloudflare Worker URL is not configured to download remote files."))
        }

        val uploadsDir = File(context.filesDir, "chat_uploads").apply { mkdirs() }
        val safeName = sanitizeFileName(message.attachmentName.ifBlank { "attachment_${message.id}" })
        val keyPrefix = message.id.ifBlank { message.attachmentSha256.take(12).ifBlank { "r2" } }
        val target = File(uploadsDir, "${keyPrefix}_$safeName")

        when (
            val dl = WorkerBridgeClient.downloadChatFileFromCloudflare(
                companyId = companyId,
                chatId = message.channelId,
                storagePath = message.attachmentStoragePath,
                destination = target
            )
        ) {
            is WorkerResult.Success -> {
                runCatching {
                    XMaxChatDatabase.getInstance(context).chatDao().upsertMediaCache(
                        MediaCacheEntity(
                            storagePath = message.attachmentStoragePath,
                            localFilePath = dl.data.absolutePath,
                            mimeType = message.attachmentMimeType,
                            byteSize = dl.data.length(),
                            lastAccessedMillis = System.currentTimeMillis()
                        )
                    )
                }
                Result.success(dl.data)
            }
            is WorkerResult.RateLimited ->
                Result.failure(IllegalStateException("Cloudflare rate limit reached. Retry in ${dl.retryAfterSeconds}s."))
            is WorkerResult.NotConfigured ->
                Result.failure(IllegalStateException("Cloudflare Worker URL is not configured."))
            is WorkerResult.Error ->
                Result.failure(IllegalStateException(dl.message))
        }
    }

    suspend fun loadOrDownloadMessageBitmap(
        context: Context,
        message: ChatMessage,
        companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID,
        maxEdgePx: Int = 900
    ): Bitmap? = withContext(Dispatchers.IO) {
        decodeMessageBitmap(message, maxEdgePx)?.let { return@withContext it }
        val file = resolveOrDownloadAttachment(context, message, companyId).getOrNull() ?: return@withContext null
        decodeBitmapFileSampled(file, maxEdgePx)
    }

    private fun decodeBitmapFileSampled(file: File, maxEdgePx: Int): Bitmap? {
        return runCatching {
            if (!file.exists() || file.length() == 0L) return null
            val bytes = file.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val largest = max(bounds.outWidth, bounds.outHeight)
            if (largest <= 0) return null
            var sample = 1
            while (largest / sample > maxEdgePx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    private val base64BitmapCache = android.util.LruCache<Int, Bitmap>(48)

    suspend fun loadProfilePhotoPreview(context: Context, uri: Uri): Result<ProfilePhotoPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val tempDir = File(context.cacheDir, "profile_tmp").apply { mkdirs() }
                val tempFile = File(tempDir, "picked_${UUID.randomUUID().toString().take(8)}.img")
                try {
                    val copiedBytes = runCatching {
                        val input = resolver.openInputStream(uri)
                            ?: resolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
                            ?: if (uri.scheme == "file") uri.path?.let { File(it).takeIf(File::exists)?.inputStream() } else null
                        input?.use { src ->
                            tempFile.outputStream().use { dst ->
                                src.copyTo(dst)
                            }
                        } ?: -1L
                    }.getOrDefault(-1L)

                    if (copiedBytes <= 0L || !tempFile.exists() || tempFile.length() == 0L) {
                        error("Could not read selected image.")
                    }

                    val orientation = runCatching {
                        ExifInterface(tempFile.absolutePath).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tempFile.absolutePath, bounds)

                    var decoded: Bitmap? = null
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        if (bounds.outWidth < 64 || bounds.outHeight < 64) {
                            error("Image is too small (minimum 64×64 px).")
                        }
                        var sample = 1
                        val largest = max(bounds.outWidth, bounds.outHeight)
                        while (largest / sample > 1024) {
                            sample *= 2
                        }
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample.coerceAtLeast(1)
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        decoded = BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                    }

                    if (decoded == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        decoded = runCatching {
                            val source = android.graphics.ImageDecoder.createSource(tempFile)
                            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                                val w = info.size.width
                                val h = info.size.height
                                val maxSide = max(w, h)
                                if (maxSide > 1024) {
                                    val ratio = 1024f / maxSide.toFloat()
                                    decoder.setTargetSize(
                                        (w * ratio).roundToInt().coerceAtLeast(1),
                                        (h * ratio).roundToInt().coerceAtLeast(1)
                                    )
                                }
                            }
                        }.getOrNull()
                    }

                    val finalRaw = decoded ?: error("Selected file is not a valid image.")
                    if (finalRaw.width < 64 || finalRaw.height < 64) {
                        error("Image is too small (minimum 64×64 px).")
                    }

                    val oriented = rotateBitmapByOrientation(finalRaw, orientation)
                    val scaled = if (max(oriented.width, oriented.height) > 1024) {
                        val ratio = 1024f / max(oriented.width, oriented.height).toFloat()
                        val target = Bitmap.createScaledBitmap(
                            oriented,
                            (oriented.width * ratio).roundToInt().coerceAtLeast(1),
                            (oriented.height * ratio).roundToInt().coerceAtLeast(1),
                            true
                        )
                        if (target !== oriented) oriented.recycle()
                        target
                    } else {
                        oriented
                    }

                    val sizeBytes = queryFileSize(context, uri).takeIf { it > 0L } ?: tempFile.length()
                    ProfilePhotoPreview(
                        bitmap = scaled,
                        width = scaled.width,
                        height = scaled.height,
                        byteSize = sizeBytes
                    )
                } finally {
                    runCatching { tempFile.delete() }
                }
            }
        }

    suspend fun compressCroppedProfilePhotoBase64(
        bitmap: Bitmap,
        zoom: Float = 1f,
        offsetXFrac: Float = 0f,
        offsetYFrac: Float = 0f
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeZoom = zoom.coerceIn(1f, 3f)
            val baseSquare = min(bitmap.width, bitmap.height).coerceAtLeast(1)
            val cropSide = (baseSquare / safeZoom).roundToInt().coerceIn(1, baseSquare)
            val maxShiftX = ((bitmap.width - cropSide) / 2f).coerceAtLeast(0f)
            val maxShiftY = ((bitmap.height - cropSide) / 2f).coerceAtLeast(0f)

            val centerX = bitmap.width / 2f - offsetXFrac.coerceIn(-1f, 1f) * maxShiftX
            val centerY = bitmap.height / 2f - offsetYFrac.coerceIn(-1f, 1f) * maxShiftY

            val cropX = (centerX - cropSide / 2f).roundToInt().coerceIn(0, (bitmap.width - cropSide).coerceAtLeast(0))
            val cropY = (centerY - cropSide / 2f).roundToInt().coerceIn(0, (bitmap.height - cropSide).coerceAtLeast(0))

            val square = Bitmap.createBitmap(bitmap, cropX, cropY, cropSide, cropSide)
            val scaled = if (cropSide != 256) {
                Bitmap.createScaledBitmap(square, 256, 256, true)
            } else {
                square
            }

            var quality = 88
            var encodedBytes: ByteArray
            do {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                encodedBytes = baos.toByteArray()
                quality -= 10
            } while (encodedBytes.size > 150_000 && quality >= 40)

            if (scaled !== square) scaled.recycle()
            if (square !== bitmap) square.recycle()

            val b64 = Base64.encodeToString(encodedBytes, Base64.NO_WRAP)
            if (b64.length > 420_000) {
                error("Profile photo is too large after compression.")
            }
            b64
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, rawBytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(rawBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return rotateBitmapByOrientation(bitmap, orientation)
    }

    private fun rotateBitmapByOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }

    suspend fun prepareProfilePhotoBase64(context: Context, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val preview = loadProfilePhotoPreview(context, uri).getOrThrow()
                val encoded = compressCroppedProfilePhotoBase64(preview.bitmap).getOrThrow()
                preview.bitmap.recycle()
                encoded
            }
        }

    fun decodeBase64Bitmap(base64: String, maxEdgePx: Int = 256): Bitmap? {
        if (base64.isBlank()) return null
        val key = base64.hashCode() * 31 + maxEdgePx
        base64BitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val largest = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (largest / sample > maxEdgePx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { bmp ->
                base64BitmapCache.put(key, bmp)
            }
        }.getOrNull()
    }

    fun decodeMessageBitmap(message: ChatMessage, maxEdgePx: Int = 900): Bitmap? {
        return runCatching {
            val file = message.attachmentLocalPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
            val bytes = when {
                file != null -> file.readBytes()
                message.attachmentDataBase64.isNotBlank() -> Base64.decode(message.attachmentDataBase64, Base64.DEFAULT)
                else -> return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val largest = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (largest / sample > maxEdgePx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    fun openOrShareAttachment(context: Context, file: File, mimeType: String, displayName: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val effectiveMime = mimeType.ifBlank { inferMimeFromExtension(displayName) }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(viewIntent)
            true
        }.getOrDefault(false)

        if (!launched) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = effectiveMime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Open or share $displayName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    suspend fun downloadAttachmentToDevice(
        context: Context,
        message: ChatMessage,
        companyId: String = CatalogueSeedData.DEFAULT_COMPANY_ID,
        showInGallery: Boolean = ChatSettingsStore.ensureLoaded(context).mediaVisibleInGallery
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedFile = resolveOrDownloadAttachment(context, message, companyId).getOrNull()
            val bytes = resolvedFile?.takeIf { it.exists() && it.length() > 0L }?.readBytes()
                ?: message.attachmentDataBase64
                    .takeIf { it.isNotBlank() }
                    ?.let { Base64.decode(it, Base64.DEFAULT) }
                ?: error("Attachment bytes are unavailable offline.")

            if (bytes.isEmpty()) error("Attachment file is empty.")

            val isImage = message.attachmentTypeEnum() == ChatAttachmentType.IMAGE
            val isVideo = message.attachmentTypeEnum() == ChatAttachmentType.VIDEO
            val rawName = message.attachmentName.ifBlank {
                when {
                    isImage -> "xmax_image_${message.id.ifBlank { "photo" }}.jpg"
                    isVideo -> "xmax_video_${message.id.ifBlank { "clip" }}.mp4"
                    else -> "xmax_file_${message.id.ifBlank { "doc" }}"
                }
            }
            val safeName = sanitizeFileName(rawName)
            val mime = message.attachmentMimeType.ifBlank { inferMimeFromExtension(safeName) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (showInGallery || (!isImage && !isVideo))) {
                val resolver = context.contentResolver
                val collection = when {
                    isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val relDir = when {
                    isImage -> "${Environment.DIRECTORY_PICTURES}/X.MAX"
                    isVideo -> "${Environment.DIRECTORY_MOVIES}/X.MAX"
                    else -> "${Environment.DIRECTORY_DOWNLOADS}/X.MAX"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val insertedUri = runCatching { resolver.insert(collection, values) }.getOrNull()
                if (insertedUri != null) {
                    val wrote = runCatching {
                        resolver.openOutputStream(insertedUri)?.use { out ->
                            out.write(bytes)
                            out.flush()
                            true
                        } ?: false
                    }.getOrDefault(false)
                    if (wrote) {
                        val doneValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        runCatching { resolver.update(insertedUri, doneValues, null, null) }
                        return@runCatching "$relDir/$safeName"
                    } else {
                        runCatching { resolver.delete(insertedUri, null, null) }
                    }
                }
            }

            val fallbackDir = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "chat_downloads")).apply { mkdirs() }
            if (!showInGallery) {
                runCatching { File(fallbackDir, ".nomedia").createNewFile() }
            }
            val outFile = File(fallbackDir, safeName)
            outFile.writeBytes(bytes)
            "App Private/$safeName"
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        return if (kb < 1024.0) {
            "${kb.toInt().coerceAtLeast(1)} KB"
        } else {
            String.format("%.1f MB", kb / 1024.0)
        }
    }

    private fun compressImageForChat(rawBytes: ByteArray, hdMode: Boolean = false): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val maxEdge = if (hdMode) MAX_HD_IMAGE_EDGE_PX else MAX_IMAGE_EDGE_PX
            val targetBytes = if (hdMode) TARGET_HD_IMAGE_BYTES else TARGET_IMAGE_BYTES

            var sample = 1
            val maxDim = max(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > maxEdge) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val rawDecoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
            val decoded = applyExifOrientation(rawDecoded, rawBytes)

            val scaled = if (max(decoded.width, decoded.height) > maxEdge) {
                val ratio = maxEdge.toFloat() / max(decoded.width, decoded.height).toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }

            var quality = if (hdMode) 94 else 88
            var result: ByteArray
            do {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                result = baos.toByteArray()
                quality -= 10
            } while (result.size > targetBytes && quality >= 48)

            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            result
        }.getOrNull()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else null
            }
        }.getOrNull()
    }

    private fun queryFileSize(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun inferFallbackName(type: ChatAttachmentType?, mime: String): String {
        val ext = when {
            mime.contains("pdf") -> "pdf"
            mime.contains("png") -> "png"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
            mime.contains("wav") -> "wav"
            mime.contains("m4a") || mime.contains("mp4") -> "m4a"
            mime.contains("word") -> "docx"
            mime.contains("sheet") || mime.contains("excel") -> "xlsx"
            type == ChatAttachmentType.IMAGE -> "jpg"
            type == ChatAttachmentType.AUDIO -> "m4a"
            else -> "bin"
        }
        val prefix = when (type) {
            ChatAttachmentType.IMAGE -> "image"
            ChatAttachmentType.AUDIO -> "audio"
            else -> "file"
        }
        return "${prefix}_${System.currentTimeMillis()}.$ext"
    }

    private fun inferMimeFromExtension(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".csv") -> "text/csv"
            lower.endsWith(".doc") -> "application/msword"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".xls") -> "application/vnd.ms-excel"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "upload_${System.currentTimeMillis()}" }
    }
}
