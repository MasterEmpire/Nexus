package com.conduit.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.chaquo.python.Python
import java.util.concurrent.TimeUnit
import android.content.Context

object SupabaseClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
    private val BASE_URL = "${ConduitConfig.SUPABASE_URL}${ConduitConfig.GEMINI_FUNCTION_PATH}"

    private fun extractJson(input: String): String {
        val markdownRegex = """(?s)```(?:json)?\s*(.*?)\s*```""".toRegex()
        val cleaned = markdownRegex.find(input)?.groupValues?.get(1) ?: input
        
        val trimmed = cleaned.trim()
        val start = trimmed.indexOfFirst { it == '{' || it == '[' }
        val end = trimmed.indexOfLast { it == '}' || it == ']' }

        return if (start != -1 && end != -1 && end >= start) {
            trimmed.substring(start, end + 1)
        } else trimmed
    }

    suspend fun callConduitAction(payload: JSONObject): String = withContext(Dispatchers.IO) {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(BASE_URL)
            .post(body)
            .build()
        try { 
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: "{}"
                val cleaned = extractJson(raw)
                if (cleaned.trim().startsWith("{") || cleaned.trim().startsWith("[")) {
                    cleaned
                } else {
                    JSONObject().put("error", raw).toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().put("error", "Network failure: ${e.message}").toString()
        }
    }

    suspend fun fetchSessionsFromSupabase(): List<Pair<String, String>> {
        val raw = callConduitAction(JSONObject().put("action", "list_sessions"))
        val list = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(obj.getString("id") to obj.getString("title"))
            }
        } catch (e: Exception) {}
        return list
    }

    suspend fun fetchHistoryForSession(sessionId: String): List<Message> {
        val raw = callConduitAction(JSONObject().put("action", "fetch_history").put("session_id", sessionId))
        val list = mutableListOf<Message>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val content = obj.getString("content")
                if (content.contains("\"functionResponse\"")) continue // Don't render phantom tool responses in UI
                val isAi = obj.getString("sender") == "ai"
                val timestamp = obj.optString("created_at")
                list.add(Message(System.currentTimeMillis() + i, if (isAi) "AI" else "User", content, isAi, timestamp = timestamp))
            }
        } catch (e: Exception) {}
        return list
    }

    suspend fun createNewSessionInSupabase(title: String = "New AI Conversation"): String? {
        val raw = callConduitAction(JSONObject().put("action", "create_session").put("title", title))
        return try { JSONObject(raw).getString("id") } catch (e: Exception) { null }
    }

    suspend fun generateTitleForSession(sessionId: String): Boolean {
        val raw = callConduitAction(JSONObject().put("action", "generate_title").put("session_id", sessionId))
        return raw.contains("success")
    }

    suspend fun deleteSessionFromSupabase(sessionId: String): Boolean {
        val raw = callConduitAction(JSONObject().put("action", "delete_session").put("session_id", sessionId))
        return raw.contains("true")
    }

    suspend fun truncateHistory(sessionId: String, timestamp: String): Boolean {
        val raw = callConduitAction(JSONObject().put("action", "truncate_history").put("session_id", sessionId).put("timestamp", timestamp))
        return raw.contains("true")
    }

    suspend fun callGeminiEdgeFunction(context: Context, prompt: String, sessionId: String?, onStatusUpdate: (String) -> Unit): String {
        val python = Python.getInstance()
        val executor = python.getModule("executor")
        val responseBlocks = JSONArray()
        
        var currentPrompt: String? = prompt
        var currentToolResults: JSONArray? = null
        var shouldContinue = true
        var loopCount = 0

        while (shouldContinue && loopCount < 5) {
            loopCount++
            onStatusUpdate(if (loopCount == 1) "Thinking..." else "Analyzing Result...")
            val payload = JSONObject().apply {
                put("action", "chat")
                if (sessionId != null) put("session_id", sessionId)
                if (currentToolResults != null) {
                    put("tool_results", currentToolResults)
                } else {
                    put("prompt", currentPrompt)
                }
            }

            val raw = callConduitAction(payload)
            if (!raw.trim().startsWith("{")) return "Error: $raw"
            
            val jsonResp = JSONObject(raw)
            if (jsonResp.has("error")) return "Server Error: ${jsonResp.getString("error")}"
            
            val candidates = jsonResp.optJSONArray("candidates") ?: break
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: break
            val parts = content.optJSONArray("parts") ?: break
            
            val nextToolResults = JSONArray()
            var foundFunctionCall = false

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    val text = part.getString("text")
                    if (text.isNotBlank()) {
                        responseBlocks.put(JSONObject().put("type", "text").put("content", text))
                    }
                }
                
                if (part.has("functionCall")) {
                    val call = part.getJSONObject("functionCall")
                    val name = call.getString("name")
                    
                    if (name == "get_selected_files") {
                        // This will be handled by the context injected in ViewModel, 
                        // but we return a confirmation to Gemini
                        nextToolResults.put(JSONObject().put("name", name).put("content", "Selection context already provided in prompt."))
                        foundFunctionCall = true
                    } else if (name == "perform_batch_op") {
                        val args = call.getJSONObject("args")
                        val op = args.getString("operation")
                        val paths = args.getJSONArray("paths")
                        val dest = args.optString("destination", "")
                        
                        responseBlocks.put(JSONObject().apply {
                            put("type", "batch_op")
                            put("operation", op)
                            put("paths", paths)
                            put("destination", dest)
                        })
                        nextToolResults.put(JSONObject().put("name", name).put("content", "Executing $op on ${paths.length()} items..."))
                        foundFunctionCall = true
                    } else if (name == "native_search") {
                        val query = call.getJSONObject("args").getString("query")
                        onStatusUpdate("Searching System...")
                        val results = withContext(Dispatchers.IO) { 
                            FileScanner.searchFilesNative(context, query) 
                        }
                        val resJson = JSONArray()
                        results.forEach { resJson.put(it.path) }
                        nextToolResults.put(JSONObject().put("name", name).put("content", resJson.toString()))
                        foundFunctionCall = true
                    } else if (name == "store_memory") {
                        val args = call.getJSONObject("args")
                        val key = args.optString("key", "")
                        val contentStr = args.optString("content", "")
                        val res = callConduitAction(JSONObject().put("action", "add_tag").put("session_id", sessionId).put("path", contentStr).put("tag", key))
                        nextToolResults.put(JSONObject().put("name", name).put("content", "Memory stored successfully."))
                        foundFunctionCall = true
                    } else if (name == "get_memory") {
                        val res = callConduitAction(JSONObject().put("action", "list_tags").put("session_id", sessionId))
                        nextToolResults.put(JSONObject().put("name", name).put("content", res))
                        foundFunctionCall = true
                    } else if (name == "delete_memory") {
                        val args = call.getJSONObject("args")
                        val key = args.optString("key", "")
                        val res = callConduitAction(JSONObject().put("action", "delete_tag").put("session_id", sessionId).put("tag", key))
                        nextToolResults.put(JSONObject().put("name", name).put("content", "Memory deleted successfully."))
                        foundFunctionCall = true
                    } else if (name == "open_nexus_path") {
                        val args = call.getJSONObject("args")
                        val path = args.getString("path")
                        val mode = args.getString("mode")
                        
                        // This is a UI command, we handle it via a callback or return a special status
                        nextToolResults.put(JSONObject().put("name", name).put("content", "UI navigating to $path in $mode mode"))
                        responseBlocks.put(JSONObject().put("type", "ui_nav").put("path", path).put("mode", mode))
                        foundFunctionCall = true
                    } else if (name == "execute_python_code") {
                        val code = call.optJSONObject("args")?.optString("code") ?: ""
                        val executionResult = if (code.isNotEmpty()) {
                            onStatusUpdate("Executing...")
                            withContext(Dispatchers.IO) {
                                val outputBuilder = StringBuilder()
                                val retVal = executor.callAttr("run_code", code, object {
                                    fun onOutput(msg: String) { 
                                        outputBuilder.append(msg)
                                        val cleanMsg = msg.trim()
                                        if (cleanMsg.isNotEmpty()) onStatusUpdate("Executing: ${cleanMsg.take(30)}")
                                    }
                                }).toString()
                                
                                val built = outputBuilder.toString()
                                if (built.isNotBlank()) built else if (retVal.isNotBlank()) retVal else "Execution completed (no output)."
                            }
                        } else "Error: No code provided"

                        val isError = executionResult.contains("Error") || executionResult.contains("Traceback")
                        if (!isError || loopCount >= 4) {
                            responseBlocks.put(JSONObject().apply {
                                put("type", "code_execution")
                                put("code", code)
                                put("result", executionResult)
                            })
                        }
                        nextToolResults.put(JSONObject().put("name", name).put("content", executionResult))
                        foundFunctionCall = true
                    }
            }
        }

        if (foundFunctionCall) {
            currentToolResults = nextToolResults
            currentPrompt = null
        } else {
            shouldContinue = false
        }
    }

        return if (responseBlocks.length() > 0) responseBlocks.toString() else "AI returned no response."
    }
}