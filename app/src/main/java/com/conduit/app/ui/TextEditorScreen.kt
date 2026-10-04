package com.conduit.nexus

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.EditText
import android.text.InputType
import android.view.Gravity
import android.graphics.Typeface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(viewModel: ConduitViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val hasChanges = viewModel.hasUnsavedChanges
    // Removed external scrollState for performance on large files
    var showOutputSheet by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // Fast Scroll State
    var scrollPercent by remember { mutableFloatStateOf(0f) }
    var isScrolling by remember { mutableStateOf(false) }
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    // Make the scroll handler permanently visible (partially transparent when inactive)
    val scrollbarAlpha by animateFloatAsState(if (isScrolling) 1f else 0.35f, label = "")

    BackHandler {
        if (hasChanges) showExitConfirm = true else onBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = { },
            windowInsets = WindowInsets(0.dp),
            navigationIcon = {
                IconButton(onClick = {
                    if (hasChanges) showExitConfirm = true else onBack()
                }) {
                    Icon(Icons.Default.Close, null)
                }
            },
            actions = {
                if (!viewModel.isEditLocked) {
                    IconButton(onClick = { editTextRef?.setText("") }) {
                        Icon(Icons.Default.DeleteForever, null, tint = Color.Red)
                    }
                    IconButton(onClick = {
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        editTextRef?.let { et ->
                            val start = et.selectionStart.coerceAtLeast(0)
                            val end = et.selectionEnd.coerceAtLeast(0)
                            et.text.replace(Math.min(start, end), Math.max(start, end), clip)
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                if (viewModel.editingFile?.extension?.lowercase() == "py") {
                    IconButton(onClick = {
                        val currentText = editTextRef?.text?.toString() ?: ""
                        viewModel.saveCurrentFile(currentText)
                        scope.launch(Dispatchers.IO) {
                            val python = com.chaquo.python.Python.getInstance()
                            val executor = python.getModule("executor")
                            val result = executor.callAttr("run_code", currentText).toString()
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                viewModel.executionResult = result
                                showOutputSheet = true
                            }
                        }
                    }) {
                        Icon(Icons.Default.PlayArrow, "Run", tint = Color(0xFF69F0AE))
                    }
                }

                IconButton(onClick = { viewModel.isEditLocked = !viewModel.isEditLocked }) {
                    Icon(
                        if (viewModel.isEditLocked) Icons.Default.Edit else Icons.Default.EditOff,
                        null, 
                        tint = if (viewModel.isEditLocked) Color.Gray else MaterialTheme.colorScheme.primary
                    )
                }

                if (viewModel.isEditLocked) {
                    Button(
                        onClick = { 
                            val currentText = editTextRef?.text?.toString() ?: viewModel.editorText
                            val clip = android.content.ClipData.newPlainText("File Content", currentText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }
                } else {
                    Button(
                        onClick = { 
                            val currentText = editTextRef?.text?.toString() ?: ""
                            viewModel.saveCurrentFile(currentText)
                            Toast.makeText(context, "File Saved", Toast.LENGTH_SHORT).show()
                        },
                        enabled = hasChanges,
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }
        )

        // Using Native AndroidView with Fast Scroll overlay
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
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
                
                // Add Momentum Fling Scroller
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
                    false // Return false to let EditText handle cursor placement and selection natively
                }
                
                // Save the default keyListener to toggle read-only mode later
                tag = keyListener
                
                setText(viewModel.editorText)

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
                                if (hasFocus()) {
                                    viewModel.hasUnsavedChanges = true
                                }
                            }
                        })
                    }
                },
                update = { view ->
                    // Instead of disabling the view (which breaks scrolling), we toggle the keyListener
                    if (viewModel.isEditLocked) {
                        if (view.keyListener != null) view.tag = view.keyListener
                        view.keyListener = null
                        view.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
                    } else {
                        if (view.keyListener == null) view.keyListener = view.tag as? android.text.method.KeyListener
                        view.movementMethod = android.text.method.ArrowKeyMovementMethod.getInstance()
                    }
                }
            )

            // Fast Scroll Handle
            if (true) {
                BoxWithConstraints(modifier = Modifier.fillMaxHeight().width(40.dp).align(Alignment.CenterEnd)) {
                    val constraintsHeight = maxHeight
                    
                    var isDraggingThumb by remember { mutableStateOf(false) }
                    
                    // Efficient scrollbar fade logic decoupled from the actual drag state
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
