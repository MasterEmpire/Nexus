package com.conduit.nexus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class PipPackageItem(
    val name: String,
    val version: String,
    val source: String
)

@Composable
fun PipScreen() {
    val scope = rememberCoroutineScope()
    val python = remember { Python.getInstance() }
    val pipManager = remember { python.getModule("pip_manager") }

    var packageInput by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf<List<PipPackageItem>>(emptyList()) }

    val quickSuggestions = remember {
        listOf("yt-dlp", "rich", "pypdf", "tabulate", "qrcode", "httpx", "python-dotenv", "markdown", "openpyxl", "tqdm")
    }

    fun refreshList() {
        scope.launch(Dispatchers.IO) {
            try {
                val raw = pipManager.callAttr("list_packages_json").toString()
                val arr = JSONArray(raw)
                val parsed = mutableListOf<PipPackageItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    parsed.add(
                        PipPackageItem(
                            name = obj.optString("name"),
                            version = obj.optString("version"),
                            source = obj.optString("source")
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    packages = parsed
                }
            } catch (_: Exception) {}
        }
    }

    fun installPkg(pkg: String) {
        val trimmed = pkg.trim()
        if (trimmed.isBlank() || isBusy) return
        isBusy = true
        installLog = "$ pip install $trimmed\n"
        scope.launch(Dispatchers.IO) {
            try {
                val callback = object {
                    fun onOutput(line: String) {
                        scope.launch(Dispatchers.Main) {
                            installLog += "$line\n"
                        }
                    }
                }
                pipManager.callAttr("install_package", trimmed, callback)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    installLog += "Error: ${e.message}\n"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    packageInput = ""
                }
                refreshList()
            }
        }
    }

    fun uninstallPkg(pkgName: String) {
        if (isBusy) return
        isBusy = true
        installLog = "$ pip uninstall $pkgName\n"
        scope.launch(Dispatchers.IO) {
            val result = try {
                pipManager.callAttr("uninstall_package", pkgName).toString()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                installLog += "$result\n"
                isBusy = false
            }
            refreshList()
        }
    }

    LaunchedEffect(Unit) {
        refreshList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pip Package Manager",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Install pure-Python packages on-device from PyPI",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = { refreshList() }, enabled = !isBusy) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF00E5FF))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = packageInput,
                onValueChange = { packageInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. yt-dlp or pypdf==4.0.0", fontSize = 13.sp) },
                singleLine = true,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { installPkg(packageInput) })
            )
            Button(
                onClick = { installPkg(packageInput) },
                enabled = !isBusy && packageInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                modifier = Modifier.height(52.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Download, contentDescription = "Install", tint = Color.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(quickSuggestions) { name ->
                AssistChip(
                    onClick = { installPkg(name) },
                    enabled = !isBusy,
                    label = { Text("+ $name", fontSize = 11.sp, color = Color(0xFF00E5FF)) }
                )
            }
        }

        if (installLog.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            val logScroll = rememberScrollState()
            LaunchedEffect(installLog) {
                logScroll.animateScrollTo(logScroll.maxValue)
            }
            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 140.dp)
                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = installLog.trimEnd(),
                    color = Color(0xFF69F0AE),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(logScroll)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val userCount = packages.count { it.source == "user" }
        val bundledCount = packages.count { it.source == "bundled" }
        Text(
            text = "INSTALLED ($userCount ON-DEVICE • $bundledCount PRE-BAKED)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(packages, key = { "${it.source}_${it.name}" }) { pkg ->
                val isUser = pkg.source == "user"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161B22), RoundedCornerShape(10.dp))
                        .border(
                            1.dp,
                            if (isUser) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pkg.name,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "v${pkg.version} • ${if (isUser) "Installed on-device" else "Pre-baked in APK"}",
                            color = if (isUser) Color(0xFF69F0AE) else Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    if (isUser) {
                        IconButton(onClick = { uninstallPkg(pkg.name) }, enabled = !isBusy) {
                            Icon(Icons.Default.Delete, contentDescription = "Uninstall", tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Bundled", tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}