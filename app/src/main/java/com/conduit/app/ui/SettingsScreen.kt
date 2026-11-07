package com.conduit.nexus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(viewModel: ConduitViewModel) {
    val context = LocalContext.current
    val bootOptions = listOf(
        BootOption(
            id = "File Manager",
            title = "File Manager",
            description = "Open directly into Nexus storage and file explorer.",
            icon = Icons.Default.Folder
        ),
        BootOption(
            id = "AI Chat",
            title = "AI Chat",
            description = "Launch right into your assistant chat session.",
            icon = Icons.Default.Chat
        ),
        BootOption(
            id = "Manual Lab",
            title = "Manual Lab",
            description = "Start in the Python code lab ready for execution.",
            icon = Icons.Default.Terminal
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Customize default behaviors and launch workflows",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        Text(
            text = "STARTUP SECTION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                bootOptions.forEachIndexed { index, option ->
                    val isSelected = viewModel.defaultBootSection == option.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.1f)
                                else Color.Transparent
                            )
                            .clickable {
                                viewModel.updateDefaultBootSection(context, option.id)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.2f)
                                    else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = null,
                                tint = if (isSelected) Color(0xFF00E5FF) else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.title,
                                color = if (isSelected) Color(0xFF00E5FF) else Color.White,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 15.sp
                            )
                            Text(
                                text = option.description,
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                viewModel.updateDefaultBootSection(context, option.id)
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(0xFF00E5FF),
                                unselectedColor = Color.Gray
                            )
                        )
                    }

                    if (index < bootOptions.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = Color.White.copy(alpha = 0.05f)
                        )
                    }
                }
            }
        }
    }
}

private data class BootOption(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)