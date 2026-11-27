package com.conduit.nexus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    val defaultEntries = remember(viewModel.extensionDefaults) {
        viewModel.extensionDefaults.entries.sortedBy { it.key }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .verticalScroll(rememberScrollState())
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

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DEFAULT APPS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            if (defaultEntries.isNotEmpty()) {
                TextButton(
                    onClick = {
                        viewModel.clearAllExtensionDefaults(context)
                        Toast.makeText(context, "Cleared all default apps", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Clear All", color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            if (defaultEntries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = Color.Gray.copy(alpha = 0.5f),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No default apps saved yet",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Pick 'Always' in the Open With dialog to set a default app for any file extension.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                Column(modifier = Modifier.padding(12.dp)) {
                    defaultEntries.forEachIndexed { index, (ext, packageName) ->
                        val resolvedApp = remember(packageName) {
                            try {
                                val pm = context.packageManager
                                val ai = pm.getApplicationInfo(packageName, 0)
                                val label = pm.getApplicationLabel(ai).toString()
                                val drawable = pm.getApplicationIcon(ai)
                                val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                                    drawable.bitmap
                                } else {
                                    val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                                    val c = Canvas(b)
                                    drawable.setBounds(0, 0, 64, 64)
                                    drawable.draw(c)
                                    b
                                }
                                label to bmp.asImageBitmap()
                            } catch (_: Exception) {
                                packageName to null
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (resolvedApp.second != null) {
                                Image(
                                    bitmap = resolvedApp.second!!,
                                    contentDescription = resolvedApp.first,
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Android, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Surface(
                                color = Color(0xFF00E5FF).copy(alpha = 0.14f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = ".$ext",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = resolvedApp.first,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = packageName,
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = {
                                    viewModel.clearExtensionDefault(context, ext)
                                    Toast.makeText(context, "Cleared default for .$ext", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear .$ext default",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (index < defaultEntries.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = Color.White.copy(alpha = 0.05f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private data class BootOption(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)