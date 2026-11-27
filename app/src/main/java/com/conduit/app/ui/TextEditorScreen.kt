package com.conduit.nexus

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LineSearchMatch(
    val lineIndex: Int,
    val startCol: Int,
    val endCol: Int
)

private class EditorSearchSpan(color: Int) : BackgroundColorSpan(color)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(viewModel: ConduitViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val hasChanges = viewModel.hasUnsavedChanges

    val fileExt = remember(viewModel.editingFile?.absolutePath) {
        viewModel.editingFile?.extension?.lowercase() ?: ""
    }
    val isHtmlFile = fileExt == "html" || fileExt == "htm"
    val isPythonFile = fileExt == "py"

    var isPreviewMode by remember(viewModel.editingFile?.absolutePath) { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showOutputSheet by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // Search State
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }

    // Fast Scroll & Edit State
    var scrollPercent by remember { mutableFloatStateOf(0f) }
    var isScrolling by remember { mutableStateOf(false) }
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    var liveEditText by remember(viewModel.editingFile?.absolutePath) {
        mutableStateOf(viewModel.editorText)
    }
    LaunchedEffect(viewModel.editorText) {
        if (!hasChanges) {
            liveEditText = viewModel.editorText
        }
    }

    var isUpdatingSpans by remember { mutableStateOf(false) }
    val scrollbarAlpha by animateFloatAsState(if (isScrolling) 1f else 0.35f, label = "")

    val readListState = rememberLazyListState()

    val readMatches = remember(viewModel.editorLines, searchQuery, showSearchBar, viewModel.isEditLocked, isPreviewMode) {
        if (isPreviewMode || !showSearchBar || searchQuery.isEmpty() || !viewModel.isEditLocked) {
            emptyList()
        } else {
            val results = mutableListOf<LineSearchMatch>()
            val qLen = searchQuery.length
            for (lineIdx in viewModel.editorLines.indices) {
                val line = viewModel.editorLines[lineIdx]
                var startIdx = 0
                while (startIdx <= line.length - qLen) {
                    val found = line.indexOf(searchQuery, startIndex = startIdx, ignoreCase = true)
                    if (found == -1) break
                    results.add(LineSearchMatch(lineIdx, found, found + qLen))
                    startIdx = found + qLen
                    if (results.size >= 2000) return@remember results
                }
            }
            results
        }
    }

    val editMatches = remember(liveEditText, searchQuery, showSearchBar, viewModel.isEditLocked, isPreviewMode) {
        if (isPreviewMode || !showSearchBar || searchQuery.isEmpty() || viewModel.isEditLocked) {
            emptyList()
        } else {
            val results = mutableListOf<IntRange>()
            val qLen = searchQuery.length
            var startIdx = 0
            while (startIdx <= liveEditText.length - qLen) {
                val found = liveEditText.indexOf(searchQuery, startIndex = startIdx, ignoreCase = true)
                if (found == -1) break
                results.add(found until (found + qLen))
                startIdx = found + qLen
                if (results.size >= 2000) break
            }
            results
        }
    }

    val totalMatches = if (viewModel.isEditLocked) readMatches.size else editMatches.size

    LaunchedEffect(searchQuery, totalMatches, viewModel.isEditLocked) {
        currentMatchIndex = 0
    }

    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            kotlinx.coroutines.delay(100)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // Apply highlights & scroll in EditText when searching in Edit Mode
    LaunchedEffect(editTextRef, editMatches, currentMatchIndex, showSearchBar, viewModel.isEditLocked, isPreviewMode) {
        if (isPreviewMode) return@LaunchedEffect
        val et = editTextRef ?: return@LaunchedEffect
        val editable = et.text ?: return@LaunchedEffect
        isUpdatingSpans = true
        try {
            val oldSpans = editable.getSpans(0, editable.length, EditorSearchSpan::class.java)
            oldSpans.forEach { editable.removeSpan(it) }

            if (showSearchBar && !viewModel.isEditLocked && editMatches.isNotEmpty()) {
                val safeIdx = currentMatchIndex.coerceIn(0, editMatches.lastIndex)
                editMatches.forEachIndexed { idx, range ->
                    if (range.first >= 0 && range.last + 1 <= editable.length) {
                        val color = if (idx == safeIdx) 0xCCFF9800.toInt() else 0x5500E5FF
                        editable.setSpan(
                            EditorSearchSpan(color),
                            range.first,
                            range.last + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                val activeRange = editMatches[safeIdx]
                et.layout?.let { layout ->
                    val line = layout.getLineForOffset(activeRange.first.coerceAtMost(editable.length))
                    val y = layout.getLineTop(line)
                    et.scrollTo(0, (y - et.height / 3).coerceAtLeast(0))
                }
            }
        } finally {
            isUpdatingSpans = false
        }
    }

    // Scroll Read Mode list when active match changes
    LaunchedEffect(currentMatchIndex, readMatches, showSearchBar, viewModel.isEditLocked, isPreviewMode) {
        if (!isPreviewMode && showSearchBar && viewModel.isEditLocked && readMatches.isNotEmpty()) {
            val safeIdx = currentMatchIndex.coerceIn(0, readMatches.lastIndex)
            readListState.animateScrollToItem(readMatches[safeIdx].lineIndex)
        }
    }

    fun jumpNextMatch() {
        if (totalMatches == 0) return
        currentMatchIndex = (currentMatchIndex + 1) % totalMatches
    }

    fun jumpPrevMatch() {
        if (totalMatches == 0) return
        currentMatchIndex = if (currentMatchIndex - 1 < 0) totalMatches - 1 else currentMatchIndex - 1
    }

    BackHandler {
        when {
            isPreviewMode -> isPreviewMode = false
            showSearchBar -> {
                showSearchBar = false
                searchQuery = ""
            }
            hasChanges -> showExitConfirm = true
            else -> onBack()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = viewModel.editingFile?.name ?: "Editor",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val modeLabel = when {
                        isPreviewMode -> "HTML Preview"
                        viewModel.isEditLocked -> "Read Only"
                        hasChanges -> "Editing • Unsaved"
                        else -> "Editing"
                    }
                    val modeColor = when {
                        isPreviewMode -> Color(0xFF69F0AE)
                        viewModel.isEditLocked -> Color(0xFF00E5FF)
                        else -> Color(0xFFFFD740)
                    }
                    Text(
                        text = "${viewModel.editorLines.size} lines • $modeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = modeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            windowInsets = WindowInsets(0.dp),
            navigationIcon = {
                IconButton(onClick = {
                    if (hasChanges) showExitConfirm = true else onBack()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            },
            actions = {
                if (isHtmlFile) {
                    IconButton(onClick = {
                        if (!isPreviewMode && !viewModel.isEditLocked) {
                            editTextRef?.text?.toString()?.let { liveEditText = it }
                        }
                        isPreviewMode = !isPreviewMode
                        if (isPreviewMode) {
                            showSearchBar = false
                        }
                    }) {
                        Icon(
                            imageVector = if (isPreviewMode) Icons.Default.Code else Icons.Default.Visibility,
                            contentDescription = if (isPreviewMode) "Show Code" else "Preview HTML",
                            tint = if (isPreviewMode) Color(0xFF69F0AE) else Color(0xFF00E5FF)
                        )
                    }
                } else if (isPythonFile && !isPreviewMode) {
                    IconButton(onClick = {
                        val currentText = if (!viewModel.isEditLocked) {
                            editTextRef?.text?.toString() ?: liveEditText
                        } else {
                            viewModel.editorText
                        }
                        viewModel.saveCurrentFile(currentText)
                        scope.launch(Dispatchers.IO) {
                            val python = com.chaquo.python.Python.getInstance()
                            val executor = python.getModule("executor")
                            val result = executor.callAttr("run_code", currentText).toString()
                            withContext(Dispatchers.Main) {
                                viewModel.executionResult = result
                                showOutputSheet = true
                            }
                        }
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color(0xFF69F0AE))
                    }
                }

                if (!isPreviewMode) {
                    IconButton(onClick = {
                        showSearchBar = !showSearchBar
                        if (!showSearchBar) searchQuery = ""
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search in file",
                            tint = if (showSearchBar) Color(0xFF00E5FF) else Color.Gray
                        )
                    }

                    if (!viewModel.isEditLocked && hasChanges) {
                        Button(
                            onClick = {
                                val currentText = editTextRef?.text?.toString() ?: liveEditText
                                liveEditText = currentText
                                viewModel.saveCurrentFile(currentText)
                                Toast.makeText(context, "File Saved", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    IconButton(onClick = {
                        if (viewModel.isEditLocked) {
                            liveEditText = viewModel.editorText
                            viewModel.isEditLocked = false
                        } else {
                            val currentText = editTextRef?.text?.toString() ?: liveEditText
                            liveEditText = currentText
                            viewModel.saveCurrentFile(currentText)
                        }
                    }) {
                        Icon(
                            if (viewModel.isEditLocked) Icons.Default.Edit else Icons.Default.Lock,
                            contentDescription = if (viewModel.isEditLocked) "Edit" else "Lock",
                            tint = if (viewModel.isEditLocked) Color.Gray else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy All") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showOverflowMenu = false
                                val currentText = if (!viewModel.isEditLocked) {
                                    editTextRef?.text?.toString() ?: liveEditText
                                } else {
                                    viewModel.editorText
                                }
                                val clip = android.content.ClipData.newPlainText("File Content", currentText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (!isPreviewMode && !viewModel.isEditLocked) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                            DropdownMenuItem(
                                text = { Text("Paste") },
                                leadingIcon = { Icon(Icons.Default.ContentPaste, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showOverflowMenu = false
                                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clip.isNotEmpty()) {
                                        editTextRef?.let { et ->
                                            val start = et.selectionStart.coerceAtLeast(0)
                                            val end = et.selectionEnd.coerceAtLeast(0)
                                            et.text.replace(Math.min(start, end), Math.max(start, end), clip)
                                            liveEditText = et.text.toString()
                                            viewModel.hasUnsavedChanges = true
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear All", color = Color(0xFFFF5252)) },
                                leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showOverflowMenu = false
                                    editTextRef?.setText("")
                                    liveEditText = ""
                                    viewModel.hasUnsavedChanges = true
                                }
                            )
                        }
                    }
                }
            }
        )

        // Find / Navigation Bar
        AnimatedVisibility(
            visible = showSearchBar && !isPreviewMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .border(0.5.dp, Color(0xFF00E5FF).copy(alpha = 0.25f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (searchQuery.isEmpty()) {
                        Text("Find in file...", color = Color.Gray, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        singleLine = true,
                        cursorBrush = SolidColor(Color(0xFF00E5FF)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { jumpNextMatch() })
                    )
                }

                if (searchQuery.isNotEmpty()) {
                    val badgeText = if (totalMatches == 0) "0/0" else "${currentMatchIndex + 1}/$totalMatches"
                    Text(
                        text = badgeText,
                        color = if (totalMatches == 0) Color(0xFFFF5252) else Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }

                IconButton(
                    onClick = { jumpPrevMatch() },
                    enabled = totalMatches > 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = "Previous match",
                        tint = if (totalMatches > 0) Color.White else Color.Gray.copy(alpha = 0.4f)
                    )
                }
                IconButton(
                    onClick = { jumpNextMatch() },
                    enabled = totalMatches > 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "Next match",
                        tint = if (totalMatches > 0) Color.White else Color.Gray.copy(alpha = 0.4f)
                    )
                }
                IconButton(
                    onClick = {
                        showSearchBar = false
                        searchQuery = ""
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Content Area: HTML Preview / Read-Only / Edit Mode
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            if (isPreviewMode && isHtmlFile) {
                val htmlContent = if (!viewModel.isEditLocked) liveEditText else viewModel.editorText
                val baseUrl = remember(viewModel.editingFile?.parentFile?.absolutePath) {
                    viewModel.editingFile?.parentFile?.let { "file://${it.absolutePath}/" }
                }
                var lastLoadedHtml by remember { mutableStateOf<String?>(null) }

                AndroidView(
                    modifier = Modifier.fillMaxSize().background(Color.White),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                        }
                    },
                    update = { webView ->
                        if (lastLoadedHtml != htmlContent) {
                            lastLoadedHtml = htmlContent
                            webView.loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", null)
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    }
                )
            } else if (viewModel.isEditLocked) {
                // === ENGINE 1: VIRTUALIZED READ-ONLY MONOSPACE VIEWER ===
                val totalLines = viewModel.editorLines.size
                val gutterWidth = remember(totalLines) {
                    (totalLines.toString().length * 9 + 20).dp.coerceAtLeast(42.dp)
                }

                val isListScrolling by remember { derivedStateOf { readListState.isScrollInProgress } }
                var isDraggingListThumb by remember { mutableStateOf(false) }
                val listScrollAlpha by animateFloatAsState(if (isListScrolling || isDraggingListThumb) 1f else 0.35f, label = "")

                val currentListPercent by remember {
                    derivedStateOf {
                        val firstVisible = readListState.firstVisibleItemIndex
                        val visibleCount = readListState.layoutInfo.visibleItemsInfo.size
                        val maxScroll = (totalLines - visibleCount).coerceAtLeast(1)
                        (firstVisible.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
                    }
                }

                val activeReadMatch = if (showSearchBar && readMatches.isNotEmpty()) {
                    readMatches[currentMatchIndex.coerceIn(0, readMatches.lastIndex)]
                } else null

                val matchesByLine = remember(readMatches) {
                    readMatches.groupBy { it.lineIndex }
                }

                SelectionContainer {
                    LazyColumn(
                        state = readListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = viewModel.editorLines,
                            key = { index, _ -> index }
                        ) { index, line ->
                            val lineMatches = matchesByLine[index]
                            val renderedLine = if (lineMatches.isNullOrEmpty()) {
                                AnnotatedString(line.ifEmpty { " " })
                            } else {
                                buildAnnotatedString {
                                    var cursor = 0
                                    for (m in lineMatches) {
                                        if (m.startCol > cursor) {
                                            append(line.substring(cursor, m.startCol))
                                        }
                                        val isCurrent = activeReadMatch == m
                                        pushStyle(
                                            SpanStyle(
                                                background = if (isCurrent) Color(0xFFFF9800) else Color(0xFF00E5FF).copy(alpha = 0.32f),
                                                color = if (isCurrent) Color.Black else Color.White,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                            )
                                        )
                                        append(line.substring(m.startCol, m.endCol))
                                        pop()
                                        cursor = m.endCol
                                    }
                                    if (cursor < line.length) {
                                        append(line.substring(cursor))
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = if (activeReadMatch?.lineIndex == index) Color(0xFFFF9800) else Color.Gray.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier
                                        .width(gutterWidth)
                                        .padding(end = 12.dp)
                                )
                                Text(
                                    text = renderedLine,
                                    color = Color(0xFFD4D4D4),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Fast-Scroll Scrub Bar for Read Mode
                if (totalLines > 30) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(40.dp)
                            .align(Alignment.CenterEnd)
                    ) {
                        val constraintsHeight = maxHeight
                        Box(
                            modifier = Modifier
                                .offset(y = (constraintsHeight - 60.dp) * currentListPercent)
                                .width(32.dp)
                                .height(60.dp)
                                .pointerInput(totalLines) {
                                    detectDragGestures(
                                        onDragStart = { isDraggingListThumb = true },
                                        onDragEnd = { isDraggingListThumb = false },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val delta = dragAmount.y / (constraintsHeight.toPx() - 60.dp.toPx())
                                            val newPercent = (currentListPercent + delta).coerceIn(0f, 1f)
                                            val targetIndex = (newPercent * (totalLines - 1)).toInt().coerceIn(0, totalLines - 1)
                                            scope.launch { readListState.scrollToItem(targetIndex) }
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
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = listScrollAlpha),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                            )
                        }
                    }
                }
            } else {
                // === ENGINE 2: NATIVE FLING-SCROLLER EDIT TEXT ===
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    factory = { ctx ->
                        EditText(ctx).apply {
                            editTextRef = this
                            inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            gravity = Gravity.TOP
                            typeface = Typeface.MONOSPACE
                            textSize = 14f
                            setTextColor(android.graphics.Color.WHITE)
                            background = null
                            setVerticalScrollBarEnabled(true)

                            val overScroller = android.widget.OverScroller(ctx)
                            var flingRunnable: Runnable? = null
                            val gestureDetector = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                                    val et = editTextRef ?: return false
                                    val layoutHeight = et.layout?.height ?: (et.lineCount * et.lineHeight)
                                    val maxY = Math.max(0, layoutHeight + et.paddingTop + et.paddingBottom - et.height)
                                    overScroller.fling(0, et.scrollY, 0, -velocityY.toInt(), 0, 0, 0, maxY, 0, 0)

                                    flingRunnable?.let { et.removeCallbacks(it) }
                                    flingRunnable = object : Runnable {
                                        override fun run() {
                                            if (overScroller.computeScrollOffset()) {
                                                et.scrollTo(0, overScroller.currY)
                                                et.postOnAnimation(this)
                                            }
                                        }
                                    }
                                    et.postOnAnimation(flingRunnable)
                                    return true
                                }
                            })

                            setOnTouchListener { _, event ->
                                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                    overScroller.forceFinished(true)
                                    flingRunnable?.let { removeCallbacks(it) }
                                }
                                gestureDetector.onTouchEvent(event)
                                false
                            }

                            var isInitializing = true
                            setText(liveEditText)
                            isInitializing = false

                            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                                isScrolling = true
                                val layoutHeight = layout?.height ?: (lineCount * lineHeight)
                                val totalScrollRange = layoutHeight + paddingTop + paddingBottom - height
                                if (totalScrollRange > 0) {
                                    scrollPercent = (scrollY.toFloat() / totalScrollRange.toFloat()).coerceIn(0f, 1f)
                                }
                            }

                            addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                override fun afterTextChanged(s: android.text.Editable?) {
                                    if (!isInitializing && !isUpdatingSpans) {
                                        liveEditText = s?.toString() ?: ""
                                        viewModel.hasUnsavedChanges = true
                                    }
                                }
                            })
                        }
                    }
                )

                // Fast Scroll Handle for Edit Mode
                BoxWithConstraints(modifier = Modifier.fillMaxHeight().width(40.dp).align(Alignment.CenterEnd)) {
                    val constraintsHeight = maxHeight
                    var isDraggingThumb by remember { mutableStateOf(false) }

                    LaunchedEffect(scrollPercent, isDraggingThumb) {
                        isScrolling = true
                        if (!isDraggingThumb) {
                            kotlinx.coroutines.delay(1500)
                            isScrolling = false
                        }
                    }

                    Box(
                        modifier = Modifier
                            .offset(y = (constraintsHeight - 60.dp) * scrollPercent)
                            .width(32.dp)
                            .height(60.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        isDraggingThumb = true
                                        isScrolling = true
                                    },
                                    onDragEnd = { isDraggingThumb = false },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val delta = dragAmount.y / (constraintsHeight.toPx() - 60.dp.toPx())
                                        scrollPercent = (scrollPercent + delta).coerceIn(0f, 1f)

                                        editTextRef?.let { view ->
                                            val layoutHeight = view.layout?.height ?: (view.lineCount * view.lineHeight)
                                            val totalScrollRange = layoutHeight + view.paddingTop + view.paddingBottom - view.height
                                            if (totalScrollRange > 0) {
                                                view.scrollTo(0, (scrollPercent * totalScrollRange).toInt())
                                            }
                                        }
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
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = scrollbarAlpha),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                    }
                }
            }
        }

        if (showOutputSheet) {
            ModalBottomSheet(onDismissRequest = { showOutputSheet = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Console Output", fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color.Black,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    ) {
                        Text(
                            text = viewModel.executionResult ?: "No output",
                            color = if (viewModel.executionResult?.contains("Traceback") == true) Color.Red else Color.Green,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("Unsaved Changes") },
                text = { Text("You've got unsaved work. You want to dump it and leave, or stay and finish?") },
                confirmButton = {
                    TextButton(onClick = { showExitConfirm = false; onBack() }) {
                        Text("Discard", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirm = false }) {
                        Text("Stay")
                    }
                }
            )
        }
    }
}