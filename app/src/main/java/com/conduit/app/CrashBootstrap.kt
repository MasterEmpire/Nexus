package com.conduit.nexus

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.chaquo.python.Python
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashBootstrap {
    private const val TAG = "NexusCrashBootstrap"
    private const val PUBLIC_CRASH_DIR = "/storage/emulated/0/Nexus/crash_logs"

    @Volatile
    private var installed = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext ?: context
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val report = formatReport(appContext, thread, throwable, "UNCAUGHT_FATAL")
                    Log.e(TAG, report)
                    writeReportToDisk(appContext, report)
                } catch (e: Throwable) {
                    Log.e(TAG, "CrashBootstrap failed while writing crash log: ${e.message}", e)
                }

                val prev = previousHandler
                if (prev != null) {
                    prev.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(10)
                }
            }
            installed = true
        }
    }

    fun logHandledThrowable(context: Context, sourceTag: String, throwable: Throwable): String? {
        val appContext = context.applicationContext ?: context
        val report = formatReport(appContext, Thread.currentThread(), throwable, "CAUGHT_$sourceTag")
        Log.e(TAG, report)
        return writeReportToDisk(appContext, report)
    }

    fun formatReport(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        kind: String = "UNCAUGHT_FATAL"
    ): String {
        val now = Date()
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(now)

        val (versionName, versionCode) = try {
            @Suppress("DEPRECATION")
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            (pi.versionName ?: "unknown") to code.toString()
        } catch (_: Throwable) {
            "unknown" to "unknown"
        }

        val pythonStarted = try {
            Python.isStarted()
        } catch (_: Throwable) {
            false
        }

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()

        return buildString {
            appendLine("================ NEXUS CRASH REPORT ================")
            appendLine("Kind: $kind")
            appendLine("Timestamp: $timeText")
            appendLine("Package: ${context.packageName} ($versionName / code $versionCode)")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("SharedRuntimeLoaded: ${SharedRuntimeLoader.isLoaded}")
            appendLine("PythonStarted: $pythonStarted")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("----------------------------------------------------")
            appendLine(sw.toString().trimEnd())
            appendLine("====================================================")
        }
    }

    private fun writeReportToDisk(context: Context, report: String): String? {
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "crash_$fileStamp.txt"
        val candidateDirs = mutableListOf<File>()

        // 1. Primary easy-to-find shared storage folder
        candidateDirs.add(File(PUBLIC_CRASH_DIR))

        // 2. Public Documents fallback
        try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (docs != null) {
                candidateDirs.add(File(docs, "Nexus/crash_logs"))
            }
        } catch (_: Throwable) {}

        // 3. App-specific external storage (always writable even without MANAGE_EXTERNAL_STORAGE)
        try {
            context.getExternalFilesDir("crash_logs")?.let { candidateDirs.add(it) }
        } catch (_: Throwable) {}

        // 4. Internal fallback (always writable)
        try {
            candidateDirs.add(File(context.filesDir, "crash_logs"))
        } catch (_: Throwable) {}

        var primarySavedPath: String? = null
        val seen = HashSet<String>()

        for (dir in candidateDirs) {
            val pathKey = try { dir.canonicalPath } catch (_: Throwable) { dir.absolutePath }
            if (!seen.add(pathKey)) continue

            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (!dir.exists() || !dir.isDirectory) continue

                val crashFile = File(dir, fileName)
                crashFile.writeText(report)

                val latestFile = File(dir, "latest_crash.txt")
                latestFile.writeText(report)

                if (primarySavedPath == null) {
                    primarySavedPath = crashFile.absolutePath
                }
            } catch (_: Throwable) {
                // Keep trying fallback locations
            }
        }
        return primarySavedPath
    }
}