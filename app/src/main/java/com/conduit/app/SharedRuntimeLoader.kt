package com.conduit.nexus

import android.content.Context
import android.os.Environment
import android.util.Log
import dalvik.system.BaseDexClassLoader
import java.io.IOException
import java.lang.reflect.Field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Array
import java.util.concurrent.TimeUnit

object SharedRuntimeLoader {
    private const val TAG = "NexusRuntimeLoader"
    private const val SUPABASE_RUNTIME_URL =
        "https://srcynzxpodjfvcydqipt.supabase.co/storage/v1/object/public/omni-modules/extended_icons.dex"
    private const val SUPABASE_ANON_KEY =
        "sb_publishable_eU7Sck13kjRIe0yQ3WFuVg_N70_ckBA"
    private const val DEX_FILENAME = "extended_icons.dex"

    @Volatile
    var isLoaded: Boolean = false
        private set

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun getSharedCandidateFiles(): List<File> {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dls = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return listOf(
            File(File(docs, ".omni_runtime"), DEX_FILENAME),
            File(File(dls, "OmniDownloads"), DEX_FILENAME),
            File(dls, DEX_FILENAME)
        )
    }

    fun tryLoadExisting(context: Context): Boolean {
        if (isLoaded) return true

        val internalDex = File(context.codeCacheDir, DEX_FILENAME)
        if (internalDex.exists() && internalDex.length() > 0) {
            if (injectDex(context, internalDex)) {
                isLoaded = true
                Log.d(TAG, "✅ Loaded from internal code cache: ${internalDex.length()} bytes")
                return true
            }
        }

        for (sharedFile in getSharedCandidateFiles()) {
            if (sharedFile.exists() && sharedFile.isFile && sharedFile.length() > 0) {
                try {
                    val ok = copyToInternalAndInject(context, sharedFile)
                    if (ok) {
                        isLoaded = true
                        Log.d(TAG, "✅ Loaded from shared storage location (${sharedFile.absolutePath}): ${sharedFile.length()} bytes")
                        return true
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed loading from ${sharedFile.absolutePath}: ${t.message}")
                    CrashBootstrap.logHandledThrowable(context, TAG, t)
                }
            }
        }
        return false
    }

    suspend fun ensureLoaded(
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true
        if (tryLoadExisting(context)) return@withContext true

        onProgress?.invoke("Connecting to shared runtime...")
        Log.d(TAG, "⬇️ Downloading $DEX_FILENAME from Supabase storage...")

        val downloaded = downloadFromSupabase(context)
        if (downloaded != null && downloaded.exists() && downloaded.length() > 0) {
            try {
                val sharedDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ".omni_runtime")
                if (!sharedDir.exists()) sharedDir.mkdirs()
                val sharedDest = File(sharedDir, DEX_FILENAME)
                downloaded.copyTo(sharedDest, overwrite = true)
                Log.d(TAG, "💾 Mirrored $DEX_FILENAME to shared storage: ${sharedDest.absolutePath}")
            } catch (_: Exception) {}

            val ok = injectDex(context, downloaded)
            if (ok) {
                isLoaded = true
                Log.d(TAG, "✅ Successfully downloaded and injected $DEX_FILENAME")
                return@withContext true
            }
        }
        return@withContext false
    }

    private fun copyToInternalAndInject(context: Context, sourceFile: File): Boolean {
        val internalDex = File(context.codeCacheDir, DEX_FILENAME)
        if (internalDex.exists()) {
            internalDex.setWritable(true)
            internalDex.delete()
        }
        sourceFile.copyTo(internalDex, overwrite = true)
        return injectDex(context, internalDex)
    }

    private val injectLock = Any()

    private fun isDexAlreadyInPathList(appPathList: Any, dexElementsField: Field, dexFile: File): Boolean {
        return try {
            val elements = dexElementsField.get(appPathList) ?: return false
            val len = Array.getLength(elements)
            val targetName = dexFile.name
            for (i in 0 until len) {
                val elem = Array.get(elements, i) ?: continue
                if (elem.toString().contains(targetName)) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun makeElementsDirectly(
        appPathList: Any,
        appClassLoader: BaseDexClassLoader,
        dexFile: File,
        optDir: File
    ): Any? {
        val files = arrayListOf(dexFile)
        val suppressed = ArrayList<IOException>()
        val cls = appPathList.javaClass

        try {
            val m5 = cls.getDeclaredMethod(
                "makeDexElements",
                List::class.java,
                File::class.java,
                List::class.java,
                ClassLoader::class.java,
                java.lang.Boolean.TYPE
            ).apply { isAccessible = true }
            return m5.invoke(null, files, optDir, suppressed, appClassLoader, false)
        } catch (_: NoSuchMethodException) {}

        try {
            val m4 = cls.getDeclaredMethod(
                "makeDexElements",
                List::class.java,
                File::class.java,
                List::class.java,
                ClassLoader::class.java
            ).apply { isAccessible = true }
            return m4.invoke(null, files, optDir, suppressed, appClassLoader)
        } catch (_: NoSuchMethodException) {}

        try {
            val m4Alt = cls.getDeclaredMethod(
                "makeDexElements",
                ArrayList::class.java,
                File::class.java,
                ArrayList::class.java,
                ClassLoader::class.java
            ).apply { isAccessible = true }
            return m4Alt.invoke(null, files, optDir, suppressed, appClassLoader)
        } catch (_: NoSuchMethodException) {}

        val m3 = cls.getDeclaredMethod(
            "makeDexElements",
            ArrayList::class.java,
            File::class.java,
            ArrayList::class.java
        ).apply { isAccessible = true }
        return m3.invoke(null, files, optDir, suppressed)
    }

    private fun injectDex(context: Context, dexFile: File): Boolean {
        synchronized(injectLock) {
            if (isLoaded) return true
            return try {
                dexFile.setReadOnly()

                val appClassLoader = context.classLoader as? BaseDexClassLoader
                    ?: return false

                val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply {
                    isAccessible = true
                }
                val appPathList = pathListField.get(appClassLoader) ?: return false
                val dexElementsField = appPathList.javaClass.getDeclaredField("dexElements").apply {
                    isAccessible = true
                }

                if (isDexAlreadyInPathList(appPathList, dexElementsField, dexFile)) {
                    Log.d(TAG, "✅ ${dexFile.name} already registered on PathClassLoader.")
                    return true
                }

                try {
                    val addDexPath = BaseDexClassLoader::class.java.getDeclaredMethod(
                        "addDexPath",
                        String::class.java
                    ).apply { isAccessible = true }
                    addDexPath.invoke(appClassLoader, dexFile.absolutePath)
                    if (isDexAlreadyInPathList(appPathList, dexElementsField, dexFile)) {
                        Log.d(TAG, "🎉 Registered ${dexFile.name} directly on PathClassLoader via addDexPath!")
                        return true
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "addDexPath unavailable (${e.message}), trying makeDexElements...")
                }

                val optDir = File(context.codeCacheDir, "opt_dex").apply { mkdirs() }
                val newElements = makeElementsDirectly(appPathList, appClassLoader, dexFile, optDir)
                    ?: return false

                val origElements = dexElementsField.get(appPathList) ?: return false
                val origLen = Array.getLength(origElements)
                val newLen = Array.getLength(newElements)
                val componentType = origElements.javaClass.componentType ?: return false

                val combined = Array.newInstance(componentType, origLen + newLen)
                System.arraycopy(origElements, 0, combined, 0, origLen)
                System.arraycopy(newElements, 0, combined, origLen, newLen)

                dexElementsField.set(appPathList, combined)
                Log.d(TAG, "🎉 Successfully merged $newLen runtime dex elements directly into PathClassLoader!")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "❌ Failed injecting DEX: ${t.message}", t)
                CrashBootstrap.logHandledThrowable(context, TAG, t)
                false
            }
        }
    }

    private fun downloadFromSupabase(context: Context): File? {
        return try {
            val tempDest = File(context.codeCacheDir, DEX_FILENAME)
            if (tempDest.exists()) {
                tempDest.setWritable(true)
                tempDest.delete()
            }

            val req = Request.Builder()
                .url(SUPABASE_RUNTIME_URL)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(tempDest).use { output ->
                        input.copyTo(output)
                    }
                }
                tempDest
            } else {
                Log.e(TAG, "Failed downloading runtime pack: HTTP ${resp.code}")
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Download error: ${t.message}")
            CrashBootstrap.logHandledThrowable(context, TAG, t)
            null
        }
    }
}