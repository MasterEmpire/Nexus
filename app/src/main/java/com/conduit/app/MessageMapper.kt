package com.conduit.nexus

import org.json.JSONArray
import org.json.JSONObject

object MessageMapper {
    fun parseToBlocks(raw: String): List<RenderBlock> {
        val blocks = mutableListOf<RenderBlock>()
        val trimmed = raw.trim()

        try {
            if (trimmed.startsWith("[")) {
                // Handle our custom UI Dialect
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    blocks.add(mapJsonToBlock(obj))
                }
            } else if (trimmed.startsWith("{")) {
                // Handle Gemini Native Dialect (from History)
                val obj = JSONObject(trimmed)
                if (obj.has("parts")) {
                    val parts = obj.getJSONArray("parts")
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("text")) {
                            blocks.add(RenderBlock.Text(part.getString("text")))
                        } else if (part.has("functionCall")) {
                            val fc = part.getJSONObject("functionCall")
                            val name = fc.optString("name", "unknown")
                            val args = fc.optJSONObject("args")
                            
                            if (name == "execute_python_code" && args != null) {
                                val code = args.optString("code", "")
                                blocks.add(RenderBlock.CodeExecution(code, "Executed previously"))
                            } else if (name == "perform_batch_op" && args != null) {
                                val op = args.optString("operation", "")
                                val dest = args.optString("destination", "")
                                val pathsArr = args.optJSONArray("paths")
                                val paths = mutableListOf<String>()
                                if (pathsArr != null) {
                                    for (j in 0 until pathsArr.length()) {
                                        paths.add(pathsArr.optString(j))
                                    }
                                }
                                blocks.add(RenderBlock.BatchOp(op, paths, dest))
                            } else if (name == "open_nexus_path" && args != null) {
                                val path = args.optString("path", "")
                                val mode = args.optString("mode", "")
                                blocks.add(RenderBlock.UiNav(path, mode))
                            } else {
                                val fcArgs = args?.toString() ?: "{}"
                                blocks.add(RenderBlock.Text("[System Tool Called: $name]\n$fcArgs"))
                            }
                        }
                    }
                } else {
                   // Fallback for random JSON objects
                   blocks.add(RenderBlock.Text(trimmed))
                }
            } else {
                blocks.add(RenderBlock.Text(raw))
            }
        } catch (e: Exception) {
            blocks.add(RenderBlock.Text(raw))
        }
        return blocks
    }

    private fun mapJsonToBlock(obj: JSONObject): RenderBlock {
        return when (obj.optString("type")) {
            "text" -> RenderBlock.Text(obj.optString("content"))
            "code_execution" -> RenderBlock.CodeExecution(obj.optString("code"), obj.optString("result"))
            "ui_nav" -> RenderBlock.UiNav(obj.optString("path"), obj.optString("mode"))
            "batch_op" -> {
                val paths = mutableListOf<String>()
                val pArr = obj.optJSONArray("paths")
                if (pArr != null) for (i in 0 until pArr.length()) paths.add(pArr.getString(i))
                RenderBlock.BatchOp(obj.optString("operation"), paths, obj.optString("destination"))
            }
            else -> RenderBlock.Text(obj.toString())
        }
    }
}