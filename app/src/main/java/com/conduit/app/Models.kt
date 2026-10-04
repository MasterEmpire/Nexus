package com.conduit.nexus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class Message(val id: Long, val sender: String, val text: String, val isAi: Boolean, val isSystemAction: Boolean = false, val timestamp: String? = null)

sealed class RenderBlock {
    data class Text(val content: String) : RenderBlock()
    data class CodeExecution(val code: String, val result: String) : RenderBlock()
    data class UiNav(val path: String, val mode: String) : RenderBlock()
    data class BatchOp(val operation: String, val paths: List<String>, val destination: String) : RenderBlock()
}

enum class SortOrder { NAME_ASC, NAME_DESC, DATE_NEW, DATE_OLD, SIZE_LARGE, SIZE_SMALL }

data class Cat(val name: String, val icon: ImageVector, val color: Color)

data class FileItem(
    val name: String, 
    val meta: String, 
    val icon: ImageVector, 
    val color: Color, 
    val path: String = "", 
    val lastModified: Long = 0L, 
    val size: Long = 0L
)

data class SavedScript(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String,
    val code: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class QuickPill(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String
)