package com.conduit.nexus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SidebarContent(
    sessions: List<Pair<String, String>>, 
    onSessionClick: (String) -> Unit, 
    onNewChatClick: () -> Unit, 
    onDeleteClick: (String) -> Unit,
    onPipClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onExplorerClick: () -> Unit,
    onManualClick: () -> Unit,
    onInterpreterClick: () -> Unit,
    onChatNavClick: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp).fillMaxHeight()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onNewChatClick) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("PRIMARY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        NavigationDrawerItem(label = { Text("AI Chat") }, selected = false, onClick = onChatNavClick, icon = { Icon(Icons.Default.Chat, null) })
        NavigationDrawerItem(label = { Text("File Explorer") }, selected = false, onClick = onExplorerClick, icon = { Icon(Icons.Default.Folder, null) })
        NavigationDrawerItem(label = { Text("Manual Lab") }, selected = false, onClick = onManualClick, icon = { Icon(Icons.Default.Terminal, null) })
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("KERNEL TOOLS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 16.dp))

        NavigationDrawerItem(label = { Text("Python REPL") }, selected = false, onClick = onTerminalClick, icon = { Icon(Icons.Default.SettingsEthernet, null) })
        NavigationDrawerItem(label = { Text("Interpreter Settings") }, selected = false, onClick = onInterpreterClick, icon = { Icon(Icons.Default.Tune, null) })
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        Text("HISTORY", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.first }) { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavigationDrawerItem(
                        modifier = Modifier.weight(1f),
                        label = { 
                            Text(
                                text = session.second, 
                                maxLines = 1, 
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, 
                                style = MaterialTheme.typography.bodyMedium
                            ) 
                        }, 
                        selected = false, 
                        onClick = { onSessionClick(session.first) }, 
                        icon = { Icon(Icons.Default.ChatBubbleOutline, null, modifier = Modifier.size(20.dp)) },
                        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                    )
                    IconButton(
                        onClick = { onDeleteClick(session.first) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.Gray.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}