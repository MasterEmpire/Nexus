import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const SYSTEM_PROMPT = `You are Conduit, a highly personalized AI OS. You have a persistent memory bank to store user preferences, project context, and important paths.

CRITICAL OPERATING RULES:
1. MEMORY FIRST: Always check 'get_memory' at the start of a task to see if you have stored context or preferences (e.g., 'User hates docs', 'Project X is at /path/').
2. ANDROID REALITY: You are in an Android environment. Primary user storage is at '/storage/emulated/0'. Always assume this path for file operations unless told otherwise.
3. PROGRESS REPORTING: For any script taking >2 seconds, you MUST print progress updates in this EXACT format: print(f'[PROGRESS:{percentage}:{status_text}]'). For example: print('[PROGRESS:25:Searching directories...]').
4. ERROR HANDLING: If code execution returns an 'Error' or 'Traceback', analyze the error and immediately retry with corrected code. Attempt to fix and retry at least 3 times before reporting failure.
5. SUCCESS REPORTS: Never stop silently. Always provide a final human-readable summary of your actions.
6. ENVIRONMENT: Generate scripts that work on Android. Avoid PC-only paths (C:\\, /home/user) or GUI libraries.`;

serve(async (req) => {
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL') ?? '',
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
  );

  const body = await req.json();
  const { action, prompt, session_id, title, timestamp } = body;
  console.log(`[LOG] Action: ${action} | Session: ${session_id}`);

  // 1. LIST SESSIONS
  if (action === 'list_sessions') {
    const { data } = await supabase.from('conduit_sessions').select('id, title').order('created_at', { ascending: false });
    return new Response(JSON.stringify(data), { headers: { "Content-Type": "application/json" } });
  }

  // 2. CREATE SESSION
  if (action === 'create_session') {
    const { data } = await supabase.from('conduit_sessions').insert({ title: title || 'New AI Conversation' }).select().single();
    return new Response(JSON.stringify(data), { headers: { "Content-Type": "application/json" } });
  }

  // 3. DELETE SESSION
  if (action === 'delete_session') {
    await supabase.from('conduit_sessions').delete().eq('id', session_id);
    return new Response(JSON.stringify({ success: true }));
  }

        // 4. FETCH HISTORY (Includes created_at for branching)
      if (action === 'fetch_history') {
        const { data } = await supabase.from('conduit_chat_history')
          .select('sender, content, created_at')
          .eq('session_id', session_id)
          .order('created_at', { ascending: true });

        if (!data) return new Response(JSON.stringify([]), { headers: { "Content-Type": "application/json" } });

        const processedHistory =[];
        let currentAiMessage: any = null;

        for (const row of data) {
          // Skip phantom tool responses from the user
          if (row.sender === 'user' && row.content.includes('"functionResponse"')) continue;

          if (row.sender === 'ai') {
            try {
              const parsed = JSON.parse(row.content);
              if (parsed.parts) {
                // Filter out invisible internal tool calls
                const visibleParts = parsed.parts.filter((p: any) => {
                  if (p.functionCall) {
                    const name = p.functionCall.name;
                    return !['get_memory', 'store_memory', 'delete_memory', 'native_search', 'get_selected_files'].includes(name);
                  }
                  return true;
                });

                if (visibleParts.length > 0) {
                  if (currentAiMessage) {
                    // Merge parts into the existing AI message
                    const currentParsed = JSON.parse(currentAiMessage.content);
                    currentParsed.parts.push(...visibleParts);
                    currentAiMessage.content = JSON.stringify(currentParsed);
                  } else {
                    currentAiMessage = { ...row, content: JSON.stringify({ ...parsed, parts: visibleParts }) };
                    processedHistory.push(currentAiMessage);
                  }
                }
              } else {
                // Fallback for non-standard JSON
                if (currentAiMessage) currentAiMessage.content += "\n" + row.content;
                else { currentAiMessage = { ...row }; processedHistory.push(currentAiMessage); }
              }
            } catch (e) {
              // Fallback for plain text
              if (currentAiMessage) currentAiMessage.content += "\n" + row.content;
              else { currentAiMessage = { ...row }; processedHistory.push(currentAiMessage); }
            }
          } else {
            // It's a regular user message
            currentAiMessage = null;
            processedHistory.push(row);
          }
        }

        return new Response(JSON.stringify(processedHistory), { headers: { "Content-Type": "application/json" } });
      }

  // 5a. ADD TAG (Memory)
  if (action === 'add_tag') {
    const { path, tag } = body;
    const { data, error } = await supabase.from('conduit_memory').upsert({ session_id, path, tag_name: tag }).select();
    return new Response(JSON.stringify({ success: !error, data }));
  }

  // 5b. LIST TAGS (Memory)
  if (action === 'list_tags') {
    const { data } = await supabase.from('conduit_memory').select('path, tag_name').eq('session_id', session_id);
    return new Response(JSON.stringify(data));
  }

  // 5c. DELETE TAG (Memory)
  if (action === 'delete_tag') {
    const { tag } = body;
    const { error } = await supabase.from('conduit_memory').delete().eq('session_id', session_id).eq('tag_name', tag);
    return new Response(JSON.stringify({ success: !error }));
  }

  // 5. TRUNCATE HISTORY (Branching logic)
  if (action === 'truncate_history') {
    if (!timestamp) return new Response(JSON.stringify({ success: false, error: "Timestamp required" }), { status: 400 });
    const { error } = await supabase.from('conduit_chat_history')
      .delete()
      .eq('session_id', session_id)
      .gte('created_at', timestamp);
    return new Response(JSON.stringify({ success: !error }));
  }

  // 6. GENERATE TITLE
  if (action === 'generate_title') {
    const { data: history } = await supabase.from('conduit_chat_history')
      .select('sender, content').eq('session_id', session_id).order('created_at', { ascending: true }).limit(6);
      
    if (!history || history.length === 0) return new Response(JSON.stringify({ error: "No history" }), { status: 400 });

    const conversation = history.map(h => {
      let text = h.content;
      try {
        const parsed = JSON.parse(h.content);
        if (parsed.parts) {
          // Extract only the human-readable text and tool names so Gemini doesn't choke on raw JSON
          text = parsed.parts.map((p: any) => p.text || (p.functionCall ? `[Tool: ${p.functionCall.name}]` : '')).join(' ');
        }
      } catch(e) {}
      return `${h.sender}: ${text}`;
    }).join('\n');

    const titlePrompt = `Based on this conversation, generate a short, descriptive title (max 5 words) for the chat session. Only output the title, no quotes or extra text.\n\n${conversation}`;

    const now = new Date().toISOString();
    const { data: keyData } = await supabase.from('api_keys').select('id, api_key')
      .eq('service', 'gemini').eq('is_active', true)
      .or(`cooldown_until.is.null,cooldown_until.lte.${now}`)
      .order('last_used_at', { ascending: true, nullsFirst: true }).limit(1).single();

    if (!keyData) return new Response(JSON.stringify({ error: "No API key" }), { status: 500 });
    await supabase.from('api_keys').update({ last_used_at: now }).eq('id', keyData.id);

    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${keyData.api_key}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents:[{ role: 'user', parts: [{ text: titlePrompt }] }]
      })
    });
    
    const result = await response.json();
    let newTitle = result.candidates?.[0]?.content?.parts?.[0]?.text?.trim().replace(/^["']|["']$/g, '');
    
    if (newTitle) {
      if (newTitle.length > 50) newTitle = newTitle.substring(0, 50) + "...";
      await supabase.from('conduit_sessions').update({ title: newTitle }).eq('id', session_id);
    }
    
    return new Response(JSON.stringify({ success: true, title: newTitle }), { headers: { "Content-Type": "application/json" } });
  }

  // 7. CHAT
  if (action === 'chat') {
    const { tool_results, session_id: sid } = body;
    const userPrompt = prompt;
    const now = new Date().toISOString();
    
    const { data: keyData } = await supabase.from('api_keys').select('id, api_key')
      .eq('service', 'gemini').eq('is_active', true)
      .or(`cooldown_until.is.null,cooldown_until.lte.${now}`)
      .order('last_used_at', { ascending: true, nullsFirst: true }).limit(1).single();

    if (!keyData) return new Response(JSON.stringify({ error: "No active API keys" }), { status: 500 });
    await supabase.from('api_keys').update({ last_used_at: now }).eq('id', keyData.id);
    
    const { data: history } = await supabase.from('conduit_chat_history')
      .select('sender, content').eq('session_id', sid).order('created_at', { ascending: true }).limit(20);
    
    const contents = (history || []).map(msg => {
      try {
        const parsed = JSON.parse(msg.content);
        if (parsed.parts) return { role: msg.sender === 'user' ? 'user' : 'model', parts: parsed.parts };
      } catch (e) {}
      return { role: msg.sender === 'user' ? 'user' : 'model', parts: [{ text: msg.content }] };
    });
    
    if (tool_results && tool_results.length > 0) {
      const functionResponses = tool_results.map((r: any) => ({ functionResponse: { name: r.name, response: { content: r.content } } }));
      contents.push({ role: 'user', parts: functionResponses });
      
      await supabase.from('conduit_chat_history').insert({ 
        session_id: sid, 
        sender: 'user', 
        content: JSON.stringify({ parts: functionResponses }) 
      });
    } else {
      // SANITIZE HISTORY: If sending a new prompt, ensure the last model turn doesn't have dangling function calls
      if (contents.length > 0) {
        const lastTurn = contents[contents.length - 1];
        if (lastTurn.role === 'model') {
          lastTurn.parts = lastTurn.parts.filter((p: any) => !p.functionCall);
          if (lastTurn.parts.length === 0) contents.pop();
        }
      }
      contents.push({ role: 'user', parts: [{ text: userPrompt }] });
      await supabase.from('conduit_chat_history').insert({ session_id: sid, sender: 'user', content: userPrompt });
    }

    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${keyData.api_key}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents, system_instruction: { parts: [{ text: SYSTEM_PROMPT }] },
        tools: [{ 
          function_declarations: [
            {
              name: "execute_python_code",
              description: "Execute python code to manipulate files or get system info.",
              parameters: { type: "object", properties: { code: { type: "string" } } }
            },
            {
              name: "open_nexus_path",
              description: "Directly opens a file or navigates to a folder location in the UI.",
              parameters: {
                type: "object",
                properties: {
                  path: { type: "string", description: "The absolute path to the file or folder." },
                  mode: { type: "string", enum: ["direct", "location"], description: "'direct' opens the file editor/viewer. 'location' takes user to the folder and highlights the item." }
                },
                required: ["path", "mode"]
              }
            },
            {
              name: "get_selected_files",
              description: "Returns the list of files currently selected by the user in the Explorer UI.",
              parameters: { type: "object", properties: {} }
            },
            {
              name: "perform_batch_op",
              description: "Performs move, copy, or delete on multiple files natively.",
              parameters: {
                type: "object",
                properties: {
                  operation: { type: "string", enum: ["move", "delete", "copy"] },
                  paths: { type: "array", items: { type: "string" } },
                  destination: { type: "string", description: "Target folder for move/copy operations." }
                },
                required: ["operation", "paths"]
              }
            },
            {
              name: "native_search",
              description: "High-speed system search for files by name/extension.",
              parameters: { type: "object", properties: { query: { type: "string" } }, required: ["query"] }
            },
            {
              name: "store_memory",
              description: "Stores an insight, preference, or path into long-term memory.",
              parameters: {
                type: "object",
                properties: {
                  key: { type: "string", description: "Unique identifier for the memory (e.g. 'coding_style')" },
                  content: { type: "string", description: "The actual data or path to remember" },
                  type: { type: "string", enum: ["PATH", "INSIGHT", "PREFERENCE"] }
                },
                required: ["key", "content", "type"]
              }
            },
            {
              name: "get_memory",
              description: "Retrieves all previously tagged important paths and folders.",
              parameters: { type: "object", properties: {} }
            },
            {
              name: "delete_memory",
              description: "Deletes a previously stored memory or tag.",
              parameters: {
                type: "object",
                properties: {
                  key: { type: "string", description: "Unique identifier for the memory to delete" }
                },
                required: ["key"]
              }
            }
          ] 
        }]
      })
    });

    const result = await response.json();
    if (result.error) return new Response(JSON.stringify(result), { status: 500 });

    if (result.candidates?.[0]) {
      await supabase.from('conduit_chat_history').insert({ session_id: sid, sender: 'ai', content: JSON.stringify(result.candidates[0].content) });
    }
    return new Response(JSON.stringify(result), { headers: { "Content-Type": "application/json" } });
  }

  return new Response(JSON.stringify({ error: "Invalid Action" }), { status: 400 });
})