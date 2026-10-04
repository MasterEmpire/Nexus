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
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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
            MediaStore.MediaColumns.DATA
        )

        context.contentResolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)

            var count = 0
            while (cursor.moveToNext() && count < 50) { // Limit for performance
                val sizeBytes = cursor.getLong(sizeCol)
                val sizeStr = formatSize(sizeBytes)
                                    val path = cursor.getString(pathCol) ?: ""
                    files.add(FileItem(
                        name = cursor.getString(nameCol) ?: "Unknown",
                        meta = "$sizeStr • ${path.split("/").takeLast(2).firstOrNull() ?: ""}",
                        icon = when(type) {
                            "Images" -> Icons.Default.Image
                            "Videos" -> Icons.Default.Videocam
                            else -> Icons.Default.MusicNote
                        },
                        color = when(type) {
                            "Images" -> Color(0xFFFF5252)
                            "Videos" -> Color(0xFF7C4DFF)
                            else -> Color(0xFF00E5FF)
                        },
                        path = path,
                        lastModified = 0L,
                        size = sizeBytes
                    ))
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
                    val ext = if (isDir) "" else file.extension.lowercase()
                    
                    var isImg = false; var isVid = false; var isAud = false
                    var isArc = false; var isCode = false; var isText = false
                    
                    if (!isDir) {
                        when (ext) {
                            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> isImg = true
                            "mp4", "mkv", "avi", "mov", "webm" -> isVid = true
                            "mp3", "wav", "ogg", "m4a", "flac" -> isAud = true
                            "zip", "rar", "7z", "tar", "gz" -> isArc = true
                            "py", "js", "kt", "java", "json", "xml", "html", "css", "gradle", "sh" -> isCode = true
                            "txt", "md", "csv", "doc", "docx" -> isText = true
                        }
                    }

                    val fileIcon = when {
                        isDir -> Icons.Default.Folder
                        isImg -> Icons.Default.Image
                        isVid -> Icons.Default.Videocam
                        isAud -> Icons.Default.MusicNote
                        isArc -> Icons.Default.Archive
                        ext == "pdf" -> Icons.Default.PictureAsPdf
                        ext == "apk" -> Icons.Default.Android
                        isCode -> Icons.Default.Code
                        isText -> Icons.Default.Article
                        else -> Icons.Default.Description
                    }

                    val fileColor = when {
                        isDir -> Color(0xFF00E5FF)
                        isImg -> Color(0xFFFF5252)
                        isVid -> Color(0xFF7C4DFF)
                        isAud -> Color(0xFF00B0FF)
                        isArc -> Color(0xFFFF4081)
                        ext == "pdf" -> Color(0xFFFF5252)
                        ext == "apk" -> Color(0xFF69F0AE)
                        isCode -> Color(0xFFFFD740)
                        else -> Color.Gray
                    }

                    val metaText = if (isDir) {
                        val lastMod = file.lastModified()
                        val cached = cache?.get(file.absolutePath)
                        val count = if (cached != null && cached.first == lastMod) {
                            cached.second
                        } else {
                            val freshCount = file.listFiles()?.filter { !it.name.startsWith(".") }?.size ?: 0
                            cache?.put(file.absolutePath, lastMod to freshCount)
                            freshCount
                        }
                        "$count ${if (count == 1) "item" else "items"}"
                    } else {
                        formatSize(file.length())
                    }
                    
                    items.add(FileItem(
                        name = file.name,
                        meta = metaText,
                        icon = fileIcon,
                        color = fileColor,
                        path = file.absolutePath,
                        lastModified = file.lastModified(),
                        size = file.length()
                    ))
                }
            }
        } catch (e: Exception) { }
        
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
            MediaStore.Files.FileColumns.MIME_TYPE
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

            var count = 0
            while (cursor.moveToNext() && count < 50) {
                val path = cursor.getString(pathCol) ?: ""
                if (File(path).isDirectory) continue
                
                val mime = cursor.getString(mimeCol) ?: ""
                val sizeStr = formatSize(cursor.getLong(sizeCol))
                
                val ext = path.substringAfterLast('.', "").lowercase()
                val isImg = mime.contains("image") || listOf("png", "jpg", "jpeg", "gif", "webp", "bmp").contains(ext)
                val isVid = mime.contains("video") || listOf("mp4", "mkv", "avi", "mov", "webm").contains(ext)
                val isAud = mime.contains("audio") || listOf("mp3", "wav", "ogg", "m4a", "flac").contains(ext)
                val isArc = listOf("zip", "rar", "7z", "tar", "gz").contains(ext)
                val isCode = listOf("py", "js", "kt", "java", "json", "xml", "html", "css", "gradle", "sh").contains(ext)
                val isText = listOf("txt", "md", "csv", "doc", "docx").contains(ext)

                files.add(FileItem(
                    name = cursor.getString(nameCol) ?: "Unknown",
                    meta = "$sizeStr • ${path.split("/").takeLast(2).firstOrNull() ?: ""}",
                    icon = when {
                        isImg -> Icons.Default.Image
                        isVid -> Icons.Default.Videocam
                        isAud -> Icons.Default.MusicNote
                        isArc -> Icons.Default.Archive
                        ext == "pdf" -> Icons.Default.PictureAsPdf
                        ext == "apk" -> Icons.Default.Android
                        isCode -> Icons.Default.Code
                        isText -> Icons.Default.Article
                        else -> Icons.Default.Description
                    },
                    color = when {
                        isImg -> Color(0xFFFF5252)
                        isVid -> Color(0xFF7C4DFF)
                        isAud -> Color(0xFF00B0FF)
                        isArc -> Color(0xFFFF4081)
                        ext == "pdf" -> Color(0xFFFF5252)
                        ext == "apk" -> Color(0xFF69F0AE)
                        isCode -> Color(0xFFFFD740)
                        else -> Color(0xFF00E5FF)
                    },
                    path = path
                ))
                count++
            }
        }
        return files
    }

    fun getZipContents(rawPath: String, internalPath: String): List<FileItem> {
        val dirMap = mutableMapOf<String, FileItem>()
        val fileList = mutableListOf<FileItem>()
        try {
            // Clean the path: if it's a nested zip path like 'a.zip!/b.zip', we need the actual file part
            val zipFilePath = if (rawPath.contains("!/")) rawPath.substringBefore("!/") else rawPath
            ZipFile(File(zipFilePath)).use { zip ->
                val entries = zip.entries()
                val cleanInternal = if (internalPath.endsWith("/")) internalPath else "$internalPath/"
                val searchPath = if (internalPath.isEmpty()) "" else cleanInternal

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    
                    // Normalize separators (fixes Windows zips, Mac ./ prefixes, and consecutive slashes)
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
                                dirMap[itemName] = FileItem(
                                    name = itemName,
                                    meta = "Virtual Folder",
                                    icon = Icons.Default.FolderZip,
                                    color = Color(0xFF7C4DFF),
                                    path = itemPath,
                                    lastModified = 0L,
                                    size = 0L
                                )
                            }
                        } else {
                            val ext = itemName.substringAfterLast('.', "").lowercase()
                            val isImg = listOf("png", "jpg", "jpeg", "gif", "webp").contains(ext)
                            val isVid = listOf("mp4", "mkv", "avi").contains(ext)
                            val isAud = listOf("mp3", "wav", "m4a", "ogg").contains(ext)
                            
                            fileList.add(FileItem(
                                name = itemName,
                                meta = formatSize(entry.size),
                                icon = when {
                                    isImg -> Icons.Default.Image
                                    isVid -> Icons.Default.Videocam
                                    isAud -> Icons.Default.MusicNote
                                    ext == "pdf" -> Icons.Default.PictureAsPdf
                                    ext == "zip" -> Icons.Default.Archive
                                    else -> Icons.Default.Description
                                },
                                color = when {
                                    isImg -> Color(0xFFFF5252)
                                    isVid -> Color(0xFF7C4DFF)
                                    isAud -> Color(0xFF00E5FF)
                                    ext == "zip" -> Color(0xFFFF4081)
                                    else -> Color.Gray
                                },
                                path = itemPath,
                                lastModified = entry.time,
                                size = entry.size
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return (dirMap.values.toList().sortedBy { it.name.lowercase() } + fileList.sortedBy { it.name.lowercase() })
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

            var count = 0
            while (cursor.moveToNext() && count < 100) {
                val path = cursor.getString(pathCol) ?: ""
                if (path.split("/").any { it.startsWith(".") }) continue

                val name = cursor.getString(nameCol) ?: "Unknown"
                val sizeStr = formatSize(cursor.getLong(sizeCol))
                val mime = cursor.getString(mimeCol) ?: ""
                
                files.add(FileItem(
                    name = name,
                    meta = "$sizeStr • ${path.split("/").takeLast(2).firstOrNull() ?: ""}",
                    icon = when {
                        mime.contains("image") -> Icons.Default.Image
                        mime.contains("video") -> Icons.Default.Videocam
                        mime.contains("audio") -> Icons.Default.MusicNote
                        path.endsWith(".pdf") -> Icons.Default.PictureAsPdf
                        else -> Icons.Default.Description
                    },
                    color = when {
                        mime.contains("image") -> Color(0xFFFF5252)
                        mime.contains("video") -> Color(0xFF7C4DFF)
                        else -> Color(0xFF00E5FF)
                    },
                    path = path
                ))
                count++
            }
        }
        return files
    }
}