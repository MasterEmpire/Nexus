package com.conduit.nexus

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ManualScreen(viewModel: ConduitViewModel, onRun: () -> Unit) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    var scriptToRename by remember { mutableStateOf<SavedScript?>(null) }
    var newTitleName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadSavedScripts(context) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.85f)) {
                Text("Script Library", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall, color = Color(0xFF00E5FF))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                        items(viewModel.savedScripts, key = { it.id }) { script ->
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
                                Box(Modifier.fillMaxSize().background(color).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                    Icon(Icons.Default.Delete, null, tint = Color.White)
                                }
                            },
                            enableDismissFromStartToEnd = false
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .combinedClickable(
                                        onClick = {
                                            viewModel.manualCode = script.code
                                            scope.launch { drawerState.close() }
                                        },
                                        onLongClick = {
                                            scriptToRename = script
                                            newTitleName = script.title
                                        }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Description, null, tint = Color(0xFF7C4DFF))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column { 
                                    Text(script.title, fontWeight = FontWeight.Bold) 
                                    Text(script.code.take(40).replace("\n", " ") + "...", style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1) 
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.LibraryBooks, contentDescription = "Library", tint = Color(0xFF00E5FF))
                }
                Spacer(modifier = Modifier.weight(1f))
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
                        viewModel.manualCode = pasteData
                        viewModel.saveScript(context, pasteData)
                        Toast.makeText(context, "Pasted & Saved", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Color.Gray)
                }
                IconButton(onClick = { viewModel.manualCode = "" }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = Color.Red.copy(alpha = 0.6f))
                }
            }

            Row(modifier = Modifier.weight(0.6f).fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))) {
                // Line Number Gutter
                val lineCount = viewModel.manualCode.lines().size
                Column(
                    modifier = Modifier.background(Color.White.copy(alpha = 0.05f)).padding(8.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.End
                ) {
                    repeat(lineCount) { i ->
                        Text("${i + 1}", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                
                TextField(
                    value = viewModel.manualCode,
                    onValueChange = { viewModel.manualCode = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    placeholder = { Text("Enter Python code...") },
                    visualTransformation = VisualTransformation.None,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Column(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Console Output", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    IconButton(modifier = Modifier.size(24.dp), onClick = {
                        val clip = ClipData.newPlainText("Console Output", viewModel.manualConsoleOutput)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Output", modifier = Modifier.size(16.dp))
                    }
                }
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black, RoundedCornerShape(4.dp))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(viewModel.manualConsoleOutput, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            Button( 
                onClick = onRun, 
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = if (viewModel.isManualRunning) Color.Gray else MaterialTheme.colorScheme.primary)
            ) {
                if (viewModel.isManualRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(" Executing...")
                } else {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(" Run Script")
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