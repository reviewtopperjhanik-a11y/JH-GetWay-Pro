package com.example.data.local

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.network.adapters.ContentPart
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID

enum class AttachmentCategory {
    IMAGE,
    DOCUMENT
}

enum class AttachmentImportError {
    UNSUPPORTED_TYPE,
    FILE_TOO_LARGE,
    LIMIT_EXCEEDED,
    READ_FAILED
}

data class PendingAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val category: AttachmentCategory,
    val localFilePath: String,
    val isEphemeral: Boolean
)

data class PersistedAttachmentMeta(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val category: AttachmentCategory,
    val localFilePath: String
)

sealed interface AttachmentImportResult {
    data class Success(val attachment: PendingAttachment) : AttachmentImportResult
    data class Error(val reason: AttachmentImportError, val detail: String = "") : AttachmentImportResult
}

object PlaygroundAttachmentManager {
    const val MAX_ATTACHMENTS = 5
    const val MAX_FILE_BYTES = 5L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 12L * 1024L * 1024L

    val IMAGE_MIME_TYPES = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif"
    )

    val TEXT_DOCUMENT_MIME_TYPES = setOf(
        "text/plain",
        "text/markdown",
        "text/csv",
        "application/json"
    )

    val DOCUMENT_MIME_TYPES = setOf("application/pdf") + TEXT_DOCUMENT_MIME_TYPES
    val ALLOWED_MIME_TYPES = IMAGE_MIME_TYPES + DOCUMENT_MIME_TYPES

    private const val ATTACHMENTS_DIR = "playground_attachments"
    private const val EPHEMERAL_DIR = "playground_ephemeral"
    private const val CAMERA_DIR = "camera_captures"

    fun getAttachmentsDir(context: Context): File = File(context.filesDir, ATTACHMENTS_DIR)

    fun getApprovedStorageRoots(context: Context): List<File> = listOf(
        File(context.filesDir, ATTACHMENTS_DIR),
        File(context.cacheDir, EPHEMERAL_DIR),
        File(context.cacheDir, CAMERA_DIR)
    )

    fun isSymbolicLinkOrAlias(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) {
                return true
            }
        }
        return runCatching {
            val canonParent = file.parentFile?.canonicalFile ?: return@runCatching false
            val resolvedInCanonParent = File(canonParent, file.name)
            resolvedInCanonParent.canonicalFile != resolvedInCanonParent.absoluteFile
        }.getOrDefault(true)
    }

    fun isChildWithinRoot(root: File, candidate: File): Boolean {
        return runCatching {
            val canonRoot = root.canonicalFile
            val canonCandidate = candidate.canonicalFile
            if (isSymbolicLinkOrAlias(candidate)) return@runCatching false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var current: File? = candidate.absoluteFile
                while (current != null && current != root.absoluteFile) {
                    if (Files.isSymbolicLink(current.toPath())) return@runCatching false
                    current = current.parentFile
                }
            }
            canonCandidate.path.startsWith(canonRoot.path + File.separator)
        }.getOrDefault(false)
    }

    fun isFileWithinApprovedRoots(context: Context, file: File): Boolean {
        return getApprovedStorageRoots(context).any { root -> isChildWithinRoot(root, file) }
    }

    fun extensionForMimeType(mimeType: String, displayName: String = ""): String = when (mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "application/pdf" -> "pdf"
        "text/markdown" -> "md"
        "text/csv" -> "csv"
        "application/json" -> "json"
        "text/plain" -> "txt"
        else -> {
            val rawExt = displayName.substringAfterLast('.', "").lowercase()
                .replace(Regex("[^a-z0-9]"), "")
            rawExt.takeIf { it.isNotEmpty() && it.length <= 8 } ?: "bin"
        }
    }

    fun createCameraCaptureTarget(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
        val file = File(dir, "cam_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    fun normalizeMimeType(rawMime: String?, displayName: String): String? {
        val cleaned = rawMime?.substringBefore(';')?.trim()?.lowercase()
        val mappedMime = when (cleaned) {
            "image/jpg" -> "image/jpeg"
            "text/x-markdown", "application/markdown" -> "text/markdown"
            "text/comma-separated-values", "application/csv" -> "text/csv"
            "text/json" -> "application/json"
            null, "", "application/octet-stream" -> null
            else -> cleaned
        }
        if (mappedMime != null && mappedMime in ALLOWED_MIME_TYPES) {
            return mappedMime
        }
        if (mappedMime != null && mappedMime !in ALLOWED_MIME_TYPES) {
            // Only fall back to extension when resolver reported generic/unknown text/binary types
            if (mappedMime != "application/binary" && !mappedMime.startsWith("application/x-")) {
                return null
            }
        }
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt", "log" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            else -> null
        }
    }

    fun importPickedUri(
        context: Context,
        uri: Uri,
        saveHistory: Boolean,
        existingCount: Int,
        existingTotalBytes: Long
    ): AttachmentImportResult {
        if (existingCount >= MAX_ATTACHMENTS) {
            return AttachmentImportResult.Error(AttachmentImportError.LIMIT_EXCEEDED)
        }
        val resolver = context.contentResolver
        var displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "attachment"
        var declaredSize = -1L

        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) declaredSize = c.getLong(sizeIdx)
                }
            }
        }

        val mime = normalizeMimeType(runCatching { resolver.getType(uri) }.getOrNull(), displayName)
            ?: return AttachmentImportResult.Error(AttachmentImportError.UNSUPPORTED_TYPE, displayName)

        if (declaredSize > MAX_FILE_BYTES) {
            return AttachmentImportResult.Error(AttachmentImportError.FILE_TOO_LARGE, displayName)
        }
        if (declaredSize > 0 && existingTotalBytes + declaredSize > MAX_TOTAL_BYTES) {
            return AttachmentImportResult.Error(AttachmentImportError.LIMIT_EXCEEDED, displayName)
        }

        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        return AttachmentImportResult.Error(AttachmentImportError.FILE_TOO_LARGE, displayName)
                    }
                    if (existingTotalBytes + total > MAX_TOTAL_BYTES) {
                        return AttachmentImportResult.Error(AttachmentImportError.LIMIT_EXCEEDED, displayName)
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            } ?: return AttachmentImportResult.Error(AttachmentImportError.READ_FAILED, displayName)
        } catch (_: Exception) {
            return AttachmentImportResult.Error(AttachmentImportError.READ_FAILED, displayName)
        }

        if (bytes.isEmpty()) {
            return AttachmentImportResult.Error(AttachmentImportError.READ_FAILED, displayName)
        }

        return writeBytesToOwnedStorage(
            context = context,
            bytes = bytes,
            displayName = displayName,
            mimeType = mime,
            saveHistory = saveHistory
        )
    }

    fun importCameraFile(
        context: Context,
        sourceFile: File,
        saveHistory: Boolean,
        existingCount: Int,
        existingTotalBytes: Long
    ): AttachmentImportResult {
        if (existingCount >= MAX_ATTACHMENTS) {
            runCatching { sourceFile.delete() }
            return AttachmentImportResult.Error(AttachmentImportError.LIMIT_EXCEEDED)
        }
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            runCatching { sourceFile.delete() }
            return AttachmentImportResult.Error(AttachmentImportError.READ_FAILED)
        }
        if (sourceFile.length() > MAX_FILE_BYTES) {
            runCatching { sourceFile.delete() }
            return AttachmentImportResult.Error(AttachmentImportError.FILE_TOO_LARGE)
        }
        if (existingTotalBytes + sourceFile.length() > MAX_TOTAL_BYTES) {
            runCatching { sourceFile.delete() }
            return AttachmentImportResult.Error(AttachmentImportError.LIMIT_EXCEEDED)
        }
        val bytes = runCatching { sourceFile.readBytes() }.getOrElse {
            runCatching { sourceFile.delete() }
            return AttachmentImportResult.Error(AttachmentImportError.READ_FAILED)
        }
        runCatching { sourceFile.delete() }
        if (bytes.isEmpty()) {
            return AttachmentImportResult.Error(AttachmentImportError.READ_FAILED)
        }
        return writeBytesToOwnedStorage(
            context = context,
            bytes = bytes,
            displayName = "camera_${System.currentTimeMillis() % 100000}.jpg",
            mimeType = "image/jpeg",
            saveHistory = saveHistory
        )
    }

    private fun writeBytesToOwnedStorage(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        saveHistory: Boolean
    ): AttachmentImportResult {
        val category = if (mimeType in IMAGE_MIME_TYPES) AttachmentCategory.IMAGE else AttachmentCategory.DOCUMENT
        val ext = when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "application/pdf" -> "pdf"
            "text/markdown" -> "md"
            "text/csv" -> "csv"
            "application/json" -> "json"
            else -> "txt"
        }
        val id = UUID.randomUUID().toString()
        val targetDir = if (saveHistory) {
            File(context.filesDir, ATTACHMENTS_DIR)
        } else {
            File(context.cacheDir, EPHEMERAL_DIR)
        }.apply { mkdirs() }
        val targetFile = File(targetDir, "$id.$ext")
        return try {
            targetFile.writeBytes(bytes)
            AttachmentImportResult.Success(
                PendingAttachment(
                    id = id,
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = bytes.size.toLong(),
                    category = category,
                    localFilePath = targetFile.absolutePath,
                    isEphemeral = !saveHistory
                )
            )
        } catch (_: Exception) {
            runCatching { targetFile.delete() }
            AttachmentImportResult.Error(AttachmentImportError.READ_FAILED, displayName)
        }
    }

    fun buildContentParts(prompt: String, attachments: List<PendingAttachment>): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        if (prompt.isNotBlank()) {
            parts += ContentPart.Text(prompt)
        }
        for (att in attachments) {
            val file = File(att.localFilePath)
            if (!file.exists()) {
                throw IllegalStateException("Attachment file missing: ${att.displayName}")
            }
            val bytes = file.readBytes()
            if (att.category == AttachmentCategory.IMAGE) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                parts += ContentPart.ImageUrl(url = "data:${att.mimeType};base64,$b64")
            } else if (att.mimeType == "application/pdf") {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                parts += ContentPart.FileAttachment(
                    fileName = att.displayName,
                    mimeType = att.mimeType,
                    base64Data = b64,
                    textContent = null,
                    sizeBytes = bytes.size.toLong()
                )
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                parts += ContentPart.FileAttachment(
                    fileName = att.displayName,
                    mimeType = att.mimeType,
                    base64Data = null,
                    textContent = text,
                    sizeBytes = bytes.size.toLong()
                )
            }
        }
        return parts
    }

    fun toMetadataJson(attachments: List<PendingAttachment>): String {
        if (attachments.isEmpty()) return "[]"
        val arr = JSONArray()
        attachments.forEach { att ->
            arr.put(
                JSONObject()
                    .put("id", att.id)
                    .put("displayName", att.displayName)
                    .put("mimeType", att.mimeType)
                    .put("sizeBytes", att.sizeBytes)
                    .put("category", att.category.name)
                    .put("localFilePath", att.localFilePath)
            )
        }
        return arr.toString()
    }

    fun parseMetadataJson(json: String?): List<PersistedAttachmentMeta> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val catName = obj.optString("category", AttachmentCategory.DOCUMENT.name)
                val cat = AttachmentCategory.entries.firstOrNull { it.name == catName } ?: AttachmentCategory.DOCUMENT
                PersistedAttachmentMeta(
                    id = obj.optString("id"),
                    displayName = obj.optString("displayName", "file"),
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                    category = cat,
                    localFilePath = obj.optString("localFilePath", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun deleteFileIfOwned(context: Context, localPath: String) {
        if (localPath.isBlank()) return
        runCatching {
            val target = File(localPath)
            if (!isFileWithinApprovedRoots(context, target)) return
            val f = target.canonicalFile
            if (f.exists() && f.isFile) {
                f.delete()
            }
        }
    }

    fun deleteAttachmentsFromMetadata(context: Context, metadataJsonList: List<String>) {
        metadataJsonList.forEach { raw ->
            parseMetadataJson(raw).forEach { meta ->
                deleteFileIfOwned(context, meta.localFilePath)
            }
        }
    }

    fun cleanupAllOwnedAttachments(context: Context) {
        listOf(
            File(context.filesDir, ATTACHMENTS_DIR),
            File(context.cacheDir, EPHEMERAL_DIR),
            File(context.cacheDir, CAMERA_DIR)
        ).forEach { dir ->
            runCatching {
                if (dir.exists()) dir.deleteRecursively()
            }
        }
    }

    fun cleanupEphemeralCache(context: Context) {
        listOf(
            File(context.cacheDir, EPHEMERAL_DIR),
            File(context.cacheDir, CAMERA_DIR)
        ).forEach { dir ->
            runCatching {
                if (dir.exists()) dir.deleteRecursively()
            }
        }
    }

    fun formatReadableSize(bytes: Long): String = when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "${(bytes / 1024.0).let { if (it < 10) String.format("%.1f", it) else it.toInt().toString() }}KB"
        else -> "${String.format("%.1f", bytes / (1024.0 * 1024.0))}MB"
    }
}
