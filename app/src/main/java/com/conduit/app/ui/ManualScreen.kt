package com.conduit.nexus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PYTHON_SYNTAX_REGEX = Regex(
    """(\"\"\"[\s\S]*?\"\"\"|'''[\s\S]*?'''|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|#[^\n]*|\b(?:def|class|import|from|as|return|if|elif|else|for|while|in|not|and|or|is|try|except|finally|with|pass|break|continue|True|False|None|lambda|yield|async|await|global|nonlocal|raise|assert|del)\b|\b(?:print|input|len|range|int|str|float|bool|list|dict|set|tuple|open|enumerate|zip|map|filter|sum|min|max|abs|round|sorted|type|isinstance|dir|repr)\b|\b\d+(?:\.\d+)?\b)"""
)

private val PYTHON_KEYWORDS = setOf(
    "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
    "in", "not", "and", "or", "is", "try", "except", "finally", "with", "pass", "break",
    "continue", "True", "False", "None", "lambda", "yield", "async", "await", "global",
    "nonlocal", "raise", "assert", "del"
)

private val PYTHON_BUILTINS = setOf(
    "print", "input", "len", "range", "int", "str", "float", "bool", "list", "dict", "set",
    "tuple", "open", "enumerate", "zip", "map", "filter", "sum", "min", "max", "abs",
    "round", "sorted", "type", "isinstance", "dir", "repr"
)

private class PythonVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val highlighted = buildAnnotatedString {
            var last = 0
            for (match in PYTHON_SYNTAX_REGEX.findAll(raw)) {
                if (match.range.first > last) {
                    append(raw.substring(last, match.range.first))
                }
                val token = match.value
                val color = when {
                    token.startsWith("#") -> Color(0xFF6A9955)
                    token.startsWith("\"") || token.startsWith("'") -> Color(0xFFCE9178)
                    token in PYTHON_KEYWORDS -> Color(0xFFC586C0)
                    token in PYTHON_BUILTINS -> Color(0xFF4FC1FF)
                    token[0].isDigit() -> Color(0xFFB5CEA8)
                    else -> Color(0xFFD4D4D4)
                }
                pushStyle(SpanStyle(color = color))
                append(token)
                pop()
                last = match.range.last + 1
            }
            if (last < raw.length) {
                append(raw.substring(last))
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

private fun applyPythonAutoIndent(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue {
    val oldText = oldValue.text
    val newText = newValue.text
    val cursor = newValue.selection.start
    if (!newValue.selection.collapsed || newText.length != oldText.length + 1 || cursor <= 0) {
        return newValue
    }
    if (newText[cursor - 1] != '\n') {
        return newValue
    }

    val lineStart = newText.lastIndexOf('\n', startIndex = cursor - 2).let { if (it == -1) 0 else it + 1 }
    val prevLine = newText.substring(lineStart, cursor - 1)
    val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
    val codeOnly = prevLine.substringBefore("#").trimEnd()
    val extraIndent = if (codeOnly.endsWith(":")) "    " else ""
    val indent = baseIndent + extraIndent
    if (indent.isEmpty()) return newValue

    val updatedText = newText.substring(0, cursor) + indent + newText.substring(cursor)
    return TextFieldValue(
        text = updatedText,
        selection = TextRange(cursor + indent.length)
    )
}

private fun insertSnippetAtCursor(current: TextFieldValue, snippet: String, cursorOffsetFromEnd: Int = 0): TextFieldValue {
    val start = current.selection.min.coerceIn(0, current.text.length)
    val end = current.selection.max.coerceIn(0, current.text.length)
    val updated = current.text.substring(0, start) + snippet + current.text.substring(end)
    val newCursor = (start + snippet.length + cursorOffsetFromEnd).coerceIn(0, updated.length)
    return TextFieldValue(text = updated, selection = TextRange(newCursor))
}

private fun unindentCurrentLine(current: TextFieldValue): TextFieldValue {
    val pos = current.selection.start.coerceIn(0, current.text.length)
    val lineStart = current.text.lastIndexOf('\n', startIndex = (pos - 1).coerceAtLeast(0)).let {
        if (pos == 0 || it == -1) 0 else it + 1
    }
    var removeCount = 0
    while (removeCount < 4 && lineStart + removeCount < current.text.length && current.text[lineStart + removeCount] == ' ') {
        removeCount++
    }
    if (removeCount == 0 && lineStart < current.text.length && current.text[lineStart] == '\t') {
        removeCount = 1
    }
    if (removeCount == 0) return current
    val updated = current.text.removeRange(lineStart, lineStart + removeCount)
    val newPos = (pos - removeCount).coerceAtLeast(lineStart)
    return TextFieldValue(text = updated, selection = TextRange(newPos))
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ManualScreen(viewModel: ConduitViewModel, onRun: () -> Unit) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var scriptToRename by remember { mutableStateOf<SavedScript?>(null) }
    var newTitleName by remember { mutableStateOf("") }
    var drawerSearch by remember { mutableStateOf("") }

    var editorState by remember {
        mutableStateOf(TextFieldValue(viewModel.manualCode, TextRange(viewModel.manualCode.length)))
    }
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    val pythonSyntax = remember { PythonVisualTransformation() }
    val editorFocusRequester = remember { FocusRequester() }
    val inlineStdinFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { viewModel.loadSavedScripts(context) }

    // Sync external changes (loading script, paste, + New, clear)
    LaunchedEffect(viewModel.manualCode) {
        if (editorState.text != viewModel.manualCode) {
            editorState = TextFieldValue(viewModel.manualCode, TextRange(viewModel.manualCode.length))
        }
    }

    // Clear undo history when switching scripts
    LaunchedEffect(viewModel.activeScriptId) {
        undoStack.clear()
        redoStack.clear()
    }

    // Auto-save edits into active script so edits never vanish
    LaunchedEffect(viewModel.manualCode, viewModel.activeScriptId) {
        if (viewModel.activeScriptId != null && viewModel.manualCode.isNotBlank()) {
            delay(700)
            viewModel.saveScript(context, viewModel.manualCode)
        }
    }

    // Auto-focus inline STDIN when Python calls input()
    LaunchedEffect(viewModel.isManualAwaitingInput) {
        if (viewModel.isManualAwaitingInput) {
            delay(80)
            try {
                inlineStdinFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    fun commitEditorChange(next: TextFieldValue, recordHistory: Boolean = true) {
        if (recordHistory && next.text != editorState.text) {
            undoStack.add(editorState)
            if (undoStack.size > 40) undoStack.removeAt(0)
            redoStack.clear()
        }
        editorState = next
        if (viewModel.manualCode != next.text) {
            viewModel.manualCode = next.text
        }
    }

    val filteredScripts = remember(viewModel.savedScripts, drawerSearch) {
        if (drawerSearch.isBlank()) viewModel.savedScripts
        else viewModel.savedScripts.filter {
            it.title.contains(drawerSearch, ignoreCase = true) ||
                it.code.contains(drawerSearch, ignoreCase = true)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.85f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Script Library",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalButton(
                        onClick = {
                            viewModel.createNewScript()
                            scope.launch { drawerState.close() }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New", fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = drawerSearch,
                    onValueChange = { drawerSearch = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("Search scripts...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (drawerSearch.isNotEmpty()) {
                            IconButton(onClick = { drawerSearch = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

                if (filteredScripts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (drawerSearch.isBlank()) "No saved scripts yet" else "No matching scripts",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        items(filteredScripts, key = { it.id }) { script ->
                            val isActive = script.id == viewModel.activeScriptId
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        viewModel.deleteScript(context, script.id)
                                        true
                                    } else false
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val color = when (dismissState.dismissDirection) {
                                        SwipeToDismissBoxValue.EndToStart -> Color.Red.copy(alpha = 0.6f)
                                        else -> Color.Transparent
                                    }
                                    Box(
                                        Modifier.fillMaxSize().background(color, RoundedCornerShape(14.dp)).padding(end = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = Color.White)
                                    }
                                },
                                enableDismissFromStartToEnd = false
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isActive) Color(0xFF00E5FF).copy(alpha = 0.14f)
                                            else Color(0xFF161B22)
                                        )
                                        .border(
                                            width = if (isActive) 1.dp else 0.5.dp,
                                            color = if (isActive) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.06f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                viewModel.selectScript(script)
                                                scope.launch { drawerState.close() }
                                            },
                                            onLongClick = {
                                                scriptToRename = script
                                                newTitleName = script.title
                                            }
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        null,
                                        tint = if (isActive) Color(0xFF00E5FF) else Color(0xFF7C4DFF)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = script.title,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isActive) Color(0xFF00E5FF) else Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isActive) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "ACTIVE",
                                                        color = Color(0xFF00E5FF),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = script.code.take(45).replace("\n", " "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val activeTitle = viewModel.savedScripts.find { it.id == viewModel.activeScriptId }?.title ?: "Unsaved Script"

            // Top Action Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.LibraryBooks, contentDescription = "Library", tint = Color(0xFF00E5FF))
                }
                Text(
                    text = activeTitle,
                    color = if (viewModel.activeScriptId != null) Color.White else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { scope.launch { drawerState.open() } }
                )
                IconButton(onClick = {
                    viewModel.createNewScript()
                    Toast.makeText(context, "New script ready", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "New Script", tint = Color(0xFF00E5FF))
                }
                IconButton(onClick = {
                    val clip = ClipData.newPlainText("Python Code", viewModel.manualCode)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Gray)
                }
                IconButton(onClick = {
                    val item = clipboard.primaryClip?.getItemAt(0)
                    val pasteData = item?.text?.toString() ?: ""
                    if (pasteData.isNotEmpty()) {
                        val next = TextFieldValue(pasteData, TextRange(pasteData.length))
                        commitEditorChange(next)
                        viewModel.saveScript(context, pasteData, isPaste = true)
                        Toast.makeText(
                            context,
                            if (viewModel.activeScriptId != null) "Pasted & Updated" else "Pasted & Saved",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Color.Gray)
                }
                IconButton(onClick = {
                    commitEditorChange(TextFieldValue(""))
                }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Code", tint = Color.Red.copy(alpha = 0.7f))
                }
            }

            // Shared-Scroll Python Code Editor with Synchronized Line Numbers
            val editorVScroll = rememberScrollState()
            val editorHScroll = rememberScrollState()
            val lineCount = remember(editorState.text) {
                maxOf(1, editorState.text.count { it == '\n' } + 1)
            }
            val gutterWidth = remember(lineCount) {
                (lineCount.toString().length * 9 + 18).dp.coerceAtLeast(38.dp)
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxWidth()
                    .background(Color(0xFF14181F), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            ) {
                val viewportHeight = maxHeight
                val minCodeWidth = (maxWidth - gutterWidth - 12.dp).coerceAtLeast(200.dp)

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(editorVScroll)
                ) {
                    // Synchronized Line Number Gutter
                    Column(
                        modifier = Modifier
                            .width(gutterWidth)
                            .defaultMinSize(minHeight = viewportHeight)
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        for (i in 1..lineCount) {
                            Text(
                                text = i.toString(),
                                color = Color.Gray.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.End
                            )
                        }
                    }

                    // Horizontal-Scrolling Code Area (1 line of code == 1 line number)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = viewportHeight)
                            .horizontalScroll(editorHScroll)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { editorFocusRequester.requestFocus() }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        if (editorState.text.isEmpty()) {
                            Text(
                                text = "# Write or paste Python code...",
                                color = Color.Gray.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        }
                        BasicTextField(
                            value = editorState,
                            onValueChange = { incoming ->
                                val processed = applyPythonAutoIndent(editorState, incoming)
                                commitEditorChange(processed)
                            },
                            modifier = Modifier
                                .defaultMinSize(minWidth = minCodeWidth, minHeight = viewportHeight - 20.dp)
                                .focusRequester(editorFocusRequester),
                            textStyle = TextStyle(
                                color = Color(0xFFD4D4D4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(Color(0xFF00E5FF)),
                            visualTransformation = pythonSyntax
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Python Quick Coding & Undo/Redo Toolbar
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    QuickKeyChip("Undo", enabled = undoStack.isNotEmpty()) {
                        if (undoStack.isNotEmpty()) {
                            val prev = undoStack.removeAt(undoStack.lastIndex)
                            redoStack.add(editorState)
                            commitEditorChange(prev, recordHistory = false)
                        }
                    }
                }
                item {
                    QuickKeyChip("Redo", enabled = redoStack.isNotEmpty()) {
                        if (redoStack.isNotEmpty()) {
                            val next = redoStack.removeAt(redoStack.lastIndex)
                            undoStack.add(editorState)
                            commitEditorChange(next, recordHistory = false)
                        }
                    }
                }
                item {
                    QuickKeyChip("⇥ Tab") {
                        commitEditorChange(insertSnippetAtCursor(editorState, "    "))
                    }
                }
                item {
                    QuickKeyChip("⇤") {
                        commitEditorChange(unindentCurrentLine(editorState))
                    }
                }
                item { QuickKeyChip(":") { commitEditorChange(insertSnippetAtCursor(editorState, ":")) } }
                item { QuickKeyChip("()") { commitEditorChange(insertSnippetAtCursor(editorState, "()", -1)) } }
                item { QuickKeyChip("[]") { commitEditorChange(insertSnippetAtCursor(editorState, "[]", -1)) } }
                item { QuickKeyChip("{}") { commitEditorChange(insertSnippetAtCursor(editorState, "{}", -1)) } }
                item { QuickKeyChip("\"\"") { commitEditorChange(insertSnippetAtCursor(editorState, "\"\"", -1)) } }
                item { QuickKeyChip("''") { commitEditorChange(insertSnippetAtCursor(editorState, "''", -1)) } }
                item { QuickKeyChip("#") { commitEditorChange(insertSnippetAtCursor(editorState, "# ")) } }
                item { QuickKeyChip("=") { commitEditorChange(insertSnippetAtCursor(editorState, " = ")) } }
                item { QuickKeyChip("_") { commitEditorChange(insertSnippetAtCursor(editorState, "_")) } }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Console Header & Inline Terminal Output
            Column(modifier = Modifier.weight(0.45f).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Console Output", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        if (viewModel.isManualAwaitingInput) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "● Type inline below",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            modifier = Modifier.size(26.dp),
                            onClick = { viewModel.clearManualConsole() }
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Console", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            modifier = Modifier.size(26.dp),
                            onClick = {
                                val clip = ClipData.newPlainText("Console Output", viewModel.manualConsoleOutput)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Output", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                val consoleScrollState = rememberScrollState()
                LaunchedEffect(viewModel.manualConsoleOutput, viewModel.isManualAwaitingInput, viewModel.manualInputText) {
                    consoleScrollState.animateScrollTo(consoleScrollState.maxValue)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = if (viewModel.isManualAwaitingInput) Color(0xFF00E5FF).copy(alpha = 0.6f)
                            else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (viewModel.isManualAwaitingInput) {
                                try { inlineStdinFocusRequester.requestFocus() } catch (_: Exception) {}
                            }
                        }
                        .padding(10.dp)
                        .verticalScroll(consoleScrollState)
                ) {
                    if (viewModel.isManualAwaitingInput) {
                        val rawOut = viewModel.manualConsoleOutput
                        val lastNewline = rawOut.lastIndexOf('\n')
                        val priorLines = if (lastNewline >= 0) rawOut.substring(0, lastNewline + 1) else ""
                        val promptLine = if (lastNewline >= 0) rawOut.substring(lastNewline + 1) else rawOut

                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (priorLines.isNotEmpty()) {
                                SelectionContainer {
                                    Text(
                                        text = priorLines.trimEnd('\n'),
                                        color = Color(0xFF00FF00),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (promptLine.isNotEmpty()) {
                                    Text(
                                        text = promptLine,
                                        color = Color(0xFF00FF00),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                                BasicTextField(
                                    value = viewModel.manualInputText,
                                    onValueChange = { incoming ->
                                        if (incoming.contains("\n")) {
                                            viewModel.sendManualInput(incoming.substringBefore("\n"))
                                        } else {
                                            viewModel.manualInputText = incoming
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(inlineStdinFocusRequester),
                                    textStyle = TextStyle(
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = {
                                        viewModel.sendManualInput(viewModel.manualInputText)
                                    }),
                                    cursorBrush = SolidColor(Color(0xFF00E5FF))
                                )
                            }
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                text = viewModel.manualConsoleOutput.ifEmpty { "Ready." },
                                color = if (viewModel.manualConsoleOutput.isEmpty()) Color.Gray.copy(alpha = 0.5f) else Color(0xFF00FF00),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Run / Stop Button
            Button(
                onClick = {
                    if (viewModel.isManualRunning) {
                        viewModel.stopManualCode()
                    } else {
                        onRun()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isManualRunning) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                )
            ) {
                if (viewModel.isManualRunning) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (viewModel.isManualAwaitingInput) "Stop (Waiting for Input)" else "Stop Script",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run Script", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (scriptToRename != null) {
        AlertDialog(
            onDismissRequest = { scriptToRename = null },
            title = { Text("Rename Script") },
            text = {
                TextField(value = newTitleName, onValueChange = { newTitleName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameScript(context, scriptToRename!!.id, newTitleName)
                    scriptToRename = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { scriptToRename = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun QuickKeyChip(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) Color(0xFF1F242D) else Color(0xFF14181F),
        border = BorderStroke(
            1.dp,
            if (enabled) Color(0xFF00E5FF).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f)
        )
    ) {
        Text(
            text = label,
            color = if (enabled) Color(0xFF00E5FF) else Color.Gray.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}