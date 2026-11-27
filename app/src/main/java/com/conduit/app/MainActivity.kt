package com.conduit.nexus

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    private fun runBootstrapInit(): Pair<String, String?>? {
        return try {
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
            null
        } catch (t: Throwable) {
            val savedPath = CrashBootstrap.logHandledThrowable(this, "BOOT_INIT", t)
            val report = CrashBootstrap.formatReport(this, Thread.currentThread(), t, "BOOT_INIT_CRASH")
            report to savedPath
        }
    }

    private fun requestStartupPermissions() {
        val permsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Throwable) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (t: Throwable) {
                        CrashBootstrap.logHandledThrowable(this, "STORAGE_PERM", t)
                    }
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permsToRequest.isNotEmpty()) {
            try {
                requestPermissionLauncher.launch(permsToRequest.toTypedArray())
            } catch (t: Throwable) {
                CrashBootstrap.logHandledThrowable(this, "PERM_LAUNCHER", t)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashBootstrap.install(applicationContext)
        super.onCreate(savedInstanceState)

        val initialBootCrash = runBootstrapInit()
        if (initialBootCrash == null) {
            requestStartupPermissions()
        }

        setContent {
            var bootCrash by remember { androidx.compose.runtime.mutableStateOf(initialBootCrash) }
            var isReady by remember { androidx.compose.runtime.mutableStateOf(SharedRuntimeLoader.isLoaded) }
            var loadingMsg by remember { androidx.compose.runtime.mutableStateOf("Loading shared runtime...") }

            LaunchedEffect(bootCrash) {
                if (bootCrash == null && !isReady) {
                    try {
                        val loaded = SharedRuntimeLoader.ensureLoaded(this@MainActivity) { status ->
                            loadingMsg = status
                        }
                        if (loaded) {
                            isReady = true
                        } else {
                            loadingMsg = "Failed loading shared runtime. Check internet or storage permissions."
                        }
                    } catch (t: Throwable) {
                        val savedPath = CrashBootstrap.logHandledThrowable(this@MainActivity, "RUNTIME_LOAD", t)
                        val report = CrashBootstrap.formatReport(this@MainActivity, Thread.currentThread(), t, "RUNTIME_LOAD_CRASH")
                        bootCrash = report to savedPath
                    }
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
                    val activeCrash = bootCrash
                    if (activeCrash != null) {
                        val (report, savedPath) = activeCrash
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Nexus Boot Crash Caught",
                                color = Color(0xFFFF5252),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Saved log: ${savedPath ?: "App external/internal crash_logs"}",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color(0xFF161B22), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = report,
                                    color = Color(0xFFFFCDD2),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Nexus Crash Log", report))
                                        Toast.makeText(this@MainActivity, "Crash log copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                                ) {
                                    Text("Copy Log", color = Color.White)
                                }
                                Button(
                                    onClick = {
                                        val retryErr = runBootstrapInit()
                                        bootCrash = retryErr
                                        if (retryErr == null) {
                                            isReady = SharedRuntimeLoader.isLoaded
                                            requestStartupPermissions()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                                ) {
                                    Text("Retry Boot", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (isReady) {
                        ConduitIDE(viewModel)
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFF00E5FF))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Nexus Core Loading",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
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