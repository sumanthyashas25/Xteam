package com.example.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.model.ChatAttachmentType
import com.example.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
    val base64Payload: String
)

object ChatFileManager {

    private const val MAX_INLINE_RAW_BYTES = 520_000 // ~693 KB Base64, safely under Firestore 1 MiB doc limit
    private const val TARGET_IMAGE_BYTES = 320_000
    private const val MAX_IMAGE_EDGE_PX = 1280

    suspend fun prepareAttachmentFromUri(
        context: Context,
        uri: Uri,
        preferredType: ChatAttachmentType? = null
    ): Result<StagedChatAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val detectedMime = resolver.getType(uri)?.trim().orEmpty()
            val resolvedName = queryDisplayName(context, uri)
                ?: inferFallbackName(preferredType, detectedMime)

            val resolvedType = when {
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

            if (resolvedType == ChatAttachmentType.IMAGE) {
                val compressed = compressImageForChat(rawBytes)
                val finalBytes = compressed ?: rawBytes
                if (finalBytes.size > MAX_INLINE_RAW_BYTES) {
                    error("Image is too large even after compression (${formatFileSize(finalBytes.size.toLong())}). Choose a smaller image.")
                }
                val finalName = if (compressed != null && !safeFileName.endsWith(".jpg", ignoreCase = true) && !safeFileName.endsWith(".jpeg", ignoreCase = true)) {
                    safeFileName.substringBeforeLast('.') + ".jpg"
                } else safeFileName
                val finalMime = if (compressed != null) "image/jpeg" else detectedMime.ifBlank { "image/*" }

                val cachedFile = File(uploadsDir, "${UUID.randomUUID().toString().take(8)}_$finalName")
                cachedFile.writeBytes(finalBytes)

                StagedChatAttachment(
                    type = ChatAttachmentType.IMAGE,
                    fileName = finalName,
                    mimeType = finalMime,
                    sizeBytes = finalBytes.size.toLong(),
                    localCachePath = cachedFile.absolutePath,
                    base64Payload = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
                )
            } else {
                if (rawBytes.size > MAX_INLINE_RAW_BYTES) {
                    error("File is too large (${formatFileSize(rawBytes.size.toLong())}). Maximum upload size is ${formatFileSize(MAX_INLINE_RAW_BYTES.toLong())}.")
                }
                val finalMime = detectedMime.ifBlank {
                    when (resolvedType) {
                        ChatAttachmentType.AUDIO -> "audio/*"
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
                    base64Payload = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
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
        if (message.attachmentDataBase64.isBlank()) return@withContext null

        runCatching {
            val bytes = Base64.decode(message.attachmentDataBase64, Base64.DEFAULT)
            if (bytes.isEmpty()) return@runCatching null
            val uploadsDir = File(context.filesDir, "chat_uploads").apply { mkdirs() }
            val safeName = sanitizeFileName(message.attachmentName.ifBlank { "attachment_${message.id}" })
            val target = File(uploadsDir, "${message.id}_$safeName")
            if (!target.exists() || target.length() == 0L) {
                target.writeBytes(bytes)
            }
            target
        }.getOrNull()
    }

    private val base64BitmapCache = android.util.LruCache<Int, Bitmap>(48)

    suspend fun loadProfilePhotoPreview(context: Context, uri: Uri): Result<ProfilePhotoPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read selected image.")
                if (rawBytes.isEmpty()) error("Selected image is empty.")
                if (rawBytes.size > 15_000_000) error("Image is too large (max 15 MB).")

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    error("Selected file is not a valid image.")
                }
                if (bounds.outWidth < 64 || bounds.outHeight < 64) {
                    error("Image is too small (minimum 64×64 px).")
                }

                var sample = 1
                val largest = max(bounds.outWidth, bounds.outHeight)
                while (largest / sample > 1024) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
                val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                    ?: error("Unable to decode selected image.")

                val oriented = applyExifOrientation(decoded, rawBytes)
                ProfilePhotoPreview(
                    bitmap = oriented,
                    width = oriented.width,
                    height = oriented.height,
                    byteSize = rawBytes.size.toLong()
                )
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

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        return if (kb < 1024.0) {
            "${kb.toInt().coerceAtLeast(1)} KB"
        } else {
            String.format("%.1f MB", kb / 1024.0)
        }
    }

    private fun compressImageForChat(rawBytes: ByteArray): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val maxDim = max(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > MAX_IMAGE_EDGE_PX) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null

            val scaled = if (max(decoded.width, decoded.height) > MAX_IMAGE_EDGE_PX) {
                val ratio = MAX_IMAGE_EDGE_PX.toFloat() / max(decoded.width, decoded.height).toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                decoded
            }

            var quality = 85
            var result: ByteArray
            do {
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                result = baos.toByteArray()
                quality -= 12
            } while (result.size > TARGET_IMAGE_BYTES && quality >= 36)

            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            result
        }.getOrNull()
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
            lower.endsWith(".doc") -> "application/msword"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".xls") -> "application/vnd.ms-excel"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "*/*"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "upload_${System.currentTimeMillis()}" }
    }
}
