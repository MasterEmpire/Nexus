package com.conduit.nexus

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color

import androidx.lifecycle.SavedStateHandle

class ConduitViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    // Persist basic UI state across process death
    var currentView by mutableStateOf(savedStateHandle.get<String>("currentView") ?: "chat")
    var explorerPath by mutableStateOf(savedStateHandle.get<String>("explorerPath") ?: "/storage/emulated/0")
    var currentSessionId by mutableStateOf(savedStateHandle.get<String>("currentSessionId"))

    var chatMessages by mutableStateOf<List<Message>>(listOf(Message(0, "AI", "Hello! I am your AI File Manager.", true)))
    var sessions by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var aiStatus by mutableStateOf<String?>(null)
    var scriptProgress by mutableFloatStateOf(0f)
    var scriptStatusText by mutableStateOf("")
    var isDataLoading by mutableStateOf(false)
    var currentCwd by mutableStateOf("/storage/emulated/0")
    var explorerCategory by mutableStateOf<String?>(null)
    var explorerFiles by mutableStateOf<List<FileItem>>(emptyList())
    var isScanningExplorer by mutableStateOf(false)
    var showExplorerCreateDialog by mutableStateOf(false)
    var showExplorerSortMenu by mutableStateOf(false)
    var explorerReturnCategory by mutableStateOf<String?>(null)
    var searchQuery by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    var selectedFiles by mutableStateOf<Set<FileItem>>(emptySet())
    val scrollPositions = mutableMapOf<String, Int>()

    var pinnedPaths by mutableStateOf<Set<String>>(emptySet())
        private set

    // Cache stores: Path -> Pair(LastModifiedTimestamp, ItemCount)
    var folderCountCache = mutableMapOf<String, Pair<Long, Int>>()

    fun loadFolderCache(context: android.content.Context) {
        val file = File(context.filesDir, "folder_counts.json")
        if (file.exists()) {
            try {
                val json = org.json.JSONObject(file.readText())
                json.keys().forEach { path ->
                    val data = json.getJSONArray(path)
                    folderCountCache[path] = data.getLong(0) to data.getInt(1)
                }
            } catch(e: Exception) {}
        }
    }

    fun saveFolderCache(context: android.content.Context) {
        val file = File(context.filesDir, "folder_counts.json")
        val json = org.json.JSONObject()
        folderCountCache.forEach { (path, pair) ->
            val arr = org.json.JSONArray().apply { put(pair.first); put(pair.second) }
            json.put(path, arr)
        }
        viewModelScope.launch(Dispatchers.IO) { file.writeText(json.toString()) }
    }

    fun loadPinnedPaths(context: android.content.Context) {
        val file = File(context.filesDir, "pinned_paths.json")
        if (file.exists()) {
            try {
                val json = org.json.JSONArray(file.readText())
                val set = mutableSetOf<String>()
                for (i in 0 until json.length()) set.add(json.getString(i))
                pinnedPaths = set
            } catch(e: Exception) {}
        }
    }

    private fun savePinnedPaths(context: android.content.Context) {
        val file = File(context.filesDir, "pinned_paths.json")
        val json = org.json.JSONArray()
        pinnedPaths.forEach { json.put(it) }
        file.writeText(json.toString())
    }

    fun togglePinForSelected(context: android.content.Context) {
        val selected = selectedFiles.map { it.path }
        val allPinned = selected.all { it in pinnedPaths }
        pinnedPaths = if (allPinned) pinnedPaths - selected.toSet() else pinnedPaths + selected.toSet()
        savePinnedPaths(context)
        clearSelection()
        loadExplorerFolder(explorerPath)
    }
    
    var stagedFiles by mutableStateOf<List<File>>(emptyList())
        private set
    fun addStagedFile(file: File) {
        if (!stagedFiles.contains(file)) {
            stagedFiles = stagedFiles + file
            savedStateHandle["stagedFilesPaths"] = stagedFiles.map { it.absolutePath }
        }
    }
    fun removeStagedFile(file: File) {
        stagedFiles = stagedFiles.filter { it != file }
        savedStateHandle["stagedFilesPaths"] = stagedFiles.map { it.absolutePath }
    }

    var extractionQueue by mutableStateOf<List<FileItem>>(emptyList())
        private set
    fun updateExtractionQueue(list: List<FileItem>) {
        extractionQueue = list
        savedStateHandle["extractionQueuePaths"] = list.map { it.path }
    }

    var isExtractionMode by mutableStateOf(savedStateHandle.get<Boolean>("isExtractionMode") ?: false)
        private set

    var executionProfile by mutableStateOf("Standard")
        private set

    fun loadSettings(context: android.content.Context) {
        val prefs = context.getSharedPreferences("conduit_settings", android.content.Context.MODE_PRIVATE)
        executionProfile = prefs.getString("exec_profile", "Standard") ?: "Standard"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                executor.callAttr("set_profile", executionProfile)
            } catch(e: Exception) {}
        }
    }

    fun updateExecutionProfile(context: android.content.Context, profile: String) {
        executionProfile = profile
        context.getSharedPreferences("conduit_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("exec_profile", profile).apply()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                executor.callAttr("set_profile", profile)
            } catch(e: Exception) {}
        }
    }
    fun updateExtractionMode(enabled: Boolean) {
        isExtractionMode = enabled
        savedStateHandle["isExtractionMode"] = enabled
    }

    // Editor State
    var editingFile by mutableStateOf<File?>(null)
    var editorText = ""
    var hasUnsavedChanges by mutableStateOf(false)
    private var recoveryFile: File? = null
    var isEditLocked by mutableStateOf(true)
    var executionResult by mutableStateOf<String?>(null)
    var highlightedPath by mutableStateOf<String?>(null)
    var chatInput by mutableStateOf("")
    var editingMessageTimestamp by mutableStateOf<String?>(null)

    // Manual Script State
    var manualCode by mutableStateOf("import os\nprint('Hello Nexus')")
    var manualConsoleOutput by mutableStateOf("")
    var isManualRunning by mutableStateOf(false)
    var savedScripts by mutableStateOf<List<SavedScript>>(emptyList())
    var selectedTab by mutableIntStateOf(0)
    var quickPills by mutableStateOf<List<QuickPill>>(listOf(
        QuickPill(title = "List Files", content = "List all files in current directory"),
        QuickPill(title = "Storage Info", content = "Show me storage statistics")
    ))

    fun loadQuickPills(context: android.content.Context) {
        val file = File(context.filesDir, "quick_pills.json")
        if (file.exists()) {
            try {
                val json = org.json.JSONArray(file.readText())
                val list = mutableListOf<QuickPill>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    list.add(QuickPill(obj.getString("id"), obj.getString("title"), obj.getString("content")))
                }
                quickPills = list
            } catch (e: Exception) { }
        }
    }

    private fun persistPills(context: android.content.Context) {
        val file = File(context.filesDir, "quick_pills.json")
        val json = org.json.JSONArray()
        quickPills.forEach {
            json.put(org.json.JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("content", it.content)
            })
        }
        file.writeText(json.toString())
    }

    fun savePill(context: android.content.Context, title: String, content: String, id: String? = null) {
        if (id != null) {
            quickPills = quickPills.map { if (it.id == id) it.copy(title = title, content = content) else it }
        } else {
            quickPills = quickPills + QuickPill(title = title, content = content)
        }
        persistPills(context)
    }

    fun deletePill(context: android.content.Context, id: String) {
        quickPills = quickPills.filter { it.id != id }
        persistPills(context)
    }

    fun loadSavedScripts(context: android.content.Context) {
        val file = File(context.filesDir, "scripts_history.json")
        if (file.exists()) {
            try {
                val json = org.json.JSONArray(file.readText())
                val list = mutableListOf<SavedScript>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    list.add(SavedScript(obj.getString("id"), obj.getString("title"), obj.getString("code"), obj.getLong("timestamp")))
                }
                savedScripts = list.sortedByDescending { it.timestamp }
            } catch (e: Exception) { }
        }
    }

    private fun persistScripts(context: android.content.Context) {
        val file = File(context.filesDir, "scripts_history.json")
        val json = org.json.JSONArray()
        savedScripts.forEach {
            json.put(org.json.JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("code", it.code)
                put("timestamp", it.timestamp)
            })
        }
        file.writeText(json.toString())
    }

    fun saveScript(context: android.content.Context, code: String, customTitle: String? = null) {
        if (code.isBlank()) return
        val existing = savedScripts.find { it.code.trim() == code.trim() }
        if (existing != null) return // Deduplicate

        val newScript = SavedScript(
            title = customTitle ?: "Script ${java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}",
            code = code
        )
        savedScripts = (listOf(newScript) + savedScripts).take(50)
        persistScripts(context)
    }

    fun deleteScript(context: android.content.Context, id: String) {
        savedScripts = savedScripts.filter { it.id != id }
        persistScripts(context)
    }

    fun renameScript(context: android.content.Context, id: String, newTitle: String) {
        savedScripts = savedScripts.map { if (it.id == id) it.copy(title = newTitle) else it }
        persistScripts(context)
    }

    fun runManualCode(context: android.content.Context) {
        if (ConduitExecutionService.isRunning) return
        manualConsoleOutput = ""
        isManualRunning = true
        
        // Setup the bridge listeners
        ConduitExecutionService.outputListener = { text ->
            viewModelScope.launch(Dispatchers.Main) {
                manualConsoleOutput += text
            }
        }
        
        ConduitExecutionService.onFinished = {
            viewModelScope.launch(Dispatchers.Main) {
                isManualRunning = false
            }
        }

        val intent = android.content.Intent(context, ConduitExecutionService::class.java).apply {
            putExtra("code", manualCode)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // Storage Stats
    var storageLabel by mutableStateOf("Calculating...")
    var storagePercent by mutableFloatStateOf(0f)
    var dashboardRecentFiles by mutableStateOf<List<FileItem>>(emptyList())
    var currentSortOrder by mutableStateOf(SortOrder.DATE_NEW)

    private val python = Python.getInstance()
    private val executor = python.getModule("executor")

    init {
        refreshSessions()
        syncCwd()
        updateStorageStats()
        
        // Restore session history if we were in a chat
        currentSessionId?.let { loadSession(it) }

        // Restore Staged Files & Extraction Queue
        savedStateHandle.get<List<String>>("stagedFilesPaths")?.let { paths ->
            stagedFiles = paths.map { File(it) }
        }
        savedStateHandle.get<List<String>>("extractionQueuePaths")?.let { paths ->
            extractionQueue = paths.map { path -> 
                FileItem(path.split("/").last(), "Pending...", androidx.compose.material.icons.Icons.Default.Refresh, androidx.compose.ui.graphics.Color.Gray, path)
            }
        }

        // Re-hook to service if it's already running from a previous process
        if (ConduitExecutionService.isRunning) {
            isManualRunning = true
            ConduitExecutionService.outputListener = { text ->
                viewModelScope.launch(Dispatchers.Main) {
                    manualConsoleOutput += text
                }
            }
            ConduitExecutionService.onFinished = {
                viewModelScope.launch(Dispatchers.Main) {
                    isManualRunning = false
                }
            }
        }
    }

    fun initRecovery(cacheDir: File) {
        recoveryFile = File(cacheDir, "editor_recovery.tmp")
        val editingPath = savedStateHandle.get<String>("editingFilePath")
        if (editingPath != null) {
            val file = File(editingPath)
            if (file.exists()) {
                editingFile = file
                if (recoveryFile?.exists() == true) {
                    editorText = recoveryFile?.readText() ?: ""
                    hasUnsavedChanges = true
                } else {
                    openFileForEditing(file)
                }
            }
        }
    }

    fun refreshDashboard(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val recent = FileScanner.getRecentFiles(context).take(5)
            withContext(Dispatchers.Main) {
                dashboardRecentFiles = recent
                updateStorageStats()
            }
        }
    }

    fun loadExplorerFolder(path: String, context: android.content.Context? = null) {
        if (explorerCategory != "Internal Storage" && explorerCategory != null) {
            explorerReturnCategory = explorerCategory
        }
        explorerCategory = "Internal Storage"
        explorerPath = path
        isScanningExplorer = true
        viewModelScope.launch(Dispatchers.IO) {
            val rawResults = if (path.contains("!/")) {
                val zipPath = path.substringBefore("!/")
                val internal = path.substringAfter("!/")
                FileScanner.getZipContents(zipPath, internal)
            } else {
                val results = FileScanner.getDirectoryContents(path, currentSortOrder, folderCountCache)
                // Save cache if it was updated during the scan
                if (context != null) {
                    saveFolderCache(context)
                }
                results
            }
            val sortedResults = rawResults.sortedByDescending { it.path in pinnedPaths }
            withContext(Dispatchers.Main) {
                explorerFiles = sortedResults
                isScanningExplorer = false
            }
        }
    }

    fun openExplorerCategory(context: android.content.Context, cat: String) {
        explorerCategory = cat
        isScanningExplorer = true
        viewModelScope.launch(Dispatchers.IO) {
            val results = when (cat) {
                "Recent Files" -> FileScanner.getRecentFiles(context)
                "Images", "Videos", "Audio" -> FileScanner.getMediaFiles(context, cat)
                "Internal Storage" -> {
                    explorerPath = "/storage/emulated/0"
                    FileScanner.getDirectoryContents(explorerPath, currentSortOrder)
                }
                "Downloads" -> {
                    explorerPath = "/storage/emulated/0/Download"
                    FileScanner.getDirectoryContents(explorerPath, currentSortOrder)
                }
                else -> {
                    val python = com.chaquo.python.Python.getInstance()
                    val executor = python.getModule("executor")
                    val list = executor.callAttr("find_files_by_type", cat.lowercase()).asList()
                    list.map { 
                        val p = it.toString()
                        val f = File(p)
                        val sizeStr = if (f.length() > 0) {
                            val bytes = f.length()
                            val units = arrayOf("B", "KB", "MB", "GB")
                            val i = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
                            String.format("%.1f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
                        } else "0 B"
                        
                        val parentFolder = p.split("/").dropLast(1).lastOrNull() ?: ""
                        
                        FileItem(
                            name = f.name, 
                            meta = "$sizeStr • $parentFolder", 
                            icon = if (cat == "Zips") Icons.Default.Archive else Icons.Default.Description, 
                            color = if (cat == "Zips") Color(0xFFFF4081) else Color(0xFFFFD740), 
                            path = p
                        )
                    }
                }
            }
            val sortedResults = results.sortedByDescending { it.path in pinnedPaths }
            withContext(Dispatchers.Main) {
                explorerFiles = sortedResults
                isScanningExplorer = false
            }
        }
    }

    fun updateStorageStats() {
        try {
            val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val bytesTotal = stat.totalBytes
            val bytesAvailable = stat.availableBytes
            val bytesUsed = bytesTotal - bytesAvailable

            val totalGB = bytesTotal / (1024 * 1024 * 1024.0)
            val usedGB = bytesUsed / (1024 * 1024 * 1024.0)

            storageLabel = "${String.format("%.1f", usedGB)} GB / ${String.format("%.1f", totalGB)} GB"
            storagePercent = (usedGB.toFloat() / totalGB.toFloat())
        } catch (e: Exception) {
            storageLabel = "Error reading storage"
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            isDataLoading = true
            sessions = SupabaseClient.fetchSessionsFromSupabase()
            isDataLoading = false
        }
    }

    fun loadSession(id: String) {
        viewModelScope.launch {
            isDataLoading = true
            currentSessionId = id
            chatMessages = SupabaseClient.fetchHistoryForSession(id)
            isDataLoading = false
        }
    }

    fun createNewChat() {
        // Lazy creation: Just clear the UI state. 
        // The actual DB session will be created in sendMessage() on the first prompt.
        currentSessionId = null
        chatMessages = listOf(Message(0, "AI", "Hello! I am your AI File Manager.", true))
        chatInput = ""
        editingMessageTimestamp = null
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            if (SupabaseClient.deleteSessionFromSupabase(id)) {
                if (currentSessionId == id) {
                    currentSessionId = null
                    chatMessages = listOf(Message(0, "AI", "Session deleted.", true))
                }
                refreshSessions()
            }
        }
    }

    fun sendMessage(context: android.content.Context, displayAsSystem: Boolean = false) {
        if (aiStatus != null) return // Prevent concurrent calls
        val text = chatInput
        if (text.isBlank() && stagedFiles.isEmpty()) return

        val selectionContext = if (selectedFiles.isNotEmpty()) {
            "[User has selected: ${selectedFiles.joinToString { it.path }}]\n"
        } else ""
        
        val finalPrompt = if (stagedFiles.isNotEmpty()) {
            "$selectionContext[Attached Files: ${stagedFiles.joinToString { it.absolutePath }}]\n\n$text"
        } else "$selectionContext$text"

        chatInput = ""
        stagedFiles = emptyList()

        viewModelScope.launch {
            aiStatus = "Thinking..."
            
            val localTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            val userMsg = Message(
                id = System.currentTimeMillis(), 
                sender = "User", 
                text = if (displayAsSystem) "[System Action]" else finalPrompt, 
                isAi = false, 
                isSystemAction = displayAsSystem, 
                timestamp = localTimestamp
            )

            if (editingMessageTimestamp != null && currentSessionId != null) {
                val msgIndex = chatMessages.indexOfFirst { it.timestamp == editingMessageTimestamp }
                if (msgIndex != -1) {
                    val tsToMatch = editingMessageTimestamp!!
                    
                    // Sequential sync: Try to kill the backend branch before updating local UI
                    val success = withContext(Dispatchers.IO) {
                        SupabaseClient.truncateHistory(currentSessionId!!, tsToMatch)
                    }
                    
                    if (success) {
                        chatMessages = chatMessages.take(msgIndex) + userMsg
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Failed to sync edit with server. History intact.", android.widget.Toast.LENGTH_LONG).show()
                        }
                        // Still send the message so the user isn't blocked, but we don't truncate locally to match the server desync
                        chatMessages = chatMessages + userMsg 
                    }
                } else {
                    chatMessages = chatMessages + userMsg
                }
                editingMessageTimestamp = null
            } else {
                chatMessages = chatMessages + userMsg
            }

            if (currentSessionId == null) {
                val cleanText = text.trim()
                val fallbackTitle = if (cleanText.isNotBlank()) {
                    if (cleanText.length > 30) cleanText.take(30) + "..." else cleanText
                } else if (finalPrompt.contains("[Attached Files:")) {
                    "File Analysis"
                } else {
                    "New Conversation"
                }
                val newId = SupabaseClient.createNewSessionInSupabase(fallbackTitle)
                currentSessionId = newId
                refreshSessions()
            }
            
            val aiResponse = SupabaseClient.callGeminiEdgeFunction(context, finalPrompt, currentSessionId) { status ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (status.contains("[PROGRESS:")) {
                        val regex = "\\[PROGRESS:(\\d+):(.*)\\]".toRegex()
                        regex.find(status)?.let { match ->
                            val (p, s) = match.destructured
                            scriptProgress = p.toFloat() / 100f
                            aiStatus = "Executing: $s"
                        }
                    } else {
                        aiStatus = status
                    }
                }
            }
            
            // Check for UI Navigation commands in the response
            try {
                if (aiResponse.startsWith("[")) {
                    val blocks = org.json.JSONArray(aiResponse)
                    for (i in 0 until blocks.length()) {
                        val block = blocks.getJSONObject(i)
                        if (block.optString("type") == "batch_op") {
                            val op = block.getString("operation")
                            val paths = block.getJSONArray("paths")
                            val dest = block.optString("destination")
                            
                            viewModelScope.launch(Dispatchers.IO) {
                                for (i in 0 until paths.length()) {
                                    val f = File(paths.getString(i))
                                    if (op == "delete") {
                                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                                    } else if (op == "move" && dest.isNotEmpty()) {
                                        val target = File(dest, f.name)
                                        f.renameTo(target)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    loadExplorerFolder(explorerPath)
                                    android.widget.Toast.makeText(context, "Batch $op complete", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        if (block.optString("type") == "ui_nav") {
                            val rawPath = block.getString("path")
                            val mode = block.getString("mode")
                            val file = java.io.File(rawPath).canonicalFile // Normalize the path
                            
                            if (file.exists()) {
                                if (mode == "direct" && file.isFile) {
                                    openFileForEditing(file)
                                    currentView = "explorer"
                                    selectedTab = 1
                                } else {
                                    // Mode 'location' - AI wants us to find the item in its folder
                                    val targetPath = file.absolutePath
                                    val parent = file.parent ?: "/storage/emulated/0"
                                    
                                    highlightedPath = targetPath
                                    explorerCategory = "Internal Storage"
                                    loadExplorerFolder(parent)
                                    currentView = "explorer"
                                    selectedTab = 1
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { }

            chatMessages = chatMessages + Message(System.currentTimeMillis() + 1, "AI", aiResponse, true)
            
            val userMsgCount = chatMessages.count { !it.isAi && !it.isSystemAction }
            if (userMsgCount == 3 || userMsgCount == 5) {
                viewModelScope.launch {
                    SupabaseClient.generateTitleForSession(currentSessionId!!)
                    refreshSessions()
                }
            }

            syncCwd()
            aiStatus = null
        }
    }

    fun syncCwd() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val path = python.getModule("os").callAttr("getcwd").toString()
                withContext(Dispatchers.Main) { currentCwd = path }
            } catch (e: Exception) { }
        }
    }

    fun resetPython() {
        viewModelScope.launch(Dispatchers.IO) {
            executor.callAttr("reset_state")
            syncCwd()
        }
    }

    fun renameFile(context: android.content.Context, oldPath: String, newName: String, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val oldFile = File(oldPath)
                val newFile = File(oldFile.parent, newName)
                if (newFile.exists()) throw Exception("File already exists")
                
                val success = oldFile.renameTo(newFile)
                withContext(Dispatchers.Main) {
                    if (success) {
                        android.widget.Toast.makeText(context, "Renamed to $newName", android.widget.Toast.LENGTH_SHORT).show()
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(oldPath, newFile.absolutePath), null, null)
                        onComplete()
                    } else {
                        android.widget.Toast.makeText(context, "Rename failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun toggleSelection(item: FileItem) {
        selectedFiles = if (selectedFiles.contains(item)) {
            selectedFiles - item
        } else {
            selectedFiles + item
        }
    }

    fun clearSelection() {
        selectedFiles = emptySet()
    }

    fun selectAll() {
        selectedFiles = explorerFiles.toSet()
    }

    fun deleteSelectedFiles(context: android.content.Context, onComplete: () -> Unit) {
        val filesToDelete = selectedFiles.toList()
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            filesToDelete.forEach { item ->
                val file = File(item.path)
                if (if (file.isDirectory) file.deleteRecursively() else file.delete()) successCount++
                android.media.MediaScannerConnection.scanFile(context, arrayOf(item.path), null, null)
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Deleted $successCount files", android.widget.Toast.LENGTH_SHORT).show()
                clearSelection()
                onComplete()
            }
        }
    }

    fun deleteFile(context: android.content.Context, path: String, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(path)
                val name = file.name
                val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
                
                withContext(Dispatchers.Main) {
                    if (success) {
                        android.widget.Toast.makeText(context, "Deleted $name", android.widget.Toast.LENGTH_SHORT).show()
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                        onComplete()
                    } else {
                        android.widget.Toast.makeText(context, "Delete failed", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun createFileOrFolder(context: android.content.Context, name: String, isFolder: Boolean, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(explorerPath, name)
                val success = if (isFolder) file.mkdirs() else file.createNewFile()
                
                withContext(Dispatchers.Main) {
                    if (success) {
                        android.widget.Toast.makeText(context, "Created: $name", android.widget.Toast.LENGTH_SHORT).show()
                        // Force a MediaStore scan so the system sees it
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                        onComplete()
                    } else {
                        android.widget.Toast.makeText(context, "Failed to create $name", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onFileClicked(fileItem: FileItem, context: android.content.Context, loadFolder: (String) -> Unit) {
        // Handle Virtual Archive Paths Safely
        if (fileItem.path.contains("!/")) {
            if (fileItem.path.endsWith("/")) {
                explorerCategory = "Internal Storage"
                loadFolder(fileItem.path)
            } else {
                val textExts = listOf(
                    "txt", "py", "js", "json", "kt", "java", "html", "css", "md", "yml", "yaml", 
                    "c", "cpp", "h", "ts", "tsx", "jsx", "xml", "sql", "log", "env", "ini", 
                    "conf", "sh", "bat", "gradle", "properties", "toml"
                )
                val ext = fileItem.path.substringAfterLast('.', "").lowercase()
                if (textExts.contains(ext)) {
                    android.widget.Toast.makeText(context, "Opening in read-only mode...", android.widget.Toast.LENGTH_SHORT).show()
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val zipPath = fileItem.path.substringBefore("!/")
                            val internalPath = fileItem.path.substringAfter("!/")
                            var content = ""
                            java.util.zip.ZipFile(File(zipPath)).use { zip ->
                                val entries = zip.entries()
                                while (entries.hasMoreElements()) {
                                    val entry = entries.nextElement()
                                    var entryName = entry.name.replace("\\", "/")
                                    while (entryName.contains("//")) entryName = entryName.replace("//", "/")
                                    while (entryName.startsWith("/")) entryName = entryName.substring(1)
                                    while (entryName.startsWith("./")) entryName = entryName.substring(2)
                                    
                                    if (entryName == internalPath) {
                                        content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                                        break
                                    }
                                }
                            }
                            val tempFile = File(context.cacheDir, internalPath.substringAfterLast("/"))
                            tempFile.writeText(content)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                openFileForEditing(tempFile)
                            }
                        } catch(e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Cannot read file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    android.widget.Toast.makeText(context, "Please extract the archive to open this file.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val file = File(fileItem.path)
        if (file.isDirectory) {
            explorerCategory = "Internal Storage"
            loadFolder(file.absolutePath)
        } else {
            val textExts = listOf(
                "txt", "py", "js", "json", "kt", "java", "html", "css", "md", "yml", "yaml", 
                "c", "cpp", "h", "ts", "tsx", "jsx", "xml", "sql", "log", "env", "ini", 
                "conf", "sh", "bat", "gradle", "properties", "toml"
            )
            if (file.extension.lowercase() == "zip") {
                loadFolder("${file.absolutePath}!/")
            } else if (textExts.contains(file.extension.lowercase())) {
                openFileForEditing(file)
            } else {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val mimeType = context.contentResolver.getType(uri) ?: "*/*"
                    
                    val chooserTitle = when {
                        mimeType.startsWith("audio/") -> "Listen to ${file.name}"
                        mimeType.startsWith("video/") -> "Watch ${file.name}"
                        mimeType.startsWith("image/") -> "View ${file.name}"
                        file.extension.lowercase() == "pdf" -> "Read ${file.name}"
                        file.extension.lowercase() == "apk" -> "Install ${file.name}"
                        else -> "Open ${file.name} with..."
                    }

                    intent.setDataAndType(uri, mimeType)
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(android.content.Intent.createChooser(intent, chooserTitle))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun askAiAboutFile(file: File) {
        addStagedFile(file)
        currentView = "chat"
        selectedTab = 0
    }

    fun prepareExtraction() {
        updateExtractionQueue(selectedFiles.toList())
        updateExtractionMode(true)
        clearSelection()
    }

    fun cancelExtraction() {
        updateExtractionQueue(emptyList())
        updateExtractionMode(false)
    }

    fun performExtraction(context: android.content.Context) {
        val destination = explorerPath
        val items = extractionQueue
        updateExtractionMode(false)
        extractionQueue = emptyList()
        
        // Give immediate UI feedback
        isDataLoading = true
        android.widget.Toast.makeText(context, "Extracting...", android.widget.Toast.LENGTH_SHORT).show()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                items.forEach { item ->
                    val isVirtual = item.path.contains("!/")
                    val zipPath = if (isVirtual) item.path.substringBefore("!/") else item.path
                    val internalPath = if (isVirtual) item.path.substringAfter("!/") else ""
                    
                    ZipFile(File(zipPath)).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            
                            var entryName = entry.name.replace("\\", "/")
                            while (entryName.contains("//")) entryName = entryName.replace("//", "/")
                            while (entryName.startsWith("/")) entryName = entryName.substring(1)
                            while (entryName.startsWith("./")) entryName = entryName.substring(2)

                            if (entryName.startsWith(internalPath)) {
                                val relativePath = if (internalPath.isEmpty()) entryName else entryName.substring(internalPath.length)
                                if (relativePath.isEmpty()) continue
                                
                                val outFile = File(destination, relativePath)
                                if (entry.isDirectory || entryName.endsWith("/")) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    zip.getInputStream(entry).use { input ->
                                        outFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isDataLoading = false
                    android.widget.Toast.makeText(context, "Extraction complete", android.widget.Toast.LENGTH_SHORT).show()
                    loadExplorerFolder(explorerPath)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDataLoading = false
                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun openFileForEditing(file: File) {
        isDataLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = file.readText()
                withContext(Dispatchers.Main) {
                    editingFile = file
                    savedStateHandle["editingFilePath"] = file.absolutePath
                    editorText = content
                    hasUnsavedChanges = false
                    isEditLocked = true
                    recoveryFile?.delete() // Clear old recovery on new file open
                    isDataLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    editorText = "Error reading file: ${e.message}"
                    isDataLoading = false
                }
            }
        }
    }

    fun closeEditor() {
        editingFile = null
        savedStateHandle.remove<String>("editingFilePath")
        recoveryFile?.delete()
        editorText = ""
        hasUnsavedChanges = false
    }

    fun saveCurrentFile(textToSave: String) {
        editingFile?.let { file ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    file.writeText(textToSave)
                    withContext(Dispatchers.Main) {
                        hasUnsavedChanges = false
                        isEditLocked = true
                    }
                } catch (e: Exception) { }
            }
        }
    }

    fun onRetryMessage(context: android.content.Context, message: Message) {
        if (!message.isAi) {
            editingMessageTimestamp = message.timestamp
            chatInput = message.text
            sendMessage(context)
        } else {
            val msgIndex = chatMessages.indexOf(message)
            if (msgIndex > 0) {
                val previousMsg = chatMessages[msgIndex - 1]
                if (!previousMsg.isAi && !previousMsg.isSystemAction) {
                    editingMessageTimestamp = previousMsg.timestamp
                    chatInput = previousMsg.text
                    sendMessage(context)
                }
            }
        }
    }

    fun injectStructure() {
        viewModelScope.launch(Dispatchers.IO) {
            val structure = try {
                executor.callAttr("get_structure").toString()
            } catch (e: Exception) { "Error: ${e.message}" }
            withContext(Dispatchers.Main) {
                chatInput = "Context Update - Structure:\n$structure"
                // Note: User still needs to trigger send or we call it here
            }
        }
    }
}
