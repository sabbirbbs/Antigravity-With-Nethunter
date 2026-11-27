package id.vern.antigravity.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import id.vern.antigravity.R
import java.io.File
import java.net.URLDecoder
import java.util.Locale

object ExternalFileOpener {

    private val MIME_OVERRIDES: Map<String, String> = mapOf(
        // Spreadsheets
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls" to "application/vnd.ms-excel",
        "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
        "xlsb" to "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "csv" to "text/csv",
        // Documents & Presentations
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc" to "application/msword",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppt" to "application/vnd.ms-powerpoint",
        "odt" to "application/vnd.oasis.opendocument.text",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
        "epub" to "application/epub+zip",
        // Images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "ico" to "image/x-icon",
        // Videos
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp",
        // Audio
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "aac" to "audio/aac",
        // Archives & Packages
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
        // Text & Web
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/plain",
        "markdown" to "text/plain",
        "html" to "text/html",
        "htm" to "text/html",
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "text/plain",
        "yml" to "text/plain"
    )

    private val EXTERNAL_BINARY_EXTENSIONS: Set<String> = setOf(
        "xlsx", "xls", "xlsm", "xlsb", "ods",
        "docx", "doc", "pptx", "ppt", "odt", "odp", "pdf", "epub", "rtf",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "ico",
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp",
        "mp3", "wav", "ogg", "m4a", "flac", "aac",
        "zip", "rar", "7z", "apk"
    )

    fun isExternalBinaryFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in EXTERNAL_BINARY_EXTENSIONS
    }

    fun resolveMimeType(fileName: String, fallbackMime: String? = null): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isNotEmpty()) {
            MIME_OVERRIDES[ext]?.let { return it }
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let {
                if (it.isNotBlank()) return it
            }
        }
        if (!fallbackMime.isNullOrBlank() && fallbackMime != "application/octet-stream") {
            return fallbackMime
        }
        return "*/*"
    }

    fun openUrlOrFile(context: Context, rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false

        // Handle local file:// URIs or absolute filesystem paths
        if (trimmed.startsWith("file://", ignoreCase = true) || trimmed.startsWith("/")) {
            val file = resolveLocalFile(trimmed)
            if (file != null && file.exists()) {
                return openFile(context, file)
            }
            Toast.makeText(context, context.getString(R.string.file_opener_not_found, file?.name ?: trimmed), Toast.LENGTH_SHORT).show()
            return false
        }

        // Handle content:// URIs
        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val uri = Uri.parse(trimmed)
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            return launchViewIntent(context, uri, mime, uri.lastPathSegment ?: "File")
        }

        // Handle web or other scheme URLs (http, https, mailto, etc.)
        return try {
            val uri = Uri.parse(trimmed)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.file_opener_cannot_open, trimmed), Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun openAttachment(
        context: Context,
        localPath: String?,
        uri: Uri,
        mimeType: String?,
        fileName: String
    ): Boolean {
        if (!localPath.isNullOrBlank()) {
            val localFile = File(localPath)
            if (localFile.exists() && localFile.isFile) {
                return openFile(context, localFile, mimeType)
            }
        }
        val resolvedMime = resolveMimeType(fileName, mimeType ?: context.contentResolver.getType(uri))
        return launchViewIntent(context, uri, resolvedMime, fileName)
    }

    fun openFile(context: Context, file: File, fallbackMime: String? = null): Boolean {
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.file_opener_not_found, file.name), Toast.LENGTH_SHORT).show()
            return false
        }
        val targetFile = try {
            file.canonicalFile
        } catch (_: Exception) {
            file
        }
        val authority = "${context.packageName}.fileprovider"
        val contentUri = try {
            FileProvider.getUriForFile(context, authority, targetFile)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.file_opener_cannot_share, targetFile.name), Toast.LENGTH_SHORT).show()
            return false
        }

        val mimeType = resolveMimeType(targetFile.name, fallbackMime)
        return launchViewIntent(context, contentUri, mimeType, targetFile.name)
    }

    private fun launchViewIntent(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String
    ): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newRawUri(displayName, uri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    clipData = ClipData.newRawUri(displayName, uri)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                val chooser = Intent.createChooser(fallback, displayName).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                true
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.file_opener_no_app, displayName), Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.file_opener_cannot_open, displayName), Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun resolveLocalFile(raw: String): File? {
        val withoutFragment = raw.substringBefore('#').substringBefore('?')
        val path = if (withoutFragment.startsWith("file://", ignoreCase = true)) {
            val parsedPath = Uri.parse(withoutFragment).path
            if (!parsedPath.isNullOrBlank()) {
                parsedPath
            } else {
                val stripped = withoutFragment.removePrefix("file://").removePrefix("FILE://")
                try {
                    URLDecoder.decode(stripped, "UTF-8")
                } catch (_: Exception) {
                    stripped
                }
            }
        } else {
            try {
                URLDecoder.decode(withoutFragment, "UTF-8")
            } catch (_: Exception) {
                withoutFragment
            }
        }
        if (path.isBlank()) return null
        return File(path)
    }
}
