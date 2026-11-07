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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePickerIntent(intent)
    }

    private fun handlePickerIntent(inIntent: Intent?) {
        if (inIntent == null) return
        val action = inIntent.action
        val isPicker = inIntent.getBooleanExtra("extra_picker_mode", false) ||
            action == Intent.ACTION_GET_CONTENT ||
            action == Intent.ACTION_OPEN_DOCUMENT ||
            action == "com.conduit.nexus.action.PICK_FILE"

        if (isPicker) {
            val allowMultiple = inIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            val mime = inIntent.type ?: "*/*"
            val initialPath = inIntent.getStringExtra("extra_initial_path")

            viewModel.currentView = "explorer"
            viewModel.selectedTab = 1
            viewModel.isPickerMode = true
            viewModel.pickerAllowMultiple = allowMultiple
            viewModel.pickerMimeFilter = mime

            if (!initialPath.isNullOrEmpty() && java.io.File(initialPath).exists()) {
                viewModel.explorerCategory = "Internal Storage"
                viewModel.explorerPath = initialPath
                viewModel.loadExplorerFolder(initialPath, this)
            } else {
                viewModel.explorerCategory = null
                viewModel.refreshDashboard(this)
            }
        }
    }

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
        handlePickerIntent(intent)
        viewModel.loadQuickPills(this)
        viewModel.loadPinnedPaths(this)
        viewModel.loadFavoritePaths(this)
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