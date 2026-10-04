package com.conduit.nexus

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*

/**
 * Signature-protected microkernel service executing Python bytecode for trusted peer apps.
 */
class NexusBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var messenger: Messenger

    companion object {
        private const val TAG = "NexusBridge"

        // IPC Message Action Protocol
        const val MSG_EXECUTE_CODE = 100
        const val MSG_STREAM_OUTPUT = 101
        const val MSG_EXECUTION_SUCCESS = 102
        const val MSG_EXECUTION_ERROR = 103

        // Bundle Keys
        const val KEY_CODE = "key_code"
        const val KEY_EXEC_ID = "key_exec_id"
        const val KEY_OUTPUT_CHUNK = "key_chunk"
        const val KEY_FINAL_RESULT = "key_result"
        const val KEY_ERROR_MESSAGE = "key_error"
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        messenger = Messenger(IncomingHandler(serviceScope) { code, execId, replyTo ->
            executePython(code, execId, replyTo)
        })
        Log.d(TAG, "Nexus Bridge Service initialized and armed with Chaquopy.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        val callingUid = Binder.getCallingUid()
        val signatureMatch = packageManager.checkSignatures(callingUid, Process.myUid()) == PackageManager.SIGNATURE_MATCH

        if (!signatureMatch) {
            Log.e(TAG, "⛔ Security Violation: Calling UID $callingUid does NOT share our developer signature. Rejecting bind.")
            return null
        }

        Log.d(TAG, "🤝 Verified trusted peer (UID $callingUid). Establishing Python IPC connection.")
        return messenger.binder
    }

    private fun executePython(code: String, execId: String, replyTo: Messenger) {
        serviceScope.launch {
            try {
                val python = Python.getInstance()
                val executor = python.getModule("executor")

                // Real-time stdout/stderr stream bridge
                val outputCallback = object {
                    fun onOutput(text: String) {
                        sendChunk(replyTo, execId, text)
                    }
                }

                val result = executor.callAttr("run_code", code, outputCallback).toString()
                val isTraceback = result.contains("Traceback (most recent call last)") || result.startsWith("Error:")

                if (isTraceback) {
                    sendError(replyTo, execId, result)
                } else {
                    sendSuccess(replyTo, execId, result)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Execution exception: ${t.message}", t)
                sendError(replyTo, execId, "Execution Exception: ${t.message}\n${t.stackTraceToString()}")
            }
        }
    }

    private fun sendChunk(replyTo: Messenger, execId: String, chunk: String) {
        try {
            val msg = Message.obtain(null, MSG_STREAM_OUTPUT).apply {
                data = Bundle().apply {
                    putString(KEY_EXEC_ID, execId)
                    putString(KEY_OUTPUT_CHUNK, chunk)
                }
            }
            replyTo.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed streaming chunk: ${e.message}")
        }
    }

    private fun sendSuccess(replyTo: Messenger, execId: String, result: String) {
        try {
            val msg = Message.obtain(null, MSG_EXECUTION_SUCCESS).apply {
                data = Bundle().apply {
                    putString(KEY_EXEC_ID, execId)
                    putString(KEY_FINAL_RESULT, result)
                }
            }
            replyTo.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending success: ${e.message}")
        }
    }

    private fun sendError(replyTo: Messenger, execId: String, error: String) {
        try {
            val msg = Message.obtain(null, MSG_EXECUTION_ERROR).apply {
                data = Bundle().apply {
                    putString(KEY_EXEC_ID, execId)
                    putString(KEY_ERROR_MESSAGE, error)
                }
            }
            replyTo.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending error: ${e.message}")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Nexus Bridge Service destroyed.")
    }

    private class IncomingHandler(
        private val scope: CoroutineScope,
        private val onExecute: (code: String, execId: String, replyTo: Messenger) -> Unit
    ) : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_EXECUTE_CODE -> {
                    val bundle = msg.data ?: return
                    val code = bundle.getString(KEY_CODE) ?: ""
                    val execId = bundle.getString(KEY_EXEC_ID) ?: System.currentTimeMillis().toString()
                    val replyTo = msg.replyTo
                    if (code.isNotBlank() && replyTo != null) {
                        onExecute(code, execId, replyTo)
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }
}