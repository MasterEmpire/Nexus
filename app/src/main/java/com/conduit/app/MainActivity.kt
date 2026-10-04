package com.conduit.nexus

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.system.exitProcess
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.POST_NOTIFICATIONS] == false) {
            Toast.makeText(this, "Notifications disabled. You won't see background progress.", Toast.LENGTH_SHORT).show()
        }
    }
    private val viewModel: ConduitViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Request Notification Permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
        
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "FATAL ERROR: ${throwable.localizedMessage}", Toast.LENGTH_LONG).show()
            }
            // Give the Toast time to appear before the process dies
            Thread.sleep(3500)
            exitProcess(1)
        }
        super.onCreate(savedInstanceState)
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        viewModel.loadQuickPills(this)
        viewModel.loadPinnedPaths(this)
        viewModel.loadFolderCache(this)
        viewModel.initRecovery(cacheDir)
        viewModel.loadSettings(this)
        


        // Request Storage Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }

        setContent {
            val conduitColorScheme = darkColorScheme(
                primary = Color(0xFF00E5FF),
                secondary = Color(0xFF7C4DFF),
                background = Color(0xFF0D1117),
                surface = Color(0xFF161B22),
                onSurface = Color(0xFFC9D1D9)
            )
            MaterialTheme(colorScheme = conduitColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ConduitIDE(viewModel)
                }
            }
        }
    }
}