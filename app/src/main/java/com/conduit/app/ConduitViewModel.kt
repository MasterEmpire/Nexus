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
    var currentView by mutableStateOf(savedStateHandle.get<String>("currentView") ?: "explorer")
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
    var explorerEntryPath by mutableStateOf<String?>(null)
    var searchQuery by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    var selectedFiles by mutableStateOf<Set<FileItem>>(emptySet())
    val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun getExplorerScrollKey(): String {
        return if (explorerCategory == "Internal Storage") "Internal Storage:$explorerPath"
        else explorerCategory ?: "Dashboard"
    }

    var pinnedPaths by mutableStateOf<Set<String>>(emptySet())
        private set

    var favoritePaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var dashboardListMode by mutableStateOf("Recent")
    var dashboardFavoriteFiles by mutableStateOf<List<FileItem>>(emptyList())

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
        refreshCurrentExplorerView(context)
    }

    fun loadFavoritePaths(context: android.content.Context) {
        val file = File(context.filesDir, "favorites.json")
        if (file.exists()) {
            try {
                val json = org.json.JSONArray(file.readText())
                val set = mutableSetOf<String>()
                for (i in 0 until json.length()) set.add(json.getString(i))
                favoritePaths = set
            } catch (_: Exception) {}
        }
        refreshFavorites(context)
    }

    fun saveFavoritePaths(context: android.content.Context) {
        val file = File(context.filesDir, "favorites.json")
        val json = org.json.JSONArray()
        favoritePaths.forEach { json.put(it) }
        viewModelScope.launch(Dispatchers.IO) {
            try { file.writeText(json.toString()) } catch (_: Exception) {}
        }
    }

    fun toggleFavoriteForSelected(context: android.content.Context) {
        val selected = selectedFiles.map { it.path }
        val allFavorited = selected.isNotEmpty() && selected.all { it in favoritePaths }
        favoritePaths = if (allFavorited) favoritePaths - selected.toSet() else favoritePaths + selected.toSet()
        saveFavoritePaths(context)
        val msg = if (allFavorited) "Removed from Favorites" else "Added to Favorites"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        clearSelection()
        refreshFavorites(context)
    }

    fun refreshFavorites(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val validPaths = favoritePaths.filter { File(it).exists() }.toSet()
            if (validPaths.size != favoritePaths.size) {
                favoritePaths = validPaths
                saveFavoritePaths(context)
            }
            val items = validPaths.mapNotNull { path ->
                try {
                    val f = File(path)
                    if (!f.exists()) return@mapNotNull null
                    val sizeStr = if (f.isDirectory) "--" else FileScanner.formatSize(f.length())
                    val timeStr = FileScanner.formatTimestamp(f.lastModified())
                    val parentFolder = path.split("/").dropLast(1).lastOrNull() ?: ""
                    val metaParts = listOf(sizeStr, timeStr, parentFolder).filter { it.isNotBlank() }
                    val (fileIcon, fileColor) = FileScanner.resolveIconAndColor(f.name, isDirectory = f.isDirectory)

                    FileItem(
                        name = f.name,
                        meta = metaParts.joinToString(" • "),
                        icon = fileIcon,
                        color = fileColor,
                        path = f.absolutePath,
                        lastModified = f.lastModified(),
                        size = f.length()
                    )
                } catch (_: Exception) { null }
            }.sortedByDescending { it.lastModified }

            withContext(Dispatchers.Main) {
                dashboardFavoriteFiles = items
            }
        }
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

    var showExtractionConflictDialog by mutableStateOf(false)
    var extractionConflicts by mutableStateOf<List<String>>(emptyList())

    var extensionDefaults by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var pendingOpenFile by mutableStateOf<File?>(null)
    var pendingOpenIntent by mutableStateOf<android.content.Intent?>(null)
    var pendingAppOptions by mutableStateOf<List<AppOption>>(emptyList())
    var showAppChooserDialog by mutableStateOf(false)

    var transferQueue by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var isMoveOperation by mutableStateOf(false)
        private set
    var isTransferMode by mutableStateOf(false)
        private set
    var showTransferConflictDialog by mutableStateOf(false)
    var transferConflicts by mutableStateOf<List<String>>(emptyList())

    var isPickerMode by mutableStateOf(savedStateHandle.get<Boolean>("isPickerMode") ?: false)
    var pickerAllowMultiple by mutableStateOf(savedStateHandle.get<Boolean>("pickerAllowMultiple") ?: false)
    var pickerMimeFilter by mutableStateOf(savedStateHandle.get<String>("pickerMimeFilter") ?: "*/*")

    fun finishPicking(context: android.content.Context, files: List<FileItem>) {
        val act = context as? android.app.Activity ?: return
        if (files.isEmpty()) {
            cancelPicking(context)
            return
        }

        val uris = files.mapNotNull { item ->
            try {
                val f = File(item.path)
                if (f.exists() && f.isFile) {
                    androidx.core.content.FileProvider.getUriForFile(context, "com.conduit.nexus.provider", f)
                } else null
            } catch (_: Exception) { null }
        }

        if (uris.isEmpty()) {
            cancelPicking(context)
            return
        }

        val parentPath = try {
            val f = File(files.first().path)
            if (f.isDirectory) f.absolutePath else f.parent ?: "/storage/emulated/0"
        } catch (_: Exception) { "/storage/emulated/0" }

        val resultIntent = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            data = uris.first()
            val clipData = android.content.ClipData.newUri(context.contentResolver, "Selected Files", uris.first())
            for (i in 1 until uris.size) {
                clipData.addItem(android.content.ClipData.Item(uris[i]))
            }
            setClipData(clipData)
            putExtra("extra_parent_path", parentPath)
        }
        act.setResult(android.app.Activity.RESULT_OK, resultIntent)
        act.finish()
    }

    fun cancelPicking(context: android.content.Context) {
        val act = context as? android.app.Activity ?: return
        act.setResult(android.app.Activity.RESULT_CANCELED)
        act.finish()
    }

    var executionProfile by mutableStateOf("Standard")
        private set

    var defaultBootSection by mutableStateOf("File Manager")
        private set

    fun loadSettings(context: android.content.Context) {
        val prefs = context.getSharedPreferences("conduit_settings", android.content.Context.MODE_PRIVATE)
        executionProfile = prefs.getString("exec_profile", "Standard") ?: "Standard"
        defaultBootSection = prefs.getString("default_boot_section", "File Manager") ?: "File Manager"
        val savedSort = prefs.getString("explorer_sort_order", SortOrder.DATE_NEW.name)
        currentSortOrder = try {
            SortOrder.valueOf(savedSort ?: SortOrder.DATE_NEW.name)
        } catch (_: Exception) {
            SortOrder.DATE_NEW
        }

        if (savedStateHandle.get<String>("currentView") == null && !isPickerMode) {
            when (defaultBootSection) {
                "AI Chat" -> { currentView = "chat"; selectedTab = 0 }
                "Manual Lab" -> { currentView = "manual" }
                else -> { currentView = "explorer"; selectedTab = 1 }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                executor.callAttr("set_profile", executionProfile)
            } catch(e: Exception) {}
        }
        loadExtensionDefaults(context)
    }

    fun loadExtensionDefaults(context: android.content.Context) {
        val file = File(context.filesDir, "extension_defaults.json")
        if (file.exists()) {
            try {
                val json = org.json.JSONObject(file.readText())
                val map = mutableMapOf<String, String>()
                json.keys().forEach { ext ->
                    map[ext] = json.getString(ext)
                }
                extensionDefaults = map
            } catch (_: Exception) {}
        }
    }

    private fun saveExtensionDefaults(context: android.content.Context) {
        val file = File(context.filesDir, "extension_defaults.json")
        val json = org.json.JSONObject()
        extensionDefaults.forEach { (ext, pkg) -> json.put(ext, pkg) }
        viewModelScope.launch(Dispatchers.IO) {
            try { file.writeText(json.toString()) } catch (_: Exception) {}
        }
    }

    fun setExtensionDefault(context: android.content.Context, ext: String, packageName: String) {
        extensionDefaults = extensionDefaults + (ext.lowercase() to packageName)
        saveExtensionDefaults(context)
    }

    fun clearExtensionDefault(context: android.content.Context, ext: String) {
        extensionDefaults = extensionDefaults - ext.lowercase()
        saveExtensionDefaults(context)
    }

    fun clearAllExtensionDefaults(context: android.content.Context) {
        extensionDefaults = emptyMap()
        saveExtensionDefaults(context)
    }

    fun launchPendingApp(context: android.content.Context, packageName: String, saveAsDefault: Boolean) {
        val file = pendingOpenFile ?: return
        val intent = pendingOpenIntent ?: return
        val ext = file.extension.lowercase()

        if (packageName == "__nexus_editor__") {
            if (saveAsDefault && ext.isNotBlank()) {
                clearExtensionDefault(context, ext)
            }
            showAppChooserDialog = false
            pendingOpenFile = null
            pendingOpenIntent = null
            pendingAppOptions = emptyList()
            openFileForEditing(file)
            return
        }

        if (saveAsDefault && ext.isNotBlank()) {
            setExtensionDefault(context, ext, packageName)
        }

        intent.setPackage(packageName)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Failed to launch app: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        } finally {
            showAppChooserDialog = false
            pendingOpenFile = null
            pendingOpenIntent = null
            pendingAppOptions = emptyList()
        }
    }

    fun updateSortOrder(context: android.content.Context, order: SortOrder) {
        currentSortOrder = order
        context.getSharedPreferences("conduit_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("explorer_sort_order", order.name).apply()
        refreshCurrentExplorerView(context)
    }

    fun updateDefaultBootSection(context: android.content.Context, section: String) {
        defaultBootSection = section
        context.getSharedPreferences("conduit_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("default_boot_section", section).apply()
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
    var editorLines by mutableStateOf<List<String>>(emptyList())
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
    var isManualAwaitingInput by mutableStateOf(false)
    var manualInputPrompt by mutableStateOf("")
    var manualInputText by mutableStateOf("")
    var savedScripts by mutableStateOf<List<SavedScript>>(emptyList())
    var activeScriptId by mutableStateOf<String?>(savedStateHandle.get<String>("activeScriptId"))
    var selectedTab by mutableIntStateOf(if (savedStateHandle.get<String>("currentView") == "chat") 0 else 1)
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

    private fun generateSmartTitle(code: String): String {
        val lines = code.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: "Untitled Script"
        val base = when {
            first.startsWith("#") -> first.removePrefix("#").trim().take(34)
            else -> {
                val defMatch = Regex("""^(?:async\s+)?def\s+([a-zA-Z_]\w*)\s*\(""").find(
                    lines.take(10).firstOrNull { it.startsWith("def ") || it.startsWith("async def ") } ?: ""
                )
                val classMatch = Regex("""^class\s+([a-zA-Z_]\w*)""").find(
                    lines.take(10).firstOrNull { it.startsWith("class ") } ?: ""
                )
                when {
                    defMatch != null -> "fn: ${defMatch.groupValues[1]}"
                    classMatch != null -> "class: ${classMatch.groupValues[1]}"
                    else -> first.take(30)
                }
            }
        }.ifBlank { "Untitled Script" }

        val timeSuffix = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val collision = savedScripts.any { it.id != activeScriptId && it.title.equals(base, ignoreCase = true) }
        return if (collision) "$base • $timeSuffix" else base
    }

    fun selectScript(script: SavedScript) {
        activeScriptId = script.id
        savedStateHandle["activeScriptId"] = script.id
        manualCode = script.code
    }

    fun createNewScript() {
        activeScriptId = null
        savedStateHandle.remove<String>("activeScriptId")
        manualCode = ""
        manualConsoleOutput = ""
    }

    fun saveScript(context: android.content.Context, code: String, customTitle: String? = null, isPaste: Boolean = false) {
        if (code.isBlank()) return
        val currentId = activeScriptId
        val activeExisting = if (currentId != null) savedScripts.find { it.id == currentId } else null

        if (activeExisting != null) {
            val updatedTitle = customTitle ?: if (isPaste && activeExisting.title.startsWith("Script ")) {
                generateSmartTitle(code)
            } else {
                activeExisting.title
            }
            val updated = activeExisting.copy(
                title = updatedTitle,
                code = code,
                timestamp = System.currentTimeMillis()
            )
            savedScripts = listOf(updated) + savedScripts.filter { it.id != currentId }
            persistScripts(context)
            return
        }

        val duplicate = savedScripts.find { it.code.trim() == code.trim() }
        if (duplicate != null) {
            activeScriptId = duplicate.id
            savedStateHandle["activeScriptId"] = duplicate.id
            return
        }

        val newScript = SavedScript(
            title = customTitle ?: generateSmartTitle(code),
            code = code
        )
        activeScriptId = newScript.id
        savedStateHandle["activeScriptId"] = newScript.id
        savedScripts = (listOf(newScript) + savedScripts).take(50)
        persistScripts(context)
    }

    fun deleteScript(context: android.content.Context, id: String) {
        savedScripts = savedScripts.filter { it.id != id }
        if (activeScriptId == id) {
            activeScriptId = null
            savedStateHandle.remove<String>("activeScriptId")
        }
        persistScripts(context)
    }

    fun renameScript(context: android.content.Context, id: String, newTitle: String) {
        savedScripts = savedScripts.map { if (it.id == id) it.copy(title = newTitle) else it }
        persistScripts(context)
    }

    fun clearManualConsole() {
        manualConsoleOutput = ""
    }

    fun stopManualCode() {
        ConduitExecutionService.stopExecution()
        isManualAwaitingInput = false
        manualInputPrompt = ""
        manualInputText = ""
    }

    fun runManualCode(context: android.content.Context) {
        if (ConduitExecutionService.isRunning) return
        saveScript(context, manualCode)
        manualConsoleOutput = ""
        isManualRunning = true
        isManualAwaitingInput = false
        manualInputPrompt = ""
        manualInputText = ""
        
        // Setup the bridge listeners
        ConduitExecutionService.outputListener = { text ->
            viewModelScope.launch(Dispatchers.Main) {
                manualConsoleOutput += text
            }
        }

        ConduitExecutionService.inputRequestListener = { prompt ->
            viewModelScope.launch(Dispatchers.Main) {
                isManualAwaitingInput = true
                manualInputPrompt = prompt
            }
        }
        
        ConduitExecutionService.onFinished = {
            viewModelScope.launch(Dispatchers.Main) {
                isManualRunning = false
                isManualAwaitingInput = false
                manualInputPrompt = ""
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

    fun sendManualInput(text: String) {
        if (!isManualAwaitingInput) return
        manualConsoleOutput += "$text\n"
        ConduitExecutionService.provideInput(text)
        isManualAwaitingInput = false
        manualInputPrompt = ""
        manualInputText = ""
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
            isManualAwaitingInput = ConduitExecutionService.isWaitingForInput
            ConduitExecutionService.outputListener = { text ->
                viewModelScope.launch(Dispatchers.Main) {
                    manualConsoleOutput += text
                }
            }
            ConduitExecutionService.inputRequestListener = { prompt ->
                viewModelScope.launch(Dispatchers.Main) {
                    isManualAwaitingInput = true
                    manualInputPrompt = prompt
                }
            }
            ConduitExecutionService.onFinished = {
                viewModelScope.launch(Dispatchers.Main) {
                    isManualRunning = false
                    isManualAwaitingInput = false
                    manualInputPrompt = ""
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
                    val text = recoveryFile?.readText() ?: ""
                    editorText = text
                    editorLines = text.lines()
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
        refreshFavorites(context)
    }

    fun sortWithPins(items: List<FileItem>): List<FileItem> {
        val comparator = Comparator<FileItem> { a, b ->
            val aIsDir = a.icon == Icons.Default.Folder || a.icon == Icons.Default.FolderZip
            val bIsDir = b.icon == Icons.Default.Folder || b.icon == Icons.Default.FolderZip
            if (aIsDir != bIsDir) return@Comparator if (aIsDir) -1 else 1
            when (currentSortOrder) {
                SortOrder.NAME_ASC -> a.name.lowercase().compareTo(b.name.lowercase())
                SortOrder.NAME_DESC -> b.name.lowercase().compareTo(a.name.lowercase())
                SortOrder.DATE_NEW -> b.lastModified.compareTo(a.lastModified)
                SortOrder.DATE_OLD -> a.lastModified.compareTo(b.lastModified)
                SortOrder.SIZE_LARGE -> b.size.compareTo(a.size)
                SortOrder.SIZE_SMALL -> a.size.compareTo(b.size)
            }
        }
        return items.sortedWith(comparator).sortedByDescending { it.path in pinnedPaths }
    }

    fun refreshCurrentExplorerView(context: android.content.Context) {
        when (val cat = explorerCategory) {
            null -> refreshDashboard(context)
            "Internal Storage" -> loadExplorerFolder(explorerPath, context)
            "Search Results" -> {
                if (searchQuery.isNotBlank()) {
                    isScanningExplorer = true
                    viewModelScope.launch(Dispatchers.IO) {
                        val target = if (explorerPath != "/storage/emulated/0") explorerPath else null
                        val results = FileScanner.searchFilesNative(context, searchQuery, target)
                        val sorted = sortWithPins(results)
                        withContext(Dispatchers.Main) {
                            explorerFiles = sorted
                            isScanningExplorer = false
                        }
                    }
                } else {
                    explorerFiles = sortWithPins(explorerFiles)
                }
            }
            else -> openExplorerCategory(context, cat)
        }
    }

    fun loadExplorerFolder(path: String, context: android.content.Context? = null) {
        if (explorerCategory != "Internal Storage" && explorerCategory != null) {
            explorerReturnCategory = explorerCategory
            explorerEntryPath = path
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
                "Favorites" -> {
                    val validPaths = favoritePaths.filter { File(it).exists() }.toSet()
                    validPaths.mapNotNull { path ->
                        try {
                            val f = File(path)
                            if (!f.exists()) return@mapNotNull null
                            val sizeStr = if (f.isDirectory) "--" else FileScanner.formatSize(f.length())
                            val timeStr = FileScanner.formatTimestamp(f.lastModified())
                            val parentFolder = path.split("/").dropLast(1).lastOrNull() ?: ""
                            val metaParts = listOf(sizeStr, timeStr, parentFolder).filter { it.isNotBlank() }
                            val (fileIcon, fileColor) = FileScanner.resolveIconAndColor(f.name, isDirectory = f.isDirectory)

                            FileItem(
                                name = f.name,
                                meta = metaParts.joinToString(" • "),
                                icon = fileIcon,
                                color = fileColor,
                                path = f.absolutePath,
                                lastModified = f.lastModified(),
                                size = f.length()
                            )
                        } catch (_: Exception) { null }
                    }
                }
                "Images", "Videos", "Audio" -> FileScanner.getMediaFiles(context, cat)
                "Internal Storage" -> {
                    explorerPath = "/storage/emulated/0"
                    FileScanner.getDirectoryContents(explorerPath, currentSortOrder, folderCountCache)
                }
                "Downloads" -> {
                    explorerPath = "/storage/emulated/0/Download"
                    FileScanner.getDirectoryContents(explorerPath, currentSortOrder, folderCountCache)
                }
                else -> {
                    val python = com.chaquo.python.Python.getInstance()
                    val executor = python.getModule("executor")
                    val list = executor.callAttr("find_files_by_type", cat.lowercase()).asList()
                    list.mapNotNull {
                        val p = it.toString()
                        val f = File(p)
                        if (!f.exists() || f.isDirectory) return@mapNotNull null

                        val sizeStr = FileScanner.formatSize(f.length())
                        val lastMod = f.lastModified()
                        val timeStr = FileScanner.formatTimestamp(lastMod)
                        val parentFolder = p.split("/").dropLast(1).lastOrNull() ?: ""
                        val metaParts = listOf(sizeStr, timeStr, parentFolder).filter { it.isNotBlank() }
                        val (fileIcon, fileColor) = FileScanner.resolveIconAndColor(f.name, isDirectory = false)

                        FileItem(
                            name = f.name,
                            meta = metaParts.joinToString(" • "),
                            icon = fileIcon,
                            color = fileColor,
                            path = p,
                            lastModified = lastMod,
                            size = f.length()
                        )
                    }
                }
            }
            val sortedResults = if (cat == "Internal Storage" || cat == "Downloads") {
                results.sortedByDescending { it.path in pinnedPaths }
            } else {
                sortWithPins(results)
            }
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
                        if (oldPath in favoritePaths) {
                            favoritePaths = (favoritePaths - oldPath) + newFile.absolutePath
                            saveFavoritePaths(context)
                            refreshFavorites(context)
                        }
                        if (oldPath in pinnedPaths) {
                            pinnedPaths = (pinnedPaths - oldPath) + newFile.absolutePath
                            savePinnedPaths(context)
                        }
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
            val deletedPaths = mutableSetOf<String>()
            filesToDelete.forEach { item ->
                val file = File(item.path)
                if (if (file.isDirectory) file.deleteRecursively() else file.delete()) {
                    successCount++
                    deletedPaths.add(item.path)
                }
                android.media.MediaScannerConnection.scanFile(context, arrayOf(item.path), null, null)
            }
            withContext(Dispatchers.Main) {
                if (deletedPaths.any { it in favoritePaths }) {
                    favoritePaths = favoritePaths - deletedPaths
                    saveFavoritePaths(context)
                    refreshFavorites(context)
                }
                if (deletedPaths.any { it in pinnedPaths }) {
                    pinnedPaths = pinnedPaths - deletedPaths
                    savePinnedPaths(context)
                }
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

    private fun isEditableTextFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', "")
        val textExts = setOf(
            "txt", "py", "pyw", "js", "mjs", "cjs", "json", "kt", "kts", "java", "html", "htm",
            "css", "scss", "md", "markdown", "yml", "yaml", "c", "cpp", "cc", "h", "hpp", "ts",
            "tsx", "jsx", "xml", "svg", "sql", "log", "env", "ini", "cfg", "conf", "sh", "bash",
            "zsh", "bat", "cmd", "ps1", "gradle", "properties", "toml", "csv", "tsv", "rst",
            "tex", "srt", "vtt", "sub", "ass", "rs", "go", "rb", "php", "dart", "swift", "lua",
            "gitignore", "gitattributes", "editorconfig", "lock", "manifest", "reg", "inf"
        )
        val knownNames = setOf(
            "readme", "license", "makefile", "dockerfile", "procfile",
            "gemfile", "rakefile", "changelog", "authors", "notice", "hosts"
        )
        return (ext.isNotEmpty() && ext in textExts) || lower in knownNames
    }

    fun onFileClicked(fileItem: FileItem, context: android.content.Context, loadFolder: (String) -> Unit) {
        // Handle Virtual Archive Paths Safely
        if (fileItem.path.contains("!/")) {
            if (fileItem.path.endsWith("/")) {
                loadFolder(fileItem.path)
            } else {
                val entryFileName = fileItem.path.substringAfterLast('/')
                if (isEditableTextFile(entryFileName)) {
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
                } else if (entryFileName.lowercase().endsWith(".apk")) {
                    isDataLoading = true
                    android.widget.Toast.makeText(context, "Preparing APK installer...", android.widget.Toast.LENGTH_SHORT).show()
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val zipPath = fileItem.path.substringBefore("!/")
                            val internalPath = fileItem.path.substringAfter("!/")
                            val apkCacheDir = File(context.cacheDir, "apk_installs").apply {
                                if (exists()) listFiles()?.forEach { it.delete() }
                                mkdirs()
                            }
                            val outApk = File(apkCacheDir, entryFileName)
                            var found = false
                            ZipFile(File(zipPath)).use { zip ->
                                val entries = zip.entries()
                                while (entries.hasMoreElements()) {
                                    val entry = entries.nextElement()
                                    val entryName = normalizeZipEntryName(entry.name)
                                    if (entryName == internalPath) {
                                        zip.getInputStream(entry).use { input ->
                                            outApk.outputStream().use { output -> input.copyTo(output) }
                                        }
                                        found = true
                                        break
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                isDataLoading = false
                                if (found && outApk.exists()) {
                                    installApkFile(context, outApk)
                                } else {
                                    android.widget.Toast.makeText(context, "APK not found in archive", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isDataLoading = false
                                android.widget.Toast.makeText(context, "Cannot prepare APK: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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
            loadFolder(file.absolutePath)
        } else {
            val ext = file.extension.lowercase()
            if (ext == "zip") {
                loadFolder("${file.absolutePath}!/")
                return
            }

            val defaultPackage = if (ext.isNotBlank()) extensionDefaults[ext] else null
            if (defaultPackage != null) {
                val isInstalled = try {
                    context.packageManager.getPackageInfo(defaultPackage, 0)
                    true
                } catch (_: Exception) { false }

                if (isInstalled) {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val explicitMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        val mimeType = explicitMime ?: if (isEditableTextFile(file.name)) "text/plain" else (context.contentResolver.getType(uri) ?: "*/*")
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            setPackage(defaultPackage)
                        }
                        context.startActivity(intent)
                        return
                    } catch (_: Exception) {}
                } else {
                    clearExtensionDefault(context, ext)
                }
            }

            if (ext == "apk") {
                installApkFile(context, file)
            } else if (isEditableTextFile(file.name)) {
                openFileForEditing(file)
            } else {
                openFileWithChooser(context, file, forceChooser = false)
            }
        }
    }

    fun installApkFile(context: android.content.Context, apkFile: File) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(
                    context,
                    "Enable 'Install unknown apps' for Nexus to install APKs",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Error launching installer: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun openFileWithChooser(context: android.content.Context, file: File, forceChooser: Boolean = true) {
        try {
            val ext = file.extension.lowercase()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val explicitMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            val mimeType = when {
                ext == "apk" -> "application/vnd.android.package-archive"
                explicitMime != null -> explicitMime
                isEditableTextFile(file.name) -> "text/plain"
                else -> context.contentResolver.getType(uri) ?: "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                context.packageManager.queryIntentActivities(intent, 0)
            }

            val externalOptions = resolveInfos.map { ri ->
                AppOption(
                    packageName = ri.activityInfo.packageName,
                    label = ri.loadLabel(context.packageManager).toString(),
                    icon = ri.loadIcon(context.packageManager)
                )
            }.distinctBy { it.packageName }

            val options = if (isEditableTextFile(file.name)) {
                val nexusOption = AppOption(
                    packageName = "__nexus_editor__",
                    label = "Nexus Editor (Built-in)",
                    icon = context.applicationInfo.loadIcon(context.packageManager)
                )
                listOf(nexusOption) + externalOptions
            } else {
                externalOptions
            }

            if (options.isEmpty()) {
                android.widget.Toast.makeText(context, "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            if (!forceChooser && options.size == 1) {
                launchPendingAppForSingle(context, intent, options.first().packageName)
                return
            }

            pendingOpenFile = file
            pendingOpenIntent = intent
            pendingAppOptions = options
            showAppChooserDialog = true
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Error opening file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPendingAppForSingle(context: android.content.Context, intent: Intent, packageName: String) {
        intent.setPackage(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Error opening file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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

    fun prepareTransfer(isMove: Boolean) {
        transferQueue = selectedFiles.toList()
        isMoveOperation = isMove
        isTransferMode = true
        clearSelection()
    }

    fun cancelTransfer() {
        transferQueue = emptyList()
        isTransferMode = false
        isMoveOperation = false
        showTransferConflictDialog = false
        transferConflicts = emptyList()
    }

    private fun resolveDestinationFile(
        destDir: File,
        desiredRelativePath: String,
        resolution: ConflictResolution
    ): File? {
        val target = File(destDir, desiredRelativePath)
        if (!target.exists()) return target

        return when (resolution) {
            ConflictResolution.REPLACE -> target
            ConflictResolution.SKIP -> null
            ConflictResolution.KEEP_BOTH -> {
                val parentDir = target.parentFile ?: destDir
                val fileName = target.name
                val nameWithoutExt = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName
                val ext = if (fileName.contains(".")) "." + fileName.substringAfterLast(".") else ""
                var counter = 1
                var candidate = File(parentDir, "$nameWithoutExt($counter)$ext")
                while (candidate.exists()) {
                    counter++
                    candidate = File(parentDir, "$nameWithoutExt($counter)$ext")
                }
                candidate
            }
        }
    }
    private fun resolveDestinationDirectory(): File {
        val destPath = if (explorerPath.contains("!/")) {
            explorerPath.substringBefore("!/").let { File(it).parent ?: "/storage/emulated/0" }
        } else {
            explorerPath
        }
        return File(destPath)
    }

    private fun normalizeZipEntryName(raw: String): String {
        var name = raw.replace("\\", "/")
        while (name.contains("//")) name = name.replace("//", "/")
        while (name.startsWith("/")) name = name.substring(1)
        while (name.startsWith("./")) name = name.substring(2)
        return name
    }

    private fun copyFileOrDir(src: File, dst: File, overwrite: Boolean) {
        if (src.isDirectory) {
            if (dst.exists() && !dst.isDirectory) {
                if (overwrite) {
                    if (!dst.delete()) throw Exception("Cannot overwrite file ${dst.name}")
                } else {
                    throw Exception("${dst.name} already exists")
                }
            }
            if (!dst.exists() && !dst.mkdirs()) {
                throw Exception("Cannot create folder ${dst.name}")
            }
            val children = src.listFiles() ?: emptyArray()
            for (child in children) {
                copyFileOrDir(child, File(dst, child.name), overwrite)
            }
        } else {
            dst.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw Exception("Cannot create folder ${parent.name}")
                }
            }
            if (dst.exists()) {
                if (!overwrite) return
                if (dst.isDirectory) {
                    if (!dst.deleteRecursively()) throw Exception("Cannot overwrite folder ${dst.name}")
                } else if (!dst.delete()) {
                    throw Exception("Cannot overwrite ${dst.name}")
                }
            }
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun detectExtractionConflicts(): List<String> {
        val destDir = resolveDestinationDirectory()
        val conflicts = mutableSetOf<String>()

        extractionQueue.forEach { item ->
            val isVirtual = item.path.contains("!/")
            val zipPath = if (isVirtual) item.path.substringBefore("!/") else item.path
            val internalPath = if (isVirtual) item.path.substringAfter("!/").trimStart('/') else ""
            val isSingleVirtualFile = isVirtual && !internalPath.endsWith("/") && !item.meta.contains("Folder")

            try {
                ZipFile(File(zipPath)).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryName = normalizeZipEntryName(entry.name)
                        if (entryName.isEmpty() || entryName.split("/").any { it == ".." }) continue

                        if (isSingleVirtualFile) {
                            if (entryName == internalPath) {
                                val fileName = File(internalPath).name
                                if (File(destDir, fileName).exists()) {
                                    conflicts.add(fileName)
                                }
                            }
                        } else if (isVirtual) {
                            val cleanFolder = internalPath.removeSuffix("/")
                            if (entryName == cleanFolder || entryName.startsWith("$cleanFolder/")) {
                                val folderBase = File(cleanFolder).name
                                val subPath = if (entryName == cleanFolder) "" else entryName.removePrefix("$cleanFolder/")
                                val relPath = if (subPath.isEmpty()) folderBase else "$folderBase/$subPath"
                                if (!entry.isDirectory && !entryName.endsWith("/") && File(destDir, relPath).exists()) {
                                    conflicts.add(File(relPath).name)
                                }
                            }
                        } else {
                            if (!entry.isDirectory && !entryName.endsWith("/") && File(destDir, entryName).exists()) {
                                conflicts.add(File(entryName).name)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return conflicts.toList()
    }

    fun startExtraction(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val conflicts = detectExtractionConflicts()
            withContext(Dispatchers.Main) {
                if (conflicts.isNotEmpty()) {
                    extractionConflicts = conflicts
                    showExtractionConflictDialog = true
                } else {
                    executeExtraction(context, ConflictResolution.REPLACE)
                }
            }
        }
    }

    fun performExtraction(context: android.content.Context) {
        startExtraction(context)
    }

    private fun detectTransferConflicts(): List<String> {
        val destDir = resolveDestinationDirectory()
        val conflicts = mutableListOf<String>()

        transferQueue.forEach { item ->
            val src = File(item.path)
            if (!src.exists()) return@forEach
            val candidate = File(destDir, src.name)
            if (candidate.exists()) {
                val isSameMove = isMoveOperation && (src.canonicalPath == candidate.canonicalPath)
                if (!isSameMove) {
                    conflicts.add(src.name)
                }
            }
        }
        return conflicts
    }

    fun startTransfer(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val conflicts = detectTransferConflicts()
            withContext(Dispatchers.Main) {
                if (conflicts.isNotEmpty()) {
                    transferConflicts = conflicts
                    showTransferConflictDialog = true
                } else {
                    executeTransfer(context, ConflictResolution.REPLACE)
                }
            }
        }
    }

    fun executeTransfer(
        context: android.content.Context,
        resolution: ConflictResolution = ConflictResolution.REPLACE
    ) {
        val destDir = resolveDestinationDirectory()
        val items = transferQueue
        val isMove = isMoveOperation

        transferQueue = emptyList()
        isTransferMode = false
        isMoveOperation = false
        showTransferConflictDialog = false
        transferConflicts = emptyList()

        if (items.isEmpty()) return

        val actionName = if (isMove) "Moving" else "Copying"
        isDataLoading = true
        android.widget.Toast.makeText(context, "$actionName ${items.size} item(s)...", android.widget.Toast.LENGTH_SHORT).show()

        viewModelScope.launch(Dispatchers.IO) {
            val affectedPaths = mutableListOf<String>()
            val movedPairs = mutableListOf<Pair<String, String>>()
            var successCount = 0
            var skippedCount = 0
            val errorMessages = mutableListOf<String>()

            try {
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw Exception("Cannot access destination folder ${destDir.name}")
                }

                items.forEach { item ->
                    val src = File(item.path)
                    if (!src.exists()) {
                        errorMessages.add("Source not found: ${item.name}")
                        return@forEach
                    }

                    val srcCanon = src.canonicalPath
                    val destCanon = destDir.canonicalPath
                    if (src.isDirectory && (destCanon == srcCanon || destCanon.startsWith("$srcCanon${File.separator}"))) {
                        errorMessages.add("Cannot ${if (isMove) "move" else "copy"} '${src.name}' into itself")
                        return@forEach
                    }

                    val directTarget = File(destDir, src.name)
                    if (srcCanon == directTarget.canonicalPath) {
                        if (isMove || resolution == ConflictResolution.REPLACE || resolution == ConflictResolution.SKIP) {
                            skippedCount++
                            return@forEach
                        }
                    }

                    val outFile = resolveDestinationFile(destDir, src.name, resolution)
                    if (outFile == null) {
                        skippedCount++
                        return@forEach
                    }

                    try {
                        if (isMove) {
                            val fastRenamed = !outFile.exists() && src.renameTo(outFile)
                            if (!fastRenamed) {
                                copyFileOrDir(src, outFile, overwrite = (resolution == ConflictResolution.REPLACE))
                                if (!outFile.exists()) {
                                    throw Exception("Failed to write ${outFile.name}")
                                }
                                val deletedSrc = if (src.isDirectory) src.deleteRecursively() else src.delete()
                                if (!deletedSrc) {
                                    throw Exception("Copied ${outFile.name}, but could not remove original")
                                }
                            }
                            successCount++
                            affectedPaths.add(src.absolutePath)
                            affectedPaths.add(outFile.absolutePath)
                            movedPairs.add(src.absolutePath to outFile.absolutePath)
                        } else {
                            copyFileOrDir(src, outFile, overwrite = (resolution == ConflictResolution.REPLACE))
                            successCount++
                            affectedPaths.add(outFile.absolutePath)
                        }
                    } catch (e: Exception) {
                        errorMessages.add("${src.name}: ${e.message ?: "Failed"}")
                    }
                }

                if (affectedPaths.isNotEmpty()) {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        affectedPaths.toTypedArray(),
                        null,
                        null
                    )
                }

                withContext(Dispatchers.Main) {
                    if (movedPairs.isNotEmpty()) {
                        var favChanged = false
                        var pinChanged = false
                        movedPairs.forEach { (oldP, newP) ->
                            if (oldP in favoritePaths) {
                                favoritePaths = (favoritePaths - oldP) + newP
                                favChanged = true
                            }
                            if (oldP in pinnedPaths) {
                                pinnedPaths = (pinnedPaths - oldP) + newP
                                pinChanged = true
                            }
                        }
                        if (favChanged) {
                            saveFavoritePaths(context)
                            refreshFavorites(context)
                        }
                        if (pinChanged) {
                            savePinnedPaths(context)
                        }
                    }

                    isDataLoading = false
                    val pastTense = if (isMove) "Moved" else "Copied"
                    val summary = when {
                        errorMessages.isNotEmpty() && successCount == 0 -> errorMessages.first()
                        errorMessages.isNotEmpty() -> "$pastTense $successCount, ${errorMessages.size} failed (${errorMessages.first()})"
                        successCount == 0 && skippedCount > 0 -> "Skipped $skippedCount item(s)"
                        skippedCount > 0 -> "$pastTense $successCount item(s) ($skippedCount skipped)"
                        else -> "$pastTense $successCount item(s)"
                    }
                    android.widget.Toast.makeText(
                        context,
                        summary,
                        if (errorMessages.isNotEmpty()) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
                    ).show()
                    refreshCurrentExplorerView(context)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDataLoading = false
                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun executeExtraction(context: android.content.Context, resolution: ConflictResolution = ConflictResolution.REPLACE) {
        val destDir = resolveDestinationDirectory()
        val items = extractionQueue
        updateExtractionMode(false)
        updateExtractionQueue(emptyList())
        showExtractionConflictDialog = false
        extractionConflicts = emptyList()

        if (items.isEmpty()) return

        isDataLoading = true
        android.widget.Toast.makeText(context, "Extracting...", android.widget.Toast.LENGTH_SHORT).show()

        viewModelScope.launch(Dispatchers.IO) {
            val extractedPaths = mutableListOf<String>()
            var createdDirs = 0
            var skippedCount = 0
            val errorMessages = mutableListOf<String>()

            try {
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw Exception("Cannot create destination folder ${destDir.name}")
                }
                val destCanon = destDir.canonicalPath

                items.forEach { item ->
                    val isVirtual = item.path.contains("!/")
                    val zipPath = if (isVirtual) item.path.substringBefore("!/") else item.path
                    val internalPath = if (isVirtual) item.path.substringAfter("!/").trimStart('/') else ""
                    val isSingleVirtualFile = isVirtual && !internalPath.endsWith("/") && !item.meta.contains("Folder")

                    val zipFile = File(zipPath)
                    if (!zipFile.exists()) {
                        errorMessages.add("Archive not found: ${zipFile.name}")
                        return@forEach
                    }

                    try {
                        ZipFile(zipFile).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val entryName = normalizeZipEntryName(entry.name)
                                if (entryName.isEmpty() || entryName.split("/").any { it == ".." }) continue

                                if (isSingleVirtualFile) {
                                    if (entryName == internalPath) {
                                        val fileName = File(internalPath).name
                                        val outFile = resolveDestinationFile(destDir, fileName, resolution)
                                        if (outFile == null) {
                                            skippedCount++
                                        } else if (outFile.canonicalPath.startsWith("$destCanon${File.separator}")) {
                                            outFile.parentFile?.mkdirs()
                                            zip.getInputStream(entry).use { input ->
                                                outFile.outputStream().use { output -> input.copyTo(output) }
                                            }
                                            extractedPaths.add(outFile.absolutePath)
                                        }
                                    }
                                } else if (isVirtual) {
                                    val cleanFolder = internalPath.removeSuffix("/")
                                    if (entryName == cleanFolder || entryName.startsWith("$cleanFolder/")) {
                                        val folderBase = File(cleanFolder).name
                                        val subPath = if (entryName == cleanFolder) "" else entryName.removePrefix("$cleanFolder/")
                                        val relPath = if (subPath.isEmpty()) folderBase else "$folderBase/$subPath"
                                        if (entry.isDirectory || entryName.endsWith("/")) {
                                            if (File(destDir, relPath).mkdirs()) createdDirs++
                                        } else {
                                            val outFile = resolveDestinationFile(destDir, relPath, resolution)
                                            if (outFile == null) {
                                                skippedCount++
                                            } else if (outFile.canonicalPath.startsWith("$destCanon${File.separator}")) {
                                                outFile.parentFile?.mkdirs()
                                                zip.getInputStream(entry).use { input ->
                                                    outFile.outputStream().use { output -> input.copyTo(output) }
                                                }
                                                extractedPaths.add(outFile.absolutePath)
                                            }
                                        }
                                    }
                                } else {
                                    if (entry.isDirectory || entryName.endsWith("/")) {
                                        if (File(destDir, entryName).mkdirs()) createdDirs++
                                    } else {
                                        val outFile = resolveDestinationFile(destDir, entryName, resolution)
                                        if (outFile == null) {
                                            skippedCount++
                                        } else if (outFile.canonicalPath.startsWith("$destCanon${File.separator}")) {
                                            outFile.parentFile?.mkdirs()
                                            zip.getInputStream(entry).use { input ->
                                                outFile.outputStream().use { output -> input.copyTo(output) }
                                            }
                                            extractedPaths.add(outFile.absolutePath)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errorMessages.add("${zipFile.name}: ${e.message ?: "Invalid archive"}")
                    }
                }

                if (extractedPaths.isNotEmpty()) {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        extractedPaths.toTypedArray(),
                        null,
                        null
                    )
                }

                withContext(Dispatchers.Main) {
                    isDataLoading = false
                    val summary = when {
                        errorMessages.isNotEmpty() && extractedPaths.isEmpty() -> "Extract failed: ${errorMessages.first()}"
                        errorMessages.isNotEmpty() -> "Extracted ${extractedPaths.size} files (${errorMessages.first()})"
                        extractedPaths.isEmpty() && skippedCount > 0 -> "Skipped $skippedCount existing file(s)"
                        extractedPaths.isEmpty() && createdDirs > 0 -> "Extracted $createdDirs folder(s)"
                        extractedPaths.isEmpty() -> "Archive had no extractable files"
                        skippedCount > 0 -> "Extracted ${extractedPaths.size} file(s) ($skippedCount skipped)"
                        else -> "Extracted ${extractedPaths.size} file(s)"
                    }
                    android.widget.Toast.makeText(
                        context,
                        summary,
                        if (errorMessages.isNotEmpty()) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
                    ).show()
                    refreshCurrentExplorerView(context)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDataLoading = false
                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    fun getCompressDestinationDir(items: List<FileItem>): File {
        if ((explorerCategory == "Internal Storage" || explorerCategory == "Downloads") && !explorerPath.contains("!/")) {
            return File(explorerPath)
        }
        val firstParent = items.firstOrNull()?.let { File(it.path).parentFile }
        return firstParent ?: File("/storage/emulated/0")
    }

    fun suggestZipName(items: List<FileItem>): String {
        val destDir = getCompressDestinationDir(items)
        val baseName = destDir.name.ifBlank { "Archive" }
        var candidate = "$baseName.zip"
        var counter = 1
        while (File(destDir, candidate).exists()) {
            candidate = "$baseName($counter).zip"
            counter++
        }
        return candidate
    }

    fun compressSelectedFiles(context: android.content.Context, rawArchiveName: String) {
        val items = selectedFiles.toList()
        if (items.isEmpty()) return

        clearSelection()
        val trimmed = rawArchiveName.trim().ifBlank { "Archive.zip" }
        val finalName = if (trimmed.lowercase().endsWith(".zip")) trimmed else "$trimmed.zip"
        val destDir = getCompressDestinationDir(items)
        val outZip = File(destDir, finalName)
        val tempZip = File(destDir, ".tmp_nexus_${System.currentTimeMillis()}.zip")

        isDataLoading = true
        android.widget.Toast.makeText(context, "Compressing ${items.size} item(s)...", android.widget.Toast.LENGTH_SHORT).show()

        viewModelScope.launch(Dispatchers.IO) {
            var fileCount = 0
            val buffer = ByteArray(8192)
            try {
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw Exception("Cannot access destination folder ${destDir.name}")
                }
                val tempCanon = tempZip.canonicalPath
                val outCanon = outZip.canonicalPath

                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(tempZip.outputStream())).use { zos ->
                    fun addEntry(file: File, entryPath: String) {
                        if (!file.exists()) return
                        val canon = file.canonicalPath
                        if (canon == tempCanon || canon == outCanon) return

                        if (file.isDirectory) {
                            val dirEntryName = if (entryPath.endsWith("/")) entryPath else "$entryPath/"
                            val dirEntry = java.util.zip.ZipEntry(dirEntryName).apply {
                                time = file.lastModified()
                            }
                            zos.putNextEntry(dirEntry)
                            zos.closeEntry()
                            val children = file.listFiles() ?: emptyArray()
                            for (child in children) {
                                addEntry(child, "$entryPath/${child.name}")
                            }
                        } else {
                            val fileEntry = java.util.zip.ZipEntry(entryPath).apply {
                                time = file.lastModified()
                            }
                            zos.putNextEntry(fileEntry)
                            file.inputStream().use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    zos.write(buffer, 0, read)
                                }
                            }
                            zos.closeEntry()
                            fileCount++
                        }
                    }

                    items.forEach { item ->
                        val src = File(item.path)
                        if (src.exists()) {
                            addEntry(src, src.name)
                        }
                    }
                }

                if (outZip.exists() && !outZip.delete()) {
                    throw Exception("Cannot overwrite existing ${outZip.name}")
                }
                if (!tempZip.renameTo(outZip)) {
                    throw Exception("Failed saving ${outZip.name}")
                }

                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outZip.absolutePath),
                    null,
                    null
                )

                withContext(Dispatchers.Main) {
                    isDataLoading = false
                    android.widget.Toast.makeText(
                        context,
                        "Created ${outZip.name} ($fileCount file(s))",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    refreshCurrentExplorerView(context)
                }
            } catch (e: Exception) {
                try { if (tempZip.exists()) tempZip.delete() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    isDataLoading = false
                    android.widget.Toast.makeText(context, "Compress failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    fun copySelectedFolderSkeleton(context: android.content.Context) {
        val folders = selectedFiles
            .map { File(it.path) }
            .filter { it.exists() && it.isDirectory }
        if (folders.isEmpty()) return

        clearSelection()
        isDataLoading = true

        viewModelScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val visited = HashSet<String>()
            var entryCount = 0
            var truncated = false
            val maxLines = 8000
            var lineCount = 0

            fun appendTree(dir: File, prefix: String) {
                if (truncated) return
                val canon = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                if (!visited.add(canon)) {
                    sb.append(prefix).append("└── [Symlink Loop]\n")
                    lineCount++
                    return
                }

                val rawChildren = try {
                    dir.listFiles()
                } catch (_: SecurityException) {
                    null
                }

                if (rawChildren == null) {
                    sb.append(prefix).append("└── [Permission Denied]\n")
                    lineCount++
                    return
                }

                val children = rawChildren.sortedWith(
                    compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                )

                for (i in children.indices) {
                    if (lineCount >= maxLines || sb.length >= 450_000) {
                        truncated = true
                        return
                    }
                    val child = children[i]
                    val isLast = i == children.lastIndex
                    val connector = if (isLast) "└── " else "├── "
                    val childPrefix = prefix + if (isLast) "    " else "│   "

                    sb.append(prefix)
                        .append(connector)
                        .append(child.name)
                        .append(if (child.isDirectory) "/" else "")
                        .append('\n')
                    lineCount++
                    entryCount++

                    if (child.isDirectory) {
                        appendTree(child, childPrefix)
                    }
                }
            }

            folders.sortedBy { it.name.lowercase() }.forEachIndexed { idx, folder ->
                if (idx > 0 && !truncated) {
                    sb.append('\n')
                    lineCount++
                }
                if (!truncated) {
                    sb.append(folder.name).append("/\n")
                    lineCount++
                    entryCount++
                    appendTree(folder, "")
                }
            }

            if (truncated) {
                sb.append("\n... (truncated: hierarchy exceeded clipboard safety limit)\n")
            }

            val finalTree = sb.toString().trimEnd()
            withContext(Dispatchers.Main) {
                isDataLoading = false
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Folder Skeleton", finalTree))
                android.widget.Toast.makeText(
                    context,
                    "Copied skeleton ($entryCount entries)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    fun openFileForEditing(file: File) {
        isDataLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = file.readText()
                val rawLines = content.lines()
                val safeLines = if (rawLines.any { it.length > 2000 }) {
                    rawLines.flatMap { line ->
                        if (line.length > 2000) line.chunked(2000) else listOf(line)
                    }
                } else rawLines

                withContext(Dispatchers.Main) {
                    editingFile = file
                    savedStateHandle["editingFilePath"] = file.absolutePath
                    editorText = content
                    editorLines = safeLines
                    hasUnsavedChanges = false
                    isEditLocked = true
                    recoveryFile?.delete()
                    isDataLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    editorText = "Error reading file: ${e.message}"
                    editorLines = listOf("Error reading file: ${e.message}")
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
        editorLines = emptyList()
        hasUnsavedChanges = false
    }

    fun saveCurrentFile(textToSave: String) {
        editingFile?.let { file ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    file.writeText(textToSave)
                    val rawLines = textToSave.lines()
                    val safeLines = if (rawLines.any { it.length > 2000 }) {
                        rawLines.flatMap { line ->
                            if (line.length > 2000) line.chunked(2000) else listOf(line)
                        }
                    } else rawLines

                    withContext(Dispatchers.Main) {
                        editorText = textToSave
                        editorLines = safeLines
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
