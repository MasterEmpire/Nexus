package com.conduit.nexus

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.chaquo.python.Python

@Composable
fun InterpreterScreen(viewModel: ConduitViewModel) {
    val context = LocalContext.current
    val python = Python.getInstance()
    val sys = python.getModule("sys")
    val version = sys.get("version").toString()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Interpreter Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Active Version", style = MaterialTheme.typography.labelLarge)
                Text(version, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Execution Profile", style = MaterialTheme.typography.titleMedium)
        listOf("Standard", "Optimized", "Isolated").forEach { profile ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                RadioButton(
                    selected = viewModel.executionProfile == profile, 
                    onClick = { viewModel.updateExecutionProfile(context, profile) }
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(profile, style = MaterialTheme.typography.bodyLarge)
                    val desc = when(profile) {
                        "Optimized" -> "Tweaks GC and thread switching for speed."
                        "Isolated" -> "Clears variables and resets path before every run."
                        else -> "Default Chaquopy Python execution environment."
                    }
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Actions", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = { 
                viewModel.resetPython()
                android.widget.Toast.makeText(context, "Python Runtime Flushed", android.widget.Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer, 
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Flush")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Flush Python Runtime")
        }
        
        Text(
            "Forces the Python interpreter to wipe memory, clear the REPL scope, and reset the CWD to the default directory. Useful if a script breaks the kernel state.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
