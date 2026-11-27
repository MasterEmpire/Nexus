package com.conduit.nexus

import java.io.File
import java.util.zip.ZipFile
import android.content.Context
import android.provider.MediaStore
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color

object FileScanner {
    private val imageExts = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic")
    private val videoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
    private val audioExts = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")
    private val archiveExts = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val codeExts = setOf(
        "py", "pyw", "js", "mjs", "cjs", "kt", "kts", "java", "json", "xml", "html", "htm",
        "css", "scss", "ts", "tsx", "jsx", "sql", "sh", "bash", "zsh", "bat", "cmd", "ps1",
        "gradle", "yml", "yaml", "toml", "c", "cpp", "cc", "h", "hpp", "rs", "go", "rb",
        "php", "dart", "swift", "lua"
    )
    private val officeExts = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "rtf")
    private val textExts = setOf(
        "txt", "md", "markdown", "csv", "tsv", "log", "env", "ini", "cfg", "conf", "properties",
        "rst", "tex", "srt", "vtt", "sub", "ass", "lock", "gitignore", "gitattributes",
        "editorconfig", "manifest", "reg", "inf", "svg"
    )
    private val knownTextNames = setOf(
        "readme", "license", "makefile", "dockerfile", "procfile",
        "gemfile", "rakefile", "changelog", "authors", "notice", "hosts"
    )

    fun resolveIconAndColor(
        name: String,
        isDirectory: Boolean = false,
        mime: String? = null,
        isVirtualFolder: Boolean = false
    ): Pair<ImageVector, Color> {
        if (isVirtualFolder) return Icons.Default.FolderZip to Color(0xFF7C4DFF)
        if (isDirectory) return Icons.Default.Folder to Color(0xFF00E5FF)

        val lower = name.lowercase()
        val ext = if (lower.contains(".")) lower.substringAfterLast('.') else ""
        val mimeLower = mime?.lowercase() ?: ""

        return when {
            ext in imageExts || mimeLower.startsWith("image/") ->
                Icons.Default.Image to Color(0xFFFF5252)
            ext in videoExts || mimeLower.startsWith("video/") ->
                Icons.Default.Videocam to Color(0xFF7C4DFF)
            ext in audioExts || mimeLower.startsWith("audio/") ->
                Icons.Default.MusicNote to Color(0xFF00B0FF)
            ext in archiveExts ->
                Icons.Default.Archive to Color(0xFFFF4081)
            ext == "pdf" || mimeLower == "application/pdf" ->
                Icons.Default.PictureAsPdf to Color(0xFFFF5252)
            ext == "apk" ->
                Icons.Default.Android to Color(0xFF69F0AE)
            ext in codeExts ->
                Icons.Default.Code to Color(0xFFFFD740)
            ext in officeExts ->
                Icons.Default.Description to Color(0xFF448AFF)
            ext in textExts || lower in knownTextNames ->
                Icons.Default.Article to Color(0xFF26C6DA)
            else ->
                Icons.Default.InsertDriveFile to Color(0xFF78909C)
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun formatTimestamp(epochMillis: Long): String {
        if (epochMillis <= 0L) return ""
        val now = System.currentTimeMillis()
        val diff = now - epochMillis
        val isWithin24Hours = diff >= -60_000L && diff < 24 * 60 * 60 * 1000L
        return if (isWithin24Hours) {
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(epochMillis))
        } else {
            val nowCal = java.util.Calendar.getInstance()
            val thenCal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
            val pattern = if (nowCal.get(java.util.Calendar.YEAR) == thenCal.get(java.util.Calendar.YEAR)) {
                "MMM d"
            } else {
                "MMM d, yyyy"
            }
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(epochMillis))
        }
    }

    fun getMediaFiles(context: Context, type: String): List<FileItem> {
        val files = mutableListOf<FileItem>()
        val uri = when (type) {
            "Images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "Videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "Audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> null
        } ?: return emptyList()

        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        context.contentResolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

            var count = 0
            while (cursor.moveToNext() && count < 80) {
                val path = cursor.getString(pathCol) ?: ""
                val f = File(path)
                if (!f.exists() || f.isDirectory) continue

                val name = cursor.getString(nameCol) ?: f.name
                val sizeBytes = cursor.getLong(sizeCol).let { if (it > 0) it else f.length() }
                val sizeStr = formatSize(sizeBytes)
                val dateSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                val dateMillis = if (dateSec > 0) dateSec * 1000L else f.lastModified()
                val timeStr = formatTimestamp(dateMillis)
                val parentFolder = path.split("/").takeLast(2).firstOrNull() ?: ""
                val metaParts = listOf(sizeStr, timeStr, parentFolder).filter { it.isNotBlank() }
                val (icon, color) = resolveIconAndColor(name, isDirectory = false)

                files.add(
                    FileItem(
                        name = name,
                        meta = metaParts.joinToString(" • "),
                        icon = icon,
                        color = color,
                        path = path,
                        lastModified = dateMillis,
                        size = sizeBytes
                    )
                )
                count++
            }
        }
        return files
    }

    fun getDirectoryContents(
        path: String,
        sortOrder: SortOrder = SortOrder.NAME_ASC,
        cache: MutableMap<String, Pair<Long, Int>>? = null
    ): List<FileItem> {
        val directory = File(path)
        val items = mutableListOf<FileItem>()
        try {
            if (directory.exists() && directory.isDirectory) {
                val files = directory.listFiles()?.filter { !it.name.startsWith(".") }
                files?.forEach { file ->
                    val isDir = file.isDirectory
                    val (fileIcon, fileColor) = resolveIconAndColor(file.name, isDirectory = isDir)

                    val lastMod = file.lastModified()
                    val timeStr = formatTimestamp(lastMod)
                    val metaText = if (isDir) {
                        val cached = cache?.get(file.absolutePath)
                        val count = if (cached != null && cached.first == lastMod) {
                            cached.second
                        } else {
                            val freshCount = file.listFiles()?.filter { !it.name.startsWith(".") }?.size ?: 0
                            cache?.put(file.absolutePath, lastMod to freshCount)
                            freshCount
                        }
                        val countText = "$count ${if (count == 1) "item" else "items"}"
                        if (timeStr.isNotBlank()) "$countText • $timeStr" else countText
                    } else {
                        val sizeStr = formatSize(file.length())
                        if (timeStr.isNotBlank()) "$sizeStr • $timeStr" else sizeStr
                    }

                    items.add(
                        FileItem(
                            name = file.name,
                            meta = metaText,
                            icon = fileIcon,
                            color = fileColor,
                            path = file.absolutePath,
                            lastModified = lastMod,
                            size = file.length()
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        val comparator = Comparator<FileItem> { a, b ->
            val aIsDir = a.icon == Icons.Default.Folder
            val bIsDir = b.icon == Icons.Default.Folder
            if (aIsDir != bIsDir) {
                return@Comparator if (aIsDir) -1 else 1
            }
            when (sortOrder) {
                SortOrder.NAME_ASC -> a.name.lowercase().compareTo(b.name.lowercase())
                SortOrder.NAME_DESC -> b.name.lowercase().compareTo(a.name.lowercase())
                SortOrder.DATE_NEW -> b.lastModified.compareTo(a.lastModified)
                SortOrder.DATE_OLD -> a.lastModified.compareTo(b.lastModified)
                SortOrder.SIZE_LARGE -> b.size.compareTo(a.size)
                SortOrder.SIZE_SMALL -> a.size.compareTo(b.size)
            }
        }
        return items.sortedWith(comparator)
    }

    fun getRecentFiles(context: Context): List<FileItem> {
        val files = mutableListOf<FileItem>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"

        context.contentResolver.query(
            uri,
            projection,
            selection,
            null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

            var count = 0
            while (cursor.moveToNext() && count < 50) {
                val path = cursor.getString(pathCol) ?: ""
                if (path.isEmpty() || path.split("/").any { it.startsWith(".") }) continue
                val f = File(path)
                if (!f.exists() || f.isDirectory) continue

                val name = cursor.getString(nameCol) ?: f.name
                val mime = cursor.getString(mimeCol) ?: ""
                val sizeBytes = cursor.getLong(sizeCol).let { if (it > 0) it else f.length() }
                val sizeStr = formatSize(sizeBytes)
                val dateSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                val dateMillis = if (dateSec > 0) dateSec * 1000L else f.lastModified()
                val timeStr = formatTimestamp(dateMillis)
                val parentFolder = path.split("/").takeLast(2).firstOrNull() ?: ""
                val metaParts = listOf(sizeStr, timeStr, parentFolder).filter { it.isNotBlank() }
                val (icon, color) = resolveIconAndColor(name, isDirectory = false, mime = mime)

                files.add(
                    FileItem(
                        name = name,
                        meta = metaParts.joinToString(" • "),
                        icon = icon,
                        color = color,
                        path = path,
                        lastModified = dateMillis,
                        size = sizeBytes
                    )
                )
                count++
            }
        }
        return files
    }

    fun getZipContents(rawPath: String, internalPath: String): List<FileItem> {
        val dirMap = mutableMapOf<String, FileItem>()
        val fileList = mutableListOf<FileItem>()
        try {
            val zipFilePath = if (rawPath.contains("!/")) rawPath.substringBefore("!/") else rawPath
            ZipFile(File(zipFilePath)).use { zip ->
                val entries = zip.entries()
                val cleanInternal = if (internalPath.endsWith("/")) internalPath else "$internalPath/"
                val searchPath = if (internalPath.isEmpty()) "" else cleanInternal

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    var name = entry.name.replace("\\", "/")
                    while (name.contains("//")) name = name.replace("//", "/")
                    while (name.startsWith("/")) name = name.substring(1)
                    while (name.startsWith("./")) name = name.substring(2)

                    if (name.startsWith(searchPath) && name != searchPath) {
                        val relative = name.substring(searchPath.length)
                        val parts = relative.split("/")
                        val itemName = parts[0]
                        if (itemName.isEmpty()) continue
                        val isDir = parts.size > 1 || entry.isDirectory

                        val itemPath = "$zipFilePath!/$searchPath$itemName${if (isDir) "/" else ""}"

                        if (isDir) {
                            if (!dirMap.containsKey(itemName)) {
                                val (dirIcon, dirColor) = resolveIconAndColor(itemName, isDirectory = true, isVirtualFolder = true)
                                dirMap[itemName] = FileItem(
                                    name = itemName,
                                    meta = "Virtual Folder",
                                    icon = dirIcon,
                                    color = dirColor,
                                    path = itemPath,
                                    lastModified = 0L,
                                    size = 0L
                                )
                            }
                        } else {
                            val sizeStr = formatSize(entry.size)
                            val timeStr = formatTimestamp(entry.time)
                            val zipMeta = if (timeStr.isNotBlank()) "$sizeStr • $timeStr" else sizeStr
                            val (fileIcon, fileColor) = resolveIconAndColor(itemName, isDirectory = false)

                            fileList.add(
                                FileItem(
                                    name = itemName,
                                    meta = zipMeta,
                                    icon = fileIcon,
                                    color = fileColor,
                                    path = itemPath,
                                    lastModified = entry.time,
                                    size = entry.size
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return dirMap.values.toList().sortedBy { it.name.lowercase() } + fileList.sortedBy { it.name.lowercase() }
    }

    fun searchFilesNative(context: Context, query: String, searchPath: String? = null): List<FileItem> {
        val files = mutableListOf<FileItem>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val selection = if (searchPath != null) {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.Files.FileColumns.DATA} LIKE ?"
        } else {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        }

        val selectionArgs = if (searchPath != null) {
            arrayOf("%$query%", "$searchPath/%")
        } else {
            arrayOf("%$query%")
        }

        context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

            var count = 0
            while (cursor.moveToNext() && count < 100) {
                val path = cursor.getString(pathCol) ?: ""
                if (path.isEmpty() || path.split("/").any { it.startsWith(".") }) continue
                val f = File(path)
                if (!f.exists()) continue

                val isDir = f.isDirectory
                val name = cursor.getString(nameCol) ?: f.name
                val sizeBytes = if (isDir) 0L else cursor.getLong(sizeCol).let { if (it > 0) it else f.length() }
                val sizeStr = if (isDir) "Folder" else formatSize(sizeBytes)
                val dateSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                val dateMillis = if (dateSec > 0) dateSec * 1000L else f.lastModified()
                val timeStr = formatTimestamp(dateMillis)
                val parentFolder = path.split("/").takeLast(2).firstOrNull() ?: ""
                val metaParts = listOf(sizeStr, timeStr, parentFolder).filter { it.isNotBlank() }
                val mime = cursor.getString(mimeCol) ?: ""
                val (icon, color) = resolveIconAndColor(name, isDirectory = isDir, mime = mime)

                files.add(
                    FileItem(
                        name = name,
                        meta = metaParts.joinToString(" • "),
                        icon = icon,
                        color = color,
                        path = path,
                        lastModified = dateMillis,
                        size = sizeBytes
                    )
                )
                count++
            }
        }
        return files
    }
}