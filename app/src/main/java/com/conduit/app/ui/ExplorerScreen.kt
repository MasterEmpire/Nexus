package com.conduit.nexus

import androidx.compose.animation.* 
import androidx.compose.animation.core.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import android.content.Intent
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler

@Composable
fun ExplorerScreen(viewModel: ConduitViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Auto-save scroll positions as the user scrolls through the current path
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (!viewModel.isScanningExplorer && viewModel.explorerFiles.isNotEmpty()) {
                    viewModel.scrollPositions[viewModel.explorerPath] = index
                }
            }
    }

    // Restore scroll position when entering a folder (or reset to 0 if newly visited)
    LaunchedEffect(viewModel.explorerPath, viewModel.explorerFiles, viewModel.isScanningExplorer) {
        if (!viewModel.isScanningExplorer && viewModel.explorerFiles.isNotEmpty() && viewModel.highlightedPath == null) {
            val targetIndex = viewModel.scrollPositions[viewModel.explorerPath] ?: 0
            if (targetIndex < viewModel.explorerFiles.size) {
                listState.scrollToItem(targetIndex)
            } else {
                listState.scrollToItem(0)
            }
        }
    }
    
    val currentCategory = viewModel.explorerCategory
    val isScanning = viewModel.isScanningExplorer

    LaunchedEffect(Unit) {
        viewModel.refreshDashboard(context)
    }

    // Robust Auto-Scroll for AI Navigation
    LaunchedEffect(viewModel.explorerFiles, viewModel.highlightedPath) {
        val target = viewModel.highlightedPath
        if (target != null && !viewModel.isScanningExplorer) {
            val index = viewModel.explorerFiles.indexOfFirst { 
                java.io.File(it.path).canonicalPath == java.io.File(target).canonicalPath 
            }
            if (index != -1) {
                // Give the UI a frame to settle before scrolling
                scope.launch {
                    kotlinx.coroutines.delay(200)
                    listState.animateScrollToItem(index)
                    // Keep highlighted for a bit so user sees it, then clear
                    kotlinx.coroutines.delay(3000)
                    viewModel.highlightedPath = null
                }
            }
        }
    }
    
    val showCreateDialog = viewModel.showExplorerCreateDialog
    var createType by remember { mutableStateOf("File") }
    var newItemName by remember { mutableStateOf("") }

    // Ops State
    val selectedFiles = viewModel.selectedFiles
    var showRenameDialog by remember { mutableStateOf(false) }
    var showPropertyDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val loadFolder = { path: String -> viewModel.loadExplorerFolder(path, context) }
    val openCategory = { cat: String -> viewModel.openExplorerCategory(context, cat) }

    val handleBackNavigation = {
        viewModel.isSearching = false
        val retCat = viewModel.explorerReturnCategory
        if (retCat != null) {
            viewModel.explorerReturnCategory = null
            if (retCat == "Internal Storage") loadFolder(viewModel.explorerPath)
            else openCategory(retCat)
        } else if (currentCategory == "Search Results") {
            loadFolder(viewModel.explorerPath)
        } else if (currentCategory == "Internal Storage" && viewModel.explorerPath != "/storage/emulated/0") {
            val current = viewModel.explorerPath
            val parent = if (current.contains("!/")) {
                val zipPart = current.substringBefore("!/")
                val intPart = current.substringAfter("!/").removeSuffix("/")
                if (intPart.isEmpty()) java.io.File(zipPart).parent ?: "/storage/emulated/0"
                else {
                    val p = java.io.File(intPart).parent ?: ""
                    if (p.isEmpty()) "$zipPart!/" else "$zipPart!/$p/"
                }
            } else {
                java.io.File(current).parent ?: "/storage/emulated/0"
            }
            loadFolder(parent)
        } else {
            viewModel.explorerCategory = null
        }
    }

    BackHandler(enabled = selectedFiles.isNotEmpty() || currentCategory != null) {
        if (selectedFiles.isNotEmpty()) {
            viewModel.clearSelection()
        } else {
            handleBackNavigation()
        }
    }

        Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(onTap = {
                viewModel.isSearching = false
                focusManager.clearFocus()
            })
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (currentCategory == null) {
                Text(
                    text = "Nexus Explorer",
                    style = MaterialTheme.typography.headlineSmall, 
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                
                // Storage Card
                item {
                    StorageUsageCard(
                        label = viewModel.storageLabel, 
                        percent = viewModel.storagePercent,
                        onClick = { openCategory("Internal Storage") }
                    )
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Category Grid
                item {
                    CategoryGrid(onCatClick = { openCategory(it) })
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Recent Files Section
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent Files", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { openCategory("Recent Files") }) {
                            Text("View All", color = Color(0xFF00E5FF), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                items(viewModel.dashboardRecentFiles) { file ->
                    FileRow(
                        file = file, 
                        isHighlighted = selectedFiles.contains(file),
                        isPinned = viewModel.pinnedPaths.contains(file.path),
                        onClick = {
                            if (selectedFiles.isNotEmpty()) viewModel.toggleSelection(file) 
                            else viewModel.onFileClicked(file, context, loadFolder)
                        },
                        onLongClick = { viewModel.toggleSelection(file) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // Dynamic Category Overlay
        AnimatedVisibility(
            visible = currentCategory != null,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it })
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D1117)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF161B22)).padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            viewModel.clearSelection()
                            handleBackNavigation()
                        }) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                        }
                        val isInternalRoot = currentCategory == "Internal Storage" && viewModel.explorerPath == "/storage/emulated/0"
                        
                        IconButton(onClick = { viewModel.explorerCategory = null }) {
                            Icon(Icons.Default.Home, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }

                        val breadcrumbScrollState = androidx.compose.foundation.rememberScrollState()
                        
                        LaunchedEffect(breadcrumbScrollState.maxValue) {
                            breadcrumbScrollState.animateScrollTo(breadcrumbScrollState.maxValue)
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(breadcrumbScrollState),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentCategory == "Internal Storage") {
                                val rootPath = "/storage/emulated/0"
                                val currentPath = viewModel.explorerPath
                                
                                // Root Link
                                Text(
                                    text = "Root",
                                    color = if (currentPath == rootPath) Color(0xFF00E5FF) else Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { viewModel.loadExplorerFolder(rootPath) }
                                )

                                if (currentPath != rootPath) {
                                    val subPath = currentPath.removePrefix(rootPath).removePrefix("/")
                                    val segments = subPath.split("/")
                                    var accumulatedPath = rootPath

                                    segments.forEach { segment ->
                                        if (segment.isNotEmpty()) {
                                            if (segment.endsWith("!")) {
                                                accumulatedPath += "/$segment/"
                                            } else if (accumulatedPath.contains("!/")) {
                                                accumulatedPath += "$segment/"
                                            } else {
                                                accumulatedPath += "/$segment"
                                            }
                                            
                                            val targetPath = accumulatedPath.replace("!//", "!/")
                                            
                                            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                                            Text(
                                                text = segment.removeSuffix("!"),
                                                color = if (currentPath == targetPath) Color(0xFF00E5FF) else Color.Gray,
                                                fontWeight = if (currentPath == targetPath) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp,
                                                modifier = Modifier.clickable { viewModel.loadExplorerFolder(targetPath) }
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Fallback for other categories (Images, Search, etc.)
                                Text(
                                    text = currentCategory ?: "",
                                    color = Color(0xFF00E5FF),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                    
                    if (isScanning) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF00E5FF))
                        }
                    } else if (viewModel.explorerFiles.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.FolderOpen, 
                                    null, 
                                    modifier = Modifier.size(64.dp), 
                                    tint = Color.Gray.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "No one is home.", 
                                    style = MaterialTheme.typography.titleMedium, 
                                    color = Color.Gray
                                )
                                Text(
                                    "This folder is empty or no files match.", 
                                    style = MaterialTheme.typography.bodySmall, 
                                    color = Color.Gray.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            var isDraggingScroll by remember { mutableStateOf(false) }
                            
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), 
                                state = listState
                            ) {
                                items(viewModel.explorerFiles) { file ->
                                FileRow(
                                    file = file, 
                                    isHighlighted = selectedFiles.contains(file) || file.path == viewModel.highlightedPath,
                                    isPinned = viewModel.pinnedPaths.contains(file.path),
                                    onClick = {
                                        if (selectedFiles.isNotEmpty()) viewModel.toggleSelection(file) 
                                        else viewModel.onFileClicked(file, context, loadFolder)
                                    },
                                    onLongClick = { viewModel.toggleSelection(file) }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }

                        // Explorer Fast-Scroll Handle
                        if (viewModel.explorerFiles.size > 20) {
                                BoxWithConstraints(modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(40.dp)) {
                                    var showScrollbar by remember { mutableStateOf(false) }
                                    var dragScrollPercent by remember { mutableFloatStateOf(0f) }
                                    
                                    LaunchedEffect(listState.isScrollInProgress, isDraggingScroll) {
                                        if (listState.isScrollInProgress || isDraggingScroll) {
                                            showScrollbar = true
                                        } else {
                                            kotlinx.coroutines.delay(1500)
                                            showScrollbar = false
                                        }
                                    }
                                    val scrollAlpha by animateFloatAsState(if (showScrollbar) 1f else 0f, label = "")
                                    val totalItems = viewModel.explorerFiles.size
                                    val maxScrollIndex = (totalItems - listState.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
                                    val listScrollPercent = (listState.firstVisibleItemIndex.toFloat() / maxScrollIndex.toFloat()).coerceIn(0f, 1f)
                                    val currentScrollPercent = if (isDraggingScroll) dragScrollPercent else listScrollPercent

                                    Box(
                                        modifier = Modifier
                                            .offset(y = (maxHeight - 60.dp) * currentScrollPercent)
                                            .width(32.dp)
                                            .height(60.dp)
                                            .pointerInput(maxHeight, totalItems) {
                                                detectDragGestures(
                                                    onDragStart = { 
                                                        isDraggingScroll = true 
                                                        val currentMax = (viewModel.explorerFiles.size - listState.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
                                                        dragScrollPercent = (listState.firstVisibleItemIndex.toFloat() / currentMax.toFloat()).coerceIn(0f, 1f)
                                                    },
                                                    onDragEnd = { isDraggingScroll = false },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val delta = dragAmount.y / (maxHeight.toPx() - 60.dp.toPx())
                                                        dragScrollPercent = (dragScrollPercent + delta).coerceIn(0f, 1f)
                                                        val currentMax = (viewModel.explorerFiles.size - listState.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
                                                        val targetIndex = (dragScrollPercent * currentMax).toInt().coerceIn(0, viewModel.explorerFiles.size - 1)
                                                        scope.launch { listState.scrollToItem(targetIndex) }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(8.dp)
                                                .fillMaxHeight()
                                                .padding(end = 2.dp)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = scrollAlpha), RoundedCornerShape(4.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showExplorerCreateDialog = false },
                title = { Text("Create New ${createType}") },
                text = {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            FilterChip(
                                selected = createType == "File",
                                onClick = { createType = "File" },
                                label = { Text("File") }
                            )
                            FilterChip(
                                selected = createType == "Folder",
                                onClick = { createType = "Folder" },
                                label = { Text("Folder") }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        TextField(
                            value = newItemName,
                            onValueChange = { newItemName = it },
                            placeholder = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (createType == "File") {
                            Spacer(modifier = Modifier.height(8.dp))
                            val exts = listOf(".txt", ".json", ".js", ".html", ".py")
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                exts.forEach { ext ->
                                    AssistChip(
                                        onClick = { 
                                            if (!newItemName.endsWith(ext)) newItemName += ext 
                                        },
                                        label = { Text(ext) },
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newItemName.isNotBlank()) {
                            viewModel.createFileOrFolder(
                                context = context,
                                name = newItemName,
                                isFolder = createType == "Folder",
                                onComplete = { loadFolder(viewModel.explorerPath) }
                            )
                            newItemName = ""
                            viewModel.showExplorerCreateDialog = false
                        }
                    }) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showExplorerCreateDialog = false; newItemName = "" }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Floating Options Bar
        androidx.compose.animation.AnimatedVisibility(
            visible = selectedFiles.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) {
            Surface(
                color = Color(0xFF161B22),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedFiles.size}",
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    val hasZip = selectedFiles.any { it.path.endsWith(".zip") || it.path.contains("!/") }
                    if (hasZip) {
                        IconButton(onClick = { viewModel.prepareExtraction() }) {
                            Icon(Icons.Default.Unarchive, "Extract", tint = Color(0xFF69F0AE))
                        }
                    }
                    val allPinned = selectedFiles.isNotEmpty() && selectedFiles.all { it.path in viewModel.pinnedPaths }
                    IconButton(onClick = { viewModel.togglePinForSelected(context) }) {
                        Icon(Icons.Default.PushPin, "Pin", tint = if (allPinned) Color(0xFFFFD740) else Color.White)
                    }
                    IconButton(onClick = { 
                        selectedFiles.forEach { viewModel.askAiAboutFile(java.io.File(it.path)) }
                        viewModel.clearSelection()
                    }) {
                        Icon(Icons.Default.AutoAwesome, "Consult AI", tint = Color(0xFF00E5FF))
                    }
                    IconButton(onClick = { showPropertyDialog = true }) {
                        Icon(Icons.Default.Info, "Properties", tint = Color.Gray)
                    }
                    var showNukeConfirm by remember { mutableStateOf(false) }
                    if (showNukeConfirm) {
                        val firstFile = selectedFiles.firstOrNull()
                        AlertDialog(
                            onDismissRequest = { showNukeConfirm = false },
                            title = { Text("Nuke this?") },
                            text = { Text("This will delete ${if (selectedFiles.size > 1) "${selectedFiles.size} items" else firstFile?.name} forever. There is no trash bin here.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.deleteSelectedFiles(context) { loadFolder(viewModel.explorerPath) }
                                    showNukeConfirm = false
                                }) { Text("Nuke ${selectedFiles.size} items", color = Color.Red) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNukeConfirm = false }) { Text("Save it") }
                            }
                        )
                    }
                    IconButton(onClick = { showNukeConfirm = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = Color.Red.copy(alpha = 0.7f))
                    }
                    if (selectedFiles.size == 1) {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Default.Edit, "Rename", tint = Color.Gray)
                        }
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More", tint = Color.White)
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Select All") },
                                onClick = {
                                    viewModel.selectAll()
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") }, 
                                onClick = {
                                    showMoreMenu = false
                                    selectedFiles.firstOrNull()?.let {
                                        val f = java.io.File(it.path)
                                        val intent = Intent(Intent.ACTION_SEND)
                                        intent.type = "*/*"
                                        intent.putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", f))
                                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(Intent.createChooser(intent, "Share File"))
                                    }
                                }
                            )
                            DropdownMenuItem(text = { Text("Favourite") }, onClick = { showMoreMenu = false })
                        }
                    }
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                }
            }
        }

        if (showRenameDialog && selectedFiles.size == 1) {
            val fileToRename = selectedFiles.first()
            var name by remember { mutableStateOf(fileToRename.name) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename") },
                text = { TextField(value = name, onValueChange = { name = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameFile(context, fileToRename.path, name) { loadFolder(viewModel.explorerPath) }
                        showRenameDialog = false
                        viewModel.clearSelection()
                    }) { Text("Rename") }
                }
            )
        }

        // Sticky Unarchive Bar
        androidx.compose.animation.AnimatedVisibility(
            visible = viewModel.isExtractionMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) {
            Surface(
                color = Color(0xFF1F2937),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 12.dp,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF69F0AE).copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Destination?", 
                        color = Color.White, 
                        style = MaterialTheme.typography.labelLarge, 
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.cancelExtraction() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Cancel", color = Color.Gray)
                    }
                    Button(
                        onClick = { viewModel.performExtraction(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF69F0AE))
                    ) {
                        Text("Extract", color = Color.Black)
                    }
                }
            }
        }

        if (showPropertyDialog && selectedFiles.isNotEmpty()) {
            val f = java.io.File(selectedFiles.first().path)
            AlertDialog(
                onDismissRequest = { showPropertyDialog = false },
                title = { Text("Properties") },
                text = {
                    Column {
                        Text("Name: ${f.name}", style = MaterialTheme.typography.bodyMedium)
                        Text("Path: ${f.absolutePath}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        val sizeStr = if (f.isDirectory) "--" else try { 
                            val python = com.chaquo.python.Python.getInstance().getModule("os")
                            // Using a quick logic here since FileScanner is an object
                            val bytes = f.length()
                            if (bytes <= 0) "0 B" else {
                                val units = arrayOf("B", "KB", "MB", "GB", "TB")
                                val i = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
                                String.format("%.1f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
                            }
                        } catch(e: Exception) { "Unknown" }
                        Text("Size: $sizeStr")
                        Text("Modified: ${java.util.Date(f.lastModified())}")
                        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Path", f.absolutePath))
                                    android.widget.Toast.makeText(context, "Path copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Copy Path", fontSize = 12.sp) }
                            
                            Button(
                                onClick = {
                                    val parentPath = f.parent ?: "/storage/emulated/0"
                                    viewModel.highlightedPath = f.absolutePath
                                    viewModel.explorerCategory = "Internal Storage"
                                    loadFolder(parentPath)
                                    showPropertyDialog = false
                                    viewModel.clearSelection()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                            ) { Text("Open Location", color = Color.Black, fontSize = 12.sp) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { 
                    showPropertyDialog = false
                    viewModel.clearSelection()
                }) { Text("OK") } }
            )
        }
    }
}

@Composable
fun StorageUsageCard(label: String, percent: Float, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Box(modifier = Modifier.background(Brush.linearGradient(listOf(Color(0xFF1F2937), Color(0xFF111827)))).padding(24.dp)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Used Space", color = Color.Gray, fontSize = 12.sp)
                        Text(label, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Icon(Icons.Default.Memory, null, tint = Color(0xFF00E5FF))
                }
                Spacer(modifier = Modifier.height(15.dp))
                Box(modifier = Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF0D1117), CircleShape)) {
                    Box(modifier = Modifier.fillMaxWidth(percent.coerceIn(0f, 1f)).fillMaxHeight().background(Color(0xFF00E5FF), CircleShape))
                }
            }
        }
    }
}

@Composable
fun CategoryGrid(onCatClick: (String) -> Unit) {
    val cats = listOf(
        Cat("Images", Icons.Default.Image, Color(0xFFFF5252)),
        Cat("Videos", Icons.Default.Videocam, Color(0xFF7C4DFF)),
        Cat("Audio", Icons.Default.MusicNote, Color(0xFF00E5FF)),
        Cat("Docs", Icons.Default.Description, Color(0xFFFFD740)),
        Cat("Downloads", Icons.Default.Download, Color(0xFF69F0AE)),
        Cat("Zips", Icons.Default.Archive, Color(0xFFFF4081))
    )
    
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            cats.take(3).forEach { CategoryItem(it, onCatClick) }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            cats.takeLast(3).forEach { CategoryItem(it, onCatClick) }
        }
    }
}

@Composable
fun CategoryItem(cat: Cat, onClick: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick(cat.name) }) {
        Box(
            modifier = Modifier.size(48.dp).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(cat.icon, null, tint = cat.color, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(cat.name, color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun ExpandingSearch(isExpanded: Boolean, query: String, onQueryChange: (String) -> Unit, onToggle: () -> Unit, onSearch: () -> Unit) {
    val width by animateDpAsState(targetValue = if (isExpanded) 220.dp else 45.dp, animationSpec = spring(stiffness = Spring.StiffnessLow))
    
    Surface(
        modifier = Modifier.width(width).height(45.dp),
        shape = RoundedCornerShape(22.dp),
        color = if (isExpanded) Color(0xFF00E5FF).copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isExpanded) Color(0xFF00E5FF) else Color.Transparent)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(
                Icons.Default.Search, 
                null, 
                tint = Color(0xFF00E5FF), 
                modifier = Modifier.size(20.dp).clickable { onToggle() }
            )
            if (isExpanded) {
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() })
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileRow(file: FileItem, isHighlighted: Boolean = false, isPinned: Boolean = false, onClick: () -> Unit = {}, onLongClick: () -> Unit = {}) {
    val context = LocalContext.current
    var mediaThumbnail by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val isApk = ext == "apk"
    val isImg = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp").contains(ext)
    val isVid = listOf("mp4", "mkv", "avi", "mov", "webm").contains(ext)

    if ((isApk || isImg || isVid) && mediaThumbnail == null) {
        LaunchedEffect(file.path) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bitmap: android.graphics.Bitmap? = when {
                        isApk -> {
                            val pm = context.packageManager
                            pm.getPackageArchiveInfo(file.path, 0)?.let { pi ->
                                pi.applicationInfo.sourceDir = file.path
                                pi.applicationInfo.publicSourceDir = file.path
                                val drawable = pi.applicationInfo.loadIcon(pm)
                                if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap
                                else {
                                    val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bmp)
                                    drawable.setBounds(0, 0, 96, 96)
                                    drawable.draw(canvas)
                                    bmp
                                }
                            }
                        }
                        isImg -> {
                            val options = android.graphics.BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            android.graphics.BitmapFactory.decodeFile(file.path, options)
                            options.inSampleSize = 4 // Heavily downsample for icons
                            options.inJustDecodeBounds = false
                            android.graphics.BitmapFactory.decodeFile(file.path, options)
                        }
                        isVid -> {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                try { 
                                    context.contentResolver.loadThumbnail(android.net.Uri.fromFile(java.io.File(file.path)), android.util.Size(96, 96), null)
                                } catch(e: Exception) {
                                    android.media.ThumbnailUtils.createVideoThumbnail(file.path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                                }
                            } else {
                                android.media.ThumbnailUtils.createVideoThumbnail(file.path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
                            }
                        }
                        else -> null
                    }

                    bitmap?.let {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(it, 96, 96, true)
                        val imageBitmap = scaled.asImageBitmap()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            mediaThumbnail = imageBitmap
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .then(
                if (isHighlighted) Modifier.border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp))
                else Modifier
            )
            .background(
                if (isHighlighted) Color(0xFF00E5FF).copy(alpha = 0.1f) 
                else Color.White.copy(alpha = 0.03f), 
                RoundedCornerShape(8.dp)
            )
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClick() }
            )
            .padding(vertical = 6.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (mediaThumbnail != null) {
            Image(
                bitmap = mediaThumbnail!!,
                contentDescription = "Thumbnail",
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(file.icon, null, tint = file.color, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name, 
                color = Color.White, 
                fontSize = 14.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = file.meta, 
                color = Color.Gray, 
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (isPinned) {
            Icon(Icons.Default.PushPin, "Pinned", tint = Color(0xFFFFD740), modifier = Modifier.size(16.dp))
        }
    }
}

// Helper Models relocated to Models.kt