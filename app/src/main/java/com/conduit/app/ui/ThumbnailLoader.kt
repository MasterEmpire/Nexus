package com.conduit.nexus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

object ThumbnailLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceIn(16 * 1024, 64 * 1024)

    private val memoryCache = object : LruCache<String, ImageBitmap>(cacheSize) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width * value.height * 4) / 1024
        }
    }

    fun getCacheKey(file: FileItem): String = "${file.path}_${file.lastModified}_${file.size}"

    fun getFromCache(key: String): ImageBitmap? = memoryCache.get(key)

    fun loadThumbnail(context: Context, file: FileItem, targetSize: Int = 144): ImageBitmap? {
        val key = getCacheKey(file)
        memoryCache.get(key)?.let { return it }

        val ext = file.name.substringAfterLast('.', "").lowercase()
        val isApk = ext == "apk"
        val isImg = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp").contains(ext)
        val isVid = listOf("mp4", "mkv", "avi", "mov", "webm").contains(ext)

        val rawBitmap: Bitmap? = try {
            when {
                isApk -> {
                    val pm = context.packageManager
                    pm.getPackageArchiveInfo(file.path, 0)?.let { pi ->
                        pi.applicationInfo.sourceDir = file.path
                        pi.applicationInfo.publicSourceDir = file.path
                        val drawable = pi.applicationInfo.loadIcon(pm)
                        if (drawable is BitmapDrawable && drawable.bitmap != null) {
                            drawable.bitmap
                        } else {
                            val bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            drawable.setBounds(0, 0, targetSize, targetSize)
                            drawable.draw(canvas)
                            bmp
                        }
                    }
                }
                isImg -> {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(file.path, options)

                    var inSampleSize = 1
                    val height = options.outHeight
                    val width = options.outWidth
                    if (height > targetSize || width > targetSize) {
                        val halfHeight = height / 2
                        val halfWidth = width / 2
                        while ((halfHeight / inSampleSize) >= targetSize && (halfWidth / inSampleSize) >= targetSize) {
                            inSampleSize *= 2
                        }
                    }

                    options.inSampleSize = inSampleSize
                    options.inJustDecodeBounds = false
                    options.inPreferredConfig = Bitmap.Config.RGB_565
                    BitmapFactory.decodeFile(file.path, options)
                }
                isVid -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            context.contentResolver.loadThumbnail(
                                Uri.fromFile(File(file.path)),
                                Size(targetSize, targetSize),
                                null
                            )
                        } catch (_: Exception) {
                            ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
                        }
                    } else {
                        ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        if (rawBitmap != null) {
            val finalBitmap = if (rawBitmap.width != targetSize || rawBitmap.height != targetSize) {
                try {
                    Bitmap.createScaledBitmap(rawBitmap, targetSize, targetSize, true)
                } catch (_: Throwable) {
                    rawBitmap
                }
            } else {
                rawBitmap
            }
            val imageBitmap = finalBitmap.asImageBitmap()
            memoryCache.put(key, imageBitmap)
            return imageBitmap
        }

        return null
    }
}