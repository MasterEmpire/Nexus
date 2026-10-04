package com.conduit.nexus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import androidx.compose.animation.core.*

@Composable
fun ChatBubble(sender: String, message: String, isAi: Boolean, isSystem: Boolean = false, onEdit: (String) -> Unit = {}, onRetry: () -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Column(
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
        horizontalAlignment = if (isAi) Alignment.Start else Alignment.End
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (isAi) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(end = 6.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(
                        if (isSystem) Color.White.copy(alpha = 0.05f) 
                        else if (isAi) Color(0xFF161B22) 
                        else Color(0xFF21262D),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isAi) 4.dp else 16.dp, bottomEnd = if (isAi) 16.dp else 4.dp)
                    )
                    .border(
                        1.dp, 
                        if (isAi) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (isAi) 4.dp else 16.dp, bottomEnd = if (isAi) 16.dp else 4.dp)
                    )
                    .padding(12.dp)
                    .animateContentSize()
            ) {
                SelectionContainer {
                    val blocks = remember(message) { MessageMapper.parseToBlocks(message) }
                    if (blocks.isEmpty()) {
                        RenderTextBlock(message)
                    } else {
                        Column {
                            blocks.forEach { block ->
                                when (block) {
                                    is RenderBlock.Text -> {
                                        if (block.content.isNotBlank()) {
                                            RenderTextBlock(block.content)
                                        }
                                    }
                                    is RenderBlock.CodeExecution -> {
                                        RenderCodeBlock(block.code, block.result)
                                    }
                                    is RenderBlock.UiNav -> {
                                        Text(
                                            text = "Navigating to: ${block.path} (${block.mode})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF00E5FF),
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                    is RenderBlock.BatchOp -> {
                                        Text(
                                            text = "Batch ${block.operation} on ${block.paths.size} items",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFFFD740),
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (isAi) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Copy Output", 
                            style = MaterialTheme.typography.labelSmall, 
                            color = Color.Gray, 
                            modifier = Modifier.clickable {
                                val textToCopy = try {
                                    val blocks = MessageMapper.parseToBlocks(message)
                                    val sb = StringBuilder()
                                    for (block in blocks) {
                                        when (block) {
                                            is RenderBlock.Text -> {
                                                if (sb.isNotEmpty()) sb.append("\n\n")
                                                sb.append(block.content)
                                            }
                                            is RenderBlock.CodeExecution -> {
                                                if (sb.isNotEmpty()) sb.append("\n\n")
                                                sb.append("```python\n${block.code}\n```")
                                                if (block.result.isNotBlank()) {
                                                    sb.append("\n\nOutput:\n```\n${block.result}\n```")
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                    sb.toString().ifEmpty { message }
                                } catch(e: Exception) { message }
                                
                                val clip = ClipData.newPlainText("AI Response", textToCopy)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                        Text("ID: ${sender.take(1)}${message.hashCode().toString().takeLast(4)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray.copy(alpha = 0.4f))
                        Text("Retry", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.clickable { onRetry() })
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Edit", 
                            style = MaterialTheme.typography.labelSmall, 
                            color = Color.Gray, 
                            modifier = Modifier.clickable { onEdit(message) }
                        )
                        Text(
                            "Copy", 
                            style = MaterialTheme.typography.labelSmall, 
                            color = Color.Gray, 
                            modifier = Modifier.clickable {
                                val clip = ClipData.newPlainText("User Message", message)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RenderTextBlock(text: String) {
    val blocks = remember(text) { text.split("```") }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index % 2 == 1) {
                val lines = block.trim().lines()
                val lang = lines.firstOrNull()?.trim() ?: ""
                val content = if (lines.size > 1) lines.drop(1).joinToString("\n") else block
                SuggestedCodeBlock(content, lang)
            } else if (block.isNotBlank()) {
                Text(
                    text = parseInlineMarkdown(block),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun SuggestedCodeBlock(code: String, lang: String) {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(lang.uppercase().ifEmpty { "CODE" }, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                IconButton(modifier = Modifier.size(24.dp), onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Code Snippet", code)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Snippet copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = Color.Gray)
                }
            }
            Text(
                text = code.trim(),
                color = Color(0xFFCE9178),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

fun parseInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        val pattern = "(\\*\\*.*?\\*\\*|`.*?`)".toRegex()
        var lastIndex = 0
        
        pattern.findAll(text).forEach { match ->
            append(text.substring(lastIndex, match.range.first))
            val matchText = match.value
            when {
                matchText.startsWith("**") && matchText.endsWith("**") -> {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                        append(matchText.removeSurrounding("**"))
                    }
                }
                matchText.startsWith("`") && matchText.endsWith("`") -> {
                    withStyle(style = SpanStyle(
                        fontFamily = FontFamily.Monospace, 
                        background = Color.Black.copy(alpha = 0.3f), 
                        color = Color(0xFF80CBC4)
                    )) {
                        append(matchText.removeSurrounding("`"))
                    }
                }
                else -> append(matchText)
            }
            lastIndex = match.range.last + 1
        }
        append(text.substring(lastIndex))
    }
}

@Composable
fun RenderCodeBlock(code: String, result: String) {
    var isExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
        color = Color(0xFF111111),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Python Runner", style = MaterialTheme.typography.labelLarge, color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { isExpanded = !isExpanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (isExpanded) "Hide Details" else "Show Result", style = MaterialTheme.typography.labelSmall)
                    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
                }
            }

            if (isExpanded) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SCRIPT", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Script", code))
                            Toast.makeText(context, "Script copied", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                        }
                    }
                    Surface(color = Color.Black.copy(alpha = 0.3f), shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(code, modifier = Modifier.padding(8.dp), color = Color(0xFF4FC3F7), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("OUTPUT", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Output", result))
                            Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                        }
                    }
                    Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(result, modifier = Modifier.padding(8.dp), color = Color(0xFF81C784), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text(
                    text = if (result.length > 60) result.take(60) + "..." else result,
                    modifier = Modifier.padding(12.dp),
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}