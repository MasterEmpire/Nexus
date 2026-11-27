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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            action == Intent.ACTION_PICK ||
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
            return
        }

        if (action == Intent.ACTION_VIEW) {
            val viewUri = inIntent.data
            if (viewUri != null) {
                viewModel.handleExternalViewIntent(this, viewUri, inIntent.type)
            }
            return
        }

        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = mutableListOf<android.net.Uri>()
            if (action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                val singleUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    inIntent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    inIntent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (singleUri != null) uris.add(singleUri)
            } else {
                @Suppress("DEPRECATION")
                val multiUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    inIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    inIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (multiUris != null) uris.addAll(multiUris)
            }

            if (uris.isEmpty() && inIntent.clipData != null) {
                val cd = inIntent.clipData!!
                for (i in 0 until cd.itemCount) {
                    cd.getItemAt(i).uri?.let { uris.add(it) }
                }
            }

            val sharedText = inIntent.getStringExtra(Intent.EXTRA_TEXT)
            if (uris.isNotEmpty() || !sharedText.isNullOrBlank()) {
                viewModel.handleIncomingShareIntent(this, uris, sharedText)
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

        SharedRuntimeLoader.tryLoadExisting(this)

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
            var isReady by remember { androidx.compose.runtime.mutableStateOf(SharedRuntimeLoader.isLoaded) }
            var loadingMsg by remember { androidx.compose.runtime.mutableStateOf("Loading shared runtime...") }

            LaunchedEffect(Unit) {
                if (!isReady) {
                    val loaded = SharedRuntimeLoader.ensureLoaded(this@MainActivity) { status ->
                        loadingMsg = status
                    }
                    isReady = loaded
                }
            }

            val conduitColorScheme = darkColorScheme(
                primary = Color(0xFF00E5FF),
                secondary = Color(0xFF7C4DFF),
                background = Color(0xFF0D1117),
                surface = Color(0xFF161B22),
                onSurface = Color(0xFFC9D1D9)
            )
            MaterialTheme(colorScheme = conduitColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (isReady) {
                        ConduitIDE(viewModel)
                    } else {
                        Box(
                            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                modifier = androidx.compose.ui.Modifier.padding(24.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFF00E5FF))
                                Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                                Text(
                                    text = "Nexus Core Loading",
                                    color = Color.White,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = androidx.compose.ui.Modifier.height(6.dp))
                                Text(
                                    text = loadingMsg,
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}