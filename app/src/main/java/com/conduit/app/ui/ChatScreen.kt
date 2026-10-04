package com.conduit.nexus

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.launch
import java.io.File

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ConduitViewModel,
    messages: List<Message>, 
    input: String, 
    aiStatus: String?,
    isThinking: Boolean, 
    scriptProgress: Float,
    stagedFiles: List<File> = emptyList(), 
    onInputChange: (String) -> Unit, 
    onSend: () -> Unit, 
    onAttachFile: () -> Unit, 
    onInjectStructure: () -> Unit, 
    onQuickAction: (String) -> Unit, 
    onRemoveFile: (File) -> Unit = {},
    onRetryMessage: (Message) -> Unit = {},
    onEditMessage: (Message) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    var isScrollingActive by remember { mutableStateOf(false) }
    val showScrollToBottom by remember {
        derivedStateOf { 
            listState.canScrollForward && isScrollingActive
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            isScrollingActive = true
        } else {
            kotlinx.coroutines.delay(2500)
            isScrollingActive = false
        }
    }

    // Sticky Scroll Logic
    val isAtBottom by remember { 
        derivedStateOf { 
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0) true
            else {
                val lastVisibleItem = visibleItemsInfo.lastOrNull()
                lastVisibleItem?.index != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 2
            }
        }
    }

    LaunchedEffect(messages.size, isThinking) {
        val lastMessageIsUser = messages.lastOrNull()?.sender == "User"
        if (isAtBottom || lastMessageIsUser) {
            scope.launch {
                // Delay slightly to allow the item to be placed in the layout
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
            }
        }
    }

    var pillToEdit by remember { mutableStateOf<QuickPill?>(null) }
    var showPillDialog by remember { mutableStateOf(false) }
    var pillTitle by remember { mutableStateOf("") }
    var pillContent by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(viewModel.quickPills, key = { it.id }) { pill ->
                val isEnabled = !isThinking
                Surface(
                    onClick = { if (isEnabled) onQuickAction(pill.content) },
                    enabled = isEnabled,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.combinedClickable(
                        onClick = { onQuickAction(pill.content) },
                        onLongClick = {
                            pillToEdit = pill
                            pillTitle = pill.title
                            pillContent = pill.content
                            showPillDialog = true
                        }
                    )
                ) {
                    Text(
                        text = pill.title,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            item {
                IconButton(
                    onClick = {
                        pillToEdit = null
                        pillTitle = ""
                        pillContent = ""
                        showPillDialog = true
                    },
                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState) {
                items(messages) { msg -> 
                    ChatBubble(
                        sender = msg.sender, 
                        message = msg.text, 
                        isAi = msg.isAi, 
                        isSystem = msg.isSystemAction, 
                        onEdit = { _ -> onEditMessage(msg) },
                        onRetry = { onRetryMessage(msg) }
                    )  
                }
                if (aiStatus != null) {
                    item {
                        val status = aiStatus ?: ""
                        if (status.contains("Executing")) {
                             // NEON KERNEL HUD
                             Column(
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .padding(vertical = 12.dp, horizontal = 4.dp)
                                     .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                     .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                     .padding(16.dp)
                             ) {
                                 Row(verticalAlignment = Alignment.CenterVertically) {
                                     val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "")
                                     val alpha by infiniteTransition.animateFloat(
                                         initialValue = 0.4f, targetValue = 1f,
                                         animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                             animation = androidx.compose.animation.core.tween(800),
                                             repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                         ), label = ""
                                     )
                                     Icon(Icons.Default.Terminal, null, tint = Color(0xFF00E5FF).copy(alpha = alpha), modifier = Modifier.size(16.dp))
                                     Spacer(modifier = Modifier.width(8.dp))
                                     Text("KERNEL ACTIVE", color = Color(0xFF00E5FF), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                 }
                                 Spacer(modifier = Modifier.height(12.dp))
                                 LinearProgressIndicator(
                                     progress = { scriptProgress },
                                     modifier = Modifier.fillMaxWidth().height(2.dp),
                                     color = Color(0xFF00E5FF),
                                     trackColor = Color.White.copy(alpha = 0.1f)
                                 )
                                 Spacer(modifier = Modifier.height(8.dp))
                                 Text(status, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                             }
                        } else {
                            // SLICK THINKING BUBBLE
                            val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "thinking")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.4f, targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                    animation = androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                ), label = "alpha"
                            )

                            Row(
                                modifier = Modifier
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f * pulseAlpha), RoundedCornerShape(16.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f * pulseAlpha), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp).padding(end = 8.dp).alpha(pulseAlpha),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    listOf(0, 150, 300).forEach { delay ->
                                        val dotOffset by infiniteTransition.animateFloat(
                                            initialValue = 0f, targetValue = -4f,
                                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                                animation = androidx.compose.animation.core.tween(400, delayMillis = delay, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                            ), label = "dot"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .offset(y = dotOffset.dp)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha), CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            ) {
                FilledIconButton(
                    onClick = { scope.launch { listState.animateScrollToItem(messages.size) } },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    modifier = Modifier.size(40.dp).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom", modifier = Modifier.size(20.dp))
                }
            }
        }
        
        if (showPillDialog) {
            AlertDialog(
                onDismissRequest = { showPillDialog = false },
                title = { Text(if (pillToEdit == null) "Add Quick Action" else "Edit Quick Action") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(value = pillTitle, onValueChange = { pillTitle = it }, label = { Text("Button Title") }, singleLine = true)
                        TextField(value = pillContent, onValueChange = { pillContent = it }, label = { Text("AI Prompt") }, modifier = Modifier.height(100.dp))
                        if (pillToEdit != null) {
                            TextButton(
                                onClick = {
                                    viewModel.deletePill(context, pillToEdit!!.id)
                                    showPillDialog = false
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                            ) { Text("Delete Action") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (pillTitle.isNotBlank() && pillContent.isNotBlank()) {
                            viewModel.savePill(context, pillTitle, pillContent, pillToEdit?.id)
                            showPillDialog = false
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showPillDialog = false }) { Text("Cancel") } }
            )
        }

        Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
            Column(modifier = Modifier.imePadding()) {
                if (stagedFiles.isNotEmpty()) {
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(stagedFiles) { file ->
                            InputChip(
                                selected = true,
                                onClick = { },
                                label = { Text(file.name, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = { 
                                    Icon(
                                        Icons.Default.Close, 
                                        "Remove", 
                                        modifier = Modifier.size(16.dp).clickable { onRemoveFile(file) }
                                    ) 
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(
                        onClick = { onInjectStructure() },
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(Icons.Default.AccountTree, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    
                    IconButton(
                        onClick = onAttachFile,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(Icons.Default.AttachFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }

                    TextField(
                        value = input, 
                        onValueChange = onInputChange, 
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        placeholder = { Text("Ask AI anything...", style = MaterialTheme.typography.bodyMedium) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    IconButton(
                        onClick = onSend,
                        enabled = !isThinking,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Send, 
                            null, 
                            tint = if (input.isNotBlank() || stagedFiles.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                }
            }
        }
    }
}