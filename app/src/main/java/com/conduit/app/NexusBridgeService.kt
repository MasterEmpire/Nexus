package com.conduit.nexus

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.os.Message as OsMessage
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
    private val inputQueues = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.LinkedBlockingQueue<String>>()

    companion object {
        private const val TAG = "NexusBridge"

        // IPC Message Action Protocol
        const val MSG_EXECUTE_CODE = 100
        const val MSG_STREAM_OUTPUT = 101
        const val MSG_EXECUTION_SUCCESS = 102
        const val MSG_EXECUTION_ERROR = 103
        const val MSG_REQUEST_INPUT = 104
        const val MSG_PROVIDE_INPUT = 105

        // Bundle Keys
        const val KEY_CODE = "key_code"
        const val KEY_EXEC_ID = "key_exec_id"
        const val KEY_OUTPUT_CHUNK = "key_chunk"
        const val KEY_FINAL_RESULT = "key_result"
        const val KEY_ERROR_MESSAGE = "key_error"
        const val KEY_INPUT_PROMPT = "key_prompt"
        const val KEY_INPUT_VALUE = "key_input_value"
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        messenger = Messenger(IncomingHandler(
            scope = serviceScope,
            onProvideInput = { execId, value ->
                inputQueues[execId]?.offer(value)
            },
            onExecute = { code, execId, replyTo ->
                executePython(code, execId, replyTo)
            }
        ))
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
        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        inputQueues[execId] = queue
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

                val inputCallback = object {
                    fun onInputRequested(prompt: String): String {
                        sendInputRequest(replyTo, execId, prompt)
                        return try {
                            queue.poll(120, java.util.concurrent.TimeUnit.SECONDS) ?: ""
                        } catch (e: InterruptedException) {
                            ""
                        }
                    }
                }

                val result = executor.callAttr("run_code", code, outputCallback, inputCallback).toString()
                val isTraceback = result.contains("Traceback (most recent call last)") || result.startsWith("Error:")

                if (isTraceback) {
                    sendError(replyTo, execId, result)
                } else {
                    sendSuccess(replyTo, execId, result)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Execution exception: ${t.message}", t)
                sendError(replyTo, execId, "Execution Exception: ${t.message}\n${t.stackTraceToString()}")
            } finally {
                inputQueues.remove(execId)
            }
        }
    }

    private fun sendInputRequest(replyTo: Messenger, execId: String, prompt: String) {
        try {
            val msg = OsMessage.obtain(null, MSG_REQUEST_INPUT).apply {
                data = Bundle().apply {
                    putString(KEY_EXEC_ID, execId)
                    putString(KEY_INPUT_PROMPT, prompt)
                }
            }
            replyTo.send(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending input request: ${e.message}")
        }
    }

    private fun sendChunk(replyTo: Messenger, execId: String, chunk: String) {
        try {
            val msg = OsMessage.obtain(null, MSG_STREAM_OUTPUT).apply {
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
            val msg = OsMessage.obtain(null, MSG_EXECUTION_SUCCESS).apply {
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
            val msg = OsMessage.obtain(null, MSG_EXECUTION_ERROR).apply {
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
        inputQueues.values.forEach { it.offer("") }
        inputQueues.clear()
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Nexus Bridge Service destroyed.")
    }

    private class IncomingHandler(
        private val scope: CoroutineScope,
        private val onProvideInput: (execId: String, value: String) -> Unit,
        private val onExecute: (code: String, execId: String, replyTo: Messenger) -> Unit
    ) : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: OsMessage) {
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
                MSG_PROVIDE_INPUT -> {
                    val bundle = msg.data ?: return
                    val execId = bundle.getString(KEY_EXEC_ID) ?: ""
                    val value = bundle.getString(KEY_INPUT_VALUE) ?: ""
                    if (execId.isNotBlank()) {
                        onProvideInput(execId, value)
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }
}