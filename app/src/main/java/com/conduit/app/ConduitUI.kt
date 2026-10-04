package com.conduit.nexus

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConduitIDE(viewModel: ConduitViewModel) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val python = remember { Python.getInstance() }
    val executor = remember { python.getModule("executor") }
    val terminalManager = remember { python.getModule("terminal_manager") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val contentResolver = context.contentResolver
                    val fileName = "att_" + (System.currentTimeMillis() % 10000) + "_" + (uri.path?.split("/")?.lastOrNull() ?: "file")
                    val destDir = File("/storage/emulated/0/Conduit")
                    if (!destDir.exists()) destDir.mkdirs()
                    val destFile = File(destDir, fileName)
                    contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(destFile).use { input.copyTo(it) } }
                    withContext(Dispatchers.Main) { viewModel.addStagedFile(destFile) }
                } catch (e: Exception) { }
            }
        }
    }

    var terminalOutput by remember { mutableStateOf("Python ${System.getProperty("os.version")} REPL\n>>> ") }
    var terminalInput by remember { mutableStateOf("") }

    BackHandler(enabled = viewModel.currentView != "chat" || drawerState.isOpen) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            viewModel.currentView = "chat"
            viewModel.selectedTab = 0
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = viewModel.editingFile == null,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                SidebarContent(
                        sessions = viewModel.sessions,
                        onSessionClick = { viewModel.loadSession(it); scope.launch { drawerState.close() } },
                        onNewChatClick = { viewModel.createNewChat(); scope.launch { drawerState.close() } },
                        onDeleteClick = { viewModel.deleteSession(it) },
                        onPipClick = { viewModel.currentView = "pip"; scope.launch { drawerState.close() } },
                        onTerminalClick = { viewModel.currentView = "terminal"; scope.launch { drawerState.close() } },
                        onExplorerClick = { viewModel.currentView = "explorer"; viewModel.selectedTab = 1; scope.launch { drawerState.close() } },
                        onInterpreterClick = { viewModel.currentView = "interpreter"; scope.launch { drawerState.close() } },
                        onManualClick = { viewModel.currentView = "manual"; scope.launch { drawerState.close() } },
                        onChatNavClick = { viewModel.currentView = "chat"; viewModel.selectedTab = 0; scope.launch { drawerState.close() } }
                    )
            }
        }
    ) {
        if (viewModel.editingFile != null) {
            TextEditorScreen(viewModel, onBack = { viewModel.editingFile = null })
        } else {
            Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !viewModel.isSearching,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut()
                        ) {
                            Column {
                                val titleText = if (viewModel.currentView == "chat") {
                                    viewModel.sessions.find { it.first == viewModel.currentSessionId }?.second ?: "New Conversation"
                                } else {
                                    "Conduit AI"
                                }
                                Text(
                                    text = titleText,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = viewModel.currentCwd.replace("/storage/emulated/0", "~Shared"), 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    actions = {
                        if (viewModel.currentView == "chat") {
                            IconButton(onClick = { viewModel.createNewChat() }) {
                                Icon(Icons.Default.AddComment, contentDescription = "New Chat", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (viewModel.currentView == "explorer") {
                            ExpandingSearch(
                                isExpanded = viewModel.isSearching,
                                query = viewModel.searchQuery,
                                onQueryChange = { viewModel.searchQuery = it },
                                onToggle = { viewModel.isSearching = !viewModel.isSearching },
                                onSearch = {
                                    viewModel.isScanningExplorer = true
                                    scope.launch(Dispatchers.IO) {
                                        val target = if (viewModel.explorerCategory == "Internal Storage" && viewModel.explorerPath != "/storage/emulated/0") viewModel.explorerPath else null
                                        val results = FileScanner.searchFilesNative(context, viewModel.searchQuery, target)
                                        withContext(Dispatchers.Main) {
                                            if (viewModel.explorerCategory != "Search Results") {
                                                viewModel.explorerReturnCategory = viewModel.explorerCategory
                                            }
                                            viewModel.explorerCategory = "Search Results"
                                            viewModel.explorerFiles = results.sortedByDescending { it.path in viewModel.pinnedPaths }
                                            viewModel.isScanningExplorer = false
                                        }
                                    }
                                }
                            )
                            if (viewModel.explorerCategory != null && !viewModel.isSearching) {
                                Box {
                                    IconButton(onClick = { viewModel.showExplorerSortMenu = true }) {
                                        Icon(Icons.Default.Sort, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    DropdownMenu(
                                        expanded = viewModel.showExplorerSortMenu,
                                        onDismissRequest = { viewModel.showExplorerSortMenu = false }
                                    ) {
                                        SortOrder.entries.forEach { order ->
                                            val isActive = viewModel.currentSortOrder == order
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = order.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                                                        color = if (isActive) Color(0xFF00E5FF) else Color.Unspecified,
                                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    viewModel.currentSortOrder = order
                                                    viewModel.loadExplorerFolder(viewModel.explorerPath, context)
                                                    viewModel.showExplorerSortMenu = false
                                                },
                                                leadingIcon = if (isActive) { { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp), tint = Color(0xFF00E5FF)) } } else null
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { viewModel.showExplorerCreateDialog = true }) {
                                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                if (viewModel.isDataLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().zIndex(1f),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
                when (viewModel.currentView) {
                    "terminal" -> TerminalScreen(
                        input = terminalInput,
                        output = terminalOutput,
                        onInputChange = { terminalInput = it },
                        onClear = { terminalOutput = "Python ${System.getProperty("os.version")} REPL\n>>> " },
                        onRun = {
                            if (terminalInput.isNotBlank()) {
                                val cmd = terminalInput
                                terminalInput = ""
                                scope.launch(Dispatchers.IO) {
                                    val result = terminalManager.callAttr("run_repl_line", cmd).toString()
                                    withContext(Dispatchers.Main) {
                                        terminalOutput += "$cmd\n$result${if (result.isEmpty()) "" else "\n"}>>> "
                                    }
                                }
                            }
                        }
                    )

                    "interpreter" -> InterpreterScreen(viewModel)
                    "explorer" -> ExplorerScreen(viewModel)
                    "chat" -> ChatScreen(
                        viewModel = viewModel,
                        messages = viewModel.chatMessages,
                        input = viewModel.chatInput,
                        aiStatus = viewModel.aiStatus,
                        isThinking = viewModel.aiStatus != null,
                        scriptProgress = viewModel.scriptProgress,
                        onInputChange = { viewModel.chatInput = it },
                        onSend = { viewModel.sendMessage(context) },
                        onAttachFile = { filePickerLauncher.launch("*/*") },
                        onInjectStructure = { viewModel.injectStructure() },
                        onQuickAction = { 
                            viewModel.chatInput = it
                            viewModel.sendMessage(context) 
                        },
                        stagedFiles = viewModel.stagedFiles,
                        onRemoveFile = { viewModel.removeStagedFile(it) },
                        onRetryMessage = { viewModel.onRetryMessage(context, it) },
                        onEditMessage = { msg -> 
                            viewModel.chatInput = msg.text
                            viewModel.editingMessageTimestamp = msg.timestamp
                        }
                    )
                    "manual" -> ManualScreen(
                        viewModel = viewModel,
                        onRun = { viewModel.runManualCode(context) }
                    )
                }
            }
        }
    }
}
}