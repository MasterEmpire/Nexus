package com.conduit.nexus

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import kotlinx.coroutines.*
import java.io.File

class ConduitExecutionService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val CHANNEL_ID = "conduit_exec_channel"
    private val NOTIF_ID = 1337

    companion object {
        @Volatile var isRunning = false
        @Volatile var isWaitingForInput = false
        var outputListener: ((String) -> Unit)? = null
        var inputRequestListener: ((String) -> Unit)? = null
        private var activeInputQueue: java.util.concurrent.LinkedBlockingQueue<String>? = null
        var onFinished: (() -> Unit)? = null

        fun provideInput(text: String) {
            activeInputQueue?.offer(text)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra("code")
        
        if (code == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Required for Android 14+: startForeground must be called within 10 seconds
        startForeground(NOTIF_ID, buildNotification("Kernel: Initializing...", true))
        
        val inputQueue = java.util.concurrent.LinkedBlockingQueue<String>()
        activeInputQueue = inputQueue
        isWaitingForInput = false
        isRunning = true
        serviceScope.launch {
            try {
                val python = Python.getInstance()
                val executor = python.getModule("executor")
                
                val outputCallback = object {
                    fun onOutput(text: String) {
                        outputListener?.invoke(text)
                    }
                }

                val inputCallback = object {
                    fun onInputRequested(prompt: String): String {
                        isWaitingForInput = true
                        inputRequestListener?.invoke(prompt)
                        updateNotification("Waiting for user input...", false)
                        val input = try {
                            inputQueue.poll(120, java.util.concurrent.TimeUnit.SECONDS) ?: ""
                        } catch (e: InterruptedException) {
                            ""
                        } finally {
                            isWaitingForInput = false
                            updateNotification("Nexus Engine Active", true)
                        }
                        return input
                    }
                }

                executor.callAttr("run_code", code, outputCallback, inputCallback)
            } catch (e: Exception) {
                outputListener?.invoke("\n[SERVICE ERROR]: ${e.message}")
            } finally {
                isRunning = false
                isWaitingForInput = false
                activeInputQueue = null
                onFinished?.invoke()
                outputListener = null
                inputRequestListener = null
                onFinished = null
                updateNotification("Execution Finished", false)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun buildNotification(content: String, showProgress: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexus Engine Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(showProgress)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, showProgress)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String, showProgress: Boolean) {
        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(content, showProgress))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "Script Execution", 
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of long-running Python scripts"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        activeInputQueue?.offer("")
        serviceScope.cancel()
        super.onDestroy()
    }
}