package com.obs.chat.relay.bot

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.obs.chat.relay.model.ChatMessage
import com.obs.chat.relay.util.PreferencesManager
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.TimeUnit

class OllamaBotEngine(
    private val prefs: PreferencesManager,
    private val onBotReply: (ChatMessage) -> Unit,
    private val onLog: (String, String) -> Unit
) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val webSearchHelper = WebSearchHelper()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val seenChatters = Collections.synchronizedSet(mutableSetOf<String>())
    private val conversationHistory = Collections.synchronizedList(mutableListOf<Pair<String, String>>()) // role -> content
    private var lastAiResponseTime = 0L
    private var isGenerating = false
    private var timersJob: Job? = null

    // Deduplication cache to strictly enforce ONE response per question/message (prevents double replies)
    private val recentAnsweredQuestions = Collections.synchronizedMap(object : LinkedHashMap<String, Long>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 100
        }
    })

    init {
        startTimers()
    }

    private fun normalizeUrl(url: String): String {
        var clean = url.trim().trimEnd('/')
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        return clean.replace("://localhost", "://127.0.0.1")
    }

    suspend fun fetchModels(customUrl: String? = null): List<String> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(customUrl ?: prefs.ollamaUrl)
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string()
                if (!body.isNullOrEmpty()) {
                    val root = JsonParser.parseString(body).asJsonObject
                    val modelsArr = root.getAsJsonArray("models")
                    val list = mutableListOf<String>()
                    modelsArr?.forEach {
                        it.asJsonObject.get("name")?.asString?.let { name -> list.add(name) }
                    }
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            onLog("[AI Bot] Error fetching Ollama models: ${e.message}", "error")
        }
        emptyList()
    }

    private val recentGreetedSubs = Collections.synchronizedSet(mutableSetOf<String>())

    fun handleChatMessage(chat: ChatMessage) {
        val username = chat.username.trim()
        val message = chat.message?.trim() ?: return

        // 1. Special Fossabot Subscriber Extraction (Only for Fossabot: wishes subscriber & alerts dock)
        val cleanUser = username.lowercase().replace("@", "").trim()
        if (cleanUser == "fossabot") {
            handleFossabotMessage(message, chat.platform)
            return // Never process Fossabot messages as normal chat or AI questions
        }

        // 2. Ignore bot accounts (our bot, nightbot, streamelements, moobot, etc.)
        if (isBotAccount(username, chat.badges)) return

        // 3. Ignore streamer account (@steushio / broadcaster)
        if (isStreamerAccount(username, chat.badges)) return

        // 4. Do not reply if viewer is talking to @steushio directly (not meant for the bot)
        val lowerMessage = message.lowercase()
        val botName = prefs.botName.lowercase().replace("@", "").trim()
        if (lowerMessage.contains("@steushio") && !lowerMessage.contains("@$botName") && !lowerMessage.contains(botName)) {
            return
        }

        // Track real viewer chat activity for activity-gated timer messages
        recordUserMessageForTimers(chat.platform)

        // 5. Strict 1-chat-per-question deduplication (prevents duplicate answers)
        val dedupeKey = if (chat.id.isNotBlank()) chat.id else "${username.lowercase()}:${message.lowercase()}"
        val now = System.currentTimeMillis()
        synchronized(recentAnsweredQuestions) {
            val lastSeen = recentAnsweredQuestions[dedupeKey]
            if (lastSeen != null && (now - lastSeen) < 30_000L) {
                return // Already answered or currently answering this question
            }
            recentAnsweredQuestions[dedupeKey] = now
        }

        // 6. Custom Commands (!discord, !specs, !socials - work instantly without needing AI)
        val commandReply = checkCustomCommands(message)
        if (commandReply != null) {
            dispatchBotReply(commandReply, chat.platform)
            return
        }

        val isAiActive = prefs.ollamaEnabled ||
                (prefs.aiProvider == "nvidia" && prefs.nvidiaApiKey.isNotBlank()) ||
                (prefs.aiProvider == "opencode_zen" && prefs.opencodeZenApiKey.isNotBlank()) ||
                (prefs.aiProvider == "gemini" && prefs.geminiApiKey.isNotBlank()) ||
                (prefs.aiProvider == "openai" && prefs.openaiApiKey.isNotBlank())
        if (!isAiActive) return

        // 7. First-words welcoming
        val lowerUser = username.lowercase()
        val isFirstTime = !seenChatters.contains(lowerUser)
        if (isFirstTime) {
            seenChatters.add(lowerUser)
            if (prefs.firstWordsEnabled) {
                scope.launch {
                    handleFirstWords(username, message, chat.platform)
                }
                return
            }
        }

        // 8. Auto-respond Trigger
        val autoRespondMode = prefs.autoRespond // "off", "mention_only", "questions_and_mentions", "all_chat"
        if (autoRespondMode == "off") return

        val isMentioned = lowerMessage.contains("@$botName") || lowerMessage.contains(botName)

        val shouldRespond = when (autoRespondMode) {
            "all_chat" -> true
            "mention_only" -> isMentioned
            "questions_and_mentions" -> isMentioned || isQuestion(message)
            else -> false
        }

        if (shouldRespond) {
            scope.launch {
                triggerAiGeneration(message, username, chat.platform)
            }
        }
    }

    private fun handleFossabotMessage(message: String, platform: String) {
        val lower = message.lowercase().trim()
        if (!lower.contains("subscrib") && !lower.contains("subbed")) {
            return
        }

        // Patterns to match:
        // "@fossabot s1 just subscribed" -> s1
        // "s1 just subscribed!" -> s1
        // "Thanks s1 for subscribing!" -> s1
        // "s1 has subscribed to the channel" -> s1
        val patterns = listOf(
            java.util.regex.Pattern.compile("""(?i)(?:@fossabot\s+)?([a-zA-Z0-9_\- ]+?)\s+(?:has\s+|just\s+)?(?:subscribed|subbed)"""),
            java.util.regex.Pattern.compile("""(?i)(?:thanks(?:\s+to)?\s+)@?([a-zA-Z0-9_\-]+)\s+for\s+(?:subscribing|the\s+sub)"""),
            java.util.regex.Pattern.compile("""(?i)welcome\s+@?([a-zA-Z0-9_\-]+)\s+to\s+the\s+"""),
            java.util.regex.Pattern.compile("""(?i)@?([a-zA-Z0-9_\-]+)\s+is\s+now\s+subscribed""")
        )

        var subscriberName: String? = null
        for (pattern in patterns) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                val candidate = matcher.group(1)?.trim()?.replace("@", "")?.trimEnd('!', '.', ',')
                if (!candidate.isNullOrBlank() && !candidate.equals("fossabot", ignoreCase = true) && candidate.length < 35) {
                    subscriberName = candidate
                    break
                }
            }
        }

        if (subscriberName == null) {
            val parts = message.split(Regex("\\s+"))
            val subIdx = parts.indexOfFirst { it.lowercase().contains("subscrib") || it.lowercase().contains("subbed") }
            if (subIdx > 0) {
                var prev = parts[subIdx - 1].replace("@", "").trimEnd('!', '.', ',')
                if (prev.equals("just", ignoreCase = true) && subIdx > 1) {
                    prev = parts[subIdx - 2].replace("@", "").trimEnd('!', '.', ',')
                }
                if (prev.isNotBlank() && !prev.equals("fossabot", ignoreCase = true)) {
                    subscriberName = prev
                }
            }
        }

        if (subscriberName.isNullOrBlank()) {
            onLog("[Fossabot] Subscription detected but could not parse name: \"$message\"", "warning")
            return
        }

        val key = subscriberName.lowercase()
        if (recentGreetedSubs.contains(key)) return
        recentGreetedSubs.add(key)

        onLog("🎉 [Fossabot Sub Alert] Detected new subscriber: $subscriberName", "success")

        // 1. Dispatch short warm chat greeting
        val greeting = "Thanks for subscribing, $subscriberName! Welcome to the squad! 🎉"
        dispatchBotReply(greeting, platform)

        // 2. Dispatch subscriber alert event to OBS custom dock & alert overlay
        val subAlert = ChatMessage(
            platform = platform,
            type = "subscription",
            id = "sub_fossa_${System.currentTimeMillis()}",
            username = subscriberName,
            message = "Just subscribed!",
            timestamp = System.currentTimeMillis()
        )
        onBotReply(subAlert)
    }

    private fun isBotAccount(username: String, badges: List<String> = emptyList()): Boolean {
        val clean = username.lowercase().replace("@", "").trim()
        val botName = prefs.botName.lowercase().replace("@", "").trim()
        if (clean == botName || clean == "cutie bot" || clean == "cutiebot") return true
        if (badges.any { it.equals("bot", ignoreCase = true) }) return true

        val knownBots = setOf(
            "fossabot",
            "nightbot",
            "streamelements",
            "moobot",
            "botrix",
            "wizebot",
            "restreambot",
            "streamlabs",
            "youtube",
            "kickbot"
        )
        if (knownBots.contains(clean)) return true

        val ignored = prefs.ignoredUsers.split(",").map { it.lowercase().replace("@", "").trim() }.filter { it.isNotBlank() }
        return ignored.contains(clean)
    }

    private fun isStreamerAccount(username: String, badges: List<String> = emptyList()): Boolean {
        val clean = username.lowercase().replace("@", "").trim()
        val kickUser = prefs.kickUsername.lowercase().replace("@", "").trim()

        if (clean == "steushio" || (kickUser.isNotBlank() && clean == kickUser)) return true

        if (badges.any { 
            it.equals("broadcaster", ignoreCase = true) || 
            it.equals("owner", ignoreCase = true) 
        }) return true

        return false
    }

    private fun isQuestion(message: String): Boolean {
        if (message.contains("?")) return true
        val lower = message.lowercase().trim()
        val qWords = listOf("who", "what", "where", "when", "why", "how", "can", "is", "are", "specs", "mouse", "sens", "rank")
        return qWords.any { lower.startsWith(it) }
    }

    private fun checkCustomCommands(message: String): String? {
        val commands = getParsedCommands()
        val lower = message.lowercase().trim()
        for (cmd in commands) {
            if (lower == cmd.first.lowercase().trim() || lower.startsWith("${cmd.first.lowercase().trim()} ")) {
                return cmd.second
            }
        }
        return null
    }

    private suspend fun handleFirstWords(username: String, message: String, platform: String) {
        if (prefs.firstWordsMode == "template") {
            val template = prefs.firstWordsTemplate.ifEmpty { "Welcome to the stream {username}! Thanks for chatting!" }
            dispatchBotReply(template.replace("{username}", username), platform)
        } else {
            val greetingPrompt = "Welcome this viewer warmly to the stream. Their username is '$username' and their first message was '$message'. Keep it under 20 words."
            val reply = generateUnifiedAiResponse(greetingPrompt, username)
            if (!reply.isNullOrBlank()) {
                dispatchBotReply(reply, platform)
            }
        }
    }

    private suspend fun triggerAiGeneration(userPrompt: String, username: String, platform: String) {
        val now = System.currentTimeMillis()
        val cooldown = prefs.cooldownSeconds * 1000L
        if (now - lastAiResponseTime < cooldown) return

        if (isGenerating) return
        isGenerating = true

        try {
            // Web Search enrichment if query is searchable
            var webContext = ""
            if (prefs.enableWebSearch && webSearchHelper.isSearchableQuery(userPrompt)) {
                onLog("[AI Bot] Querying web knowledge for: \"$userPrompt\"...", "info")
                val snippet = webSearchHelper.search(userPrompt)
                if (!snippet.isNullOrBlank()) {
                    webContext = "\nLive Internet Search Result: $snippet\n"
                    onLog("[AI Bot] Web Search Snippet found!", "success")
                }
            }

            val reply = generateUnifiedAiResponse(userPrompt, username, webContext)
            if (!reply.isNullOrBlank()) {
                lastAiResponseTime = System.currentTimeMillis()
                dispatchBotReply(reply, platform)
            }
        } finally {
            isGenerating = false
        }
    }

    // Unified AI Router: Dispatches to NVIDIA NIM, OpenCode Zen, Gemini, OpenAI (ChatGPT), or Ollama
    suspend fun generateUnifiedAiResponse(
        prompt: String,
        username: String,
        extraContext: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val raw = when (prefs.aiProvider) {
            "nvidia" -> generateNvidiaResponse(prompt, username, extraContext)
            "opencode_zen" -> generateOpenCodeZenResponse(prompt, username, extraContext)
            "gemini" -> generateGeminiResponse(prompt, username, extraContext)
            "openai" -> generateOpenAiResponse(prompt, username, extraContext)
            else -> generateOllamaResponse(prompt, username, extraContext)
        }
        if (raw.isNullOrBlank()) return@withContext null

        // Enforce 1 chat per question: single-line, punchy, no multiple paragraphs or long rambles
        var clean = raw.replace("[\r\n]+".toRegex(), " ")
            .replace("^[\"']|[\"']$".toRegex(), "")
            .trim()

        // Strip bot's own name prefix if the model echoed it (e.g. "Cutie Bot: hello")
        val botPrefix = Regex("^@?(${Regex.escape(prefs.botName)}|cutie\\s*bot)[:\\s,]+", RegexOption.IGNORE_CASE)
        clean = clean.replace(botPrefix, "").trim()

        // Hard limit length to ~140 characters so it stays concise in chat
        if (clean.length > 140) {
            val cut = clean.take(137)
            val lastSpace = cut.lastIndexOf(' ')
            clean = if (lastSpace > 80) cut.substring(0, lastSpace) + "..." else cut + "..."
        }
        clean
    }

    // 0. NVIDIA NIM Free Cloud AI (Meta Llama 3.3, Nemotron, DeepSeek R1)
    suspend fun generateNvidiaResponse(
        prompt: String,
        username: String,
        extraContext: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = prefs.nvidiaApiKey.trim()
        if (apiKey.isBlank()) {
            onLog("[NVIDIA AI] No API Key configured. Please enter your NVIDIA API key in the AI Bot tab.", "error")
            return@withContext "Please configure your NVIDIA API key in the AI Bot tab."
        }

        val baseUrl = "https://integrate.api.nvidia.com/v1"
        val model = prefs.nvidiaModel.ifEmpty { "nvidia/nemotron-3.5-lightning-30b-a3b" }
        val systemInstruction = buildSystemPrompt(extraContext)

        val messagesArray = JsonArray()
        val sysMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemInstruction)
        }
        messagesArray.add(sysMsg)

        synchronized(conversationHistory) {
            conversationHistory.takeLast(4).forEach { (role, content) ->
                val histMsg = JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content)
                }
                messagesArray.add(histMsg)
            }
        }

        val userMsg = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", "$username: $prompt")
        }
        messagesArray.add(userMsg)

        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messagesArray)
            addProperty("max_tokens", 45)
            addProperty("temperature", 0.7)
            addProperty("top_p", 1)

            // Disable thinking for fast conversational replies on Nemotron/thinking models
            val chatTemplate = JsonObject().apply {
                addProperty("enable_thinking", false)
            }
            add("chat_template_kwargs", chatTemplate)
        }

        try {
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .header("User-Agent", "OBS-Chat-Relay/1.0")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string()

            if (response.isSuccessful && !respBody.isNullOrEmpty()) {
                val root = JsonParser.parseString(respBody).asJsonObject
                val choices = root.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val choice = choices[0].asJsonObject
                    val msg = choice.getAsJsonObject("message")
                    val contentElem = msg?.get("content")
                    val reasoningElem = msg?.get("reasoning_content")
                    val content = when {
                        contentElem != null && !contentElem.isJsonNull -> contentElem.asString.trim()
                        reasoningElem != null && !reasoningElem.isJsonNull -> reasoningElem.asString.trim()
                        else -> null
                    }
                    if (!content.isNullOrBlank()) {
                        recordHistory(username, prompt, content)
                        return@withContext content
                    }
                }
            } else {
                val code = response.code
                val errorDetail = try {
                    val errRoot = JsonParser.parseString(respBody ?: "{}").asJsonObject
                    errRoot.getAsJsonObject("error")?.get("message")?.asString ?: "HTTP $code"
                } catch (e: Exception) { "HTTP $code" }
                onLog("[NVIDIA AI] API error ($code): $errorDetail", "error")
            }
        } catch (e: Exception) {
            onLog("[NVIDIA AI] Connection error: ${e.message}", "error")
        }
        null
    }

    // 0. OpenCode Zen API (With Auto-Switching & Free Models Only Filter)
    companion object {
        val NVIDIA_FREE_MODELS = listOf(
            "nvidia/nemotron-3.5-lightning-30b-a3b",
            "meta/llama-3.2-90b-vision-instruct",
            "deepseek-ai/deepseek-v4.1-flash",
            "z-ai/glm-5.3-flash",
            "minimaxai/minimax-m3.1-flash",
            "google/gemma-3-12b-it"
        )

        val DEFAULT_FREE_MODELS = listOf(
            "big-pickle",
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "mimo-v2.6-flash-free",
            "beam-free",
            "flint-40b-free",
            "nemotron-3.5-super-vl-free",
            "nemotron-3.5-lightning-free",
            "crouton-alpha-free",
            "iceman-free",
            "inkling-2-small-free"
        )
    }

    private var cachedOpenCodeModels: List<Pair<String, Boolean>> = emptyList()

    suspend fun fetchOpenCodeZenModels(freeOnly: Boolean = prefs.opencodeZenFreeOnly): List<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://opencode.ai/zen/v1/models")
                .get()
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string()
                if (!body.isNullOrEmpty()) {
                    val root = JsonParser.parseString(body).asJsonObject
                    val dataArr = root.getAsJsonArray("data")
                    val list = mutableListOf<Pair<String, Boolean>>()
                    dataArr?.forEach { item ->
                        val obj = item.asJsonObject
                        val id = obj.get("id")?.asString ?: return@forEach
                        val name = obj.get("name")?.asString ?: id
                        var isFree = id.contains("free", ignoreCase = true) || name.contains("free", ignoreCase = true)
                        try {
                            val costObj = obj.getAsJsonObject("cost")
                            val textObj = costObj?.getAsJsonObject("text")
                            val inputObj = textObj?.getAsJsonObject("input")
                            val outputObj = textObj?.getAsJsonObject("output")
                            val inPrice = inputObj?.get("price")?.asDouble ?: 1.0
                            val outPrice = outputObj?.get("price")?.asDouble ?: 1.0
                            if (inPrice == 0.0 && outPrice == 0.0) {
                                isFree = true
                            }
                        } catch (e: Exception) {}
                        list.add(id to isFree)
                    }
                    cachedOpenCodeModels = list
                    return@withContext if (freeOnly) {
                        list.filter { it.second }.map { it.first }
                    } else {
                        list.map { it.first }
                    }
                }
            }
        } catch (e: Exception) {
            onLog("[OpenCode Zen] Catalog fetch warning: ${e.message}", "warning")
        }
        if (freeOnly) DEFAULT_FREE_MODELS else DEFAULT_FREE_MODELS
    }

    suspend fun generateOpenCodeZenResponse(
        prompt: String,
        username: String,
        extraContext: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = prefs.opencodeZenApiKey.trim()
        if (apiKey.isBlank()) {
            onLog("[OpenCode Zen] No API key configured. Please enter your OpenCode Zen API key in the AI Bot tab.", "error")
            return@withContext "Please configure your OpenCode Zen API key in the AI Bot tab."
        }

        // Available models pool
        val availableModels = if (cachedOpenCodeModels.isNotEmpty()) {
            if (prefs.opencodeZenFreeOnly) cachedOpenCodeModels.filter { it.second }.map { it.first }
            else cachedOpenCodeModels.map { it.first }
        } else {
            DEFAULT_FREE_MODELS
        }

        var currentModel = prefs.opencodeZenModel.ifEmpty { "big-pickle" }
        if (prefs.opencodeZenFreeOnly && !availableModels.contains(currentModel)) {
            currentModel = availableModels.firstOrNull() ?: "big-pickle"
            prefs.opencodeZenModel = currentModel
        }

        val systemInstruction = buildSystemPrompt(extraContext)
        val maxAttempts = if (prefs.opencodeZenAutoSwitch) Math.min(4, availableModels.size) else 1
        val triedModels = mutableSetOf<String>()

        for (attempt in 0 until maxAttempts) {
            triedModels.add(currentModel)
            val messagesArray = JsonArray()
            messagesArray.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemInstruction)
            })
            messagesArray.add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "$username: $prompt")
            })

            val payload = JsonObject().apply {
                addProperty("model", currentModel)
                add("messages", messagesArray)
                addProperty("max_tokens", 80)
                addProperty("temperature", 0.7)
            }

            try {
                val url = "https://opencode.ai/zen/v1/chat/completions"
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("User-Agent", "OBS-Chat-Relay/1.0")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val respBody = response.body?.string()

                if (response.isSuccessful && !respBody.isNullOrEmpty()) {
                    val root = JsonParser.parseString(respBody).asJsonObject
                    val choices = root.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val choice = choices[0].asJsonObject
                        val msg = choice.getAsJsonObject("message")
                        val content = msg?.get("content")?.asString?.trim()
                        if (!content.isNullOrBlank()) {
                            recordHistory(username, prompt, content)
                            return@withContext content
                        }
                    }
                } else {
                    val code = response.code
                    val errorDetail = try {
                        val errRoot = JsonParser.parseString(respBody ?: "{}").asJsonObject
                        errRoot.getAsJsonObject("error")?.get("message")?.asString ?: "HTTP $code"
                    } catch (e: Exception) { "HTTP $code" }

                    onLog("[OpenCode Zen] Model $currentModel issue ($code: $errorDetail)", "warning")

                    val isQuotaOrRateLimit = code == 402 || code == 429 || code == 400 || code == 503 ||
                            errorDetail.contains("credit", ignoreCase = true) ||
                            errorDetail.contains("quota", ignoreCase = true) ||
                            errorDetail.contains("rate", ignoreCase = true) ||
                            errorDetail.contains("limit", ignoreCase = true)

                    if (isQuotaOrRateLimit && prefs.opencodeZenAutoSwitch) {
                        val nextModel = availableModels.firstOrNull { !triedModels.contains(it) }
                        if (nextModel != null) {
                            onLog("[OpenCode Zen] Quota/credits ran out on $currentModel. Auto-switching to next free model: $nextModel...", "info")
                            currentModel = nextModel
                            prefs.opencodeZenModel = nextModel
                            continue
                        }
                    }
                }
            } catch (e: Exception) {
                onLog("[OpenCode Zen] Error with $currentModel: ${e.message}", "error")
                if (prefs.opencodeZenAutoSwitch) {
                    val nextModel = availableModels.firstOrNull { !triedModels.contains(it) }
                    if (nextModel != null) {
                        onLog("[OpenCode Zen] Auto-switching to model: $nextModel", "info")
                        currentModel = nextModel
                        prefs.opencodeZenModel = nextModel
                        continue
                    }
                }
            }
        }
        null
    }

    // 1. Google Gemini API
    suspend fun generateGeminiResponse(
        prompt: String,
        username: String,
        extraContext: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = prefs.geminiApiKey.trim()
        if (apiKey.isBlank()) {
            onLog("[Gemini AI] No API Key configured. Please enter your Gemini API key in settings.", "error")
            return@withContext "Please configure your Google Gemini API key in the AI Bot tab."
        }

        val model = prefs.geminiModel.ifEmpty { "gemini-1.5-flash" }
        val systemInstruction = buildSystemPrompt(extraContext)

        val payload = JsonObject().apply {
            val sysObj = JsonObject().apply {
                val parts = JsonArray()
                val partObj = JsonObject()
                partObj.addProperty("text", systemInstruction)
                parts.add(partObj)
                add("parts", parts)
            }
            add("systemInstruction", sysObj)

            val contents = JsonArray()
            val userContent = JsonObject().apply {
                addProperty("role", "user")
                val parts = JsonArray()
                val partObj = JsonObject()
                partObj.addProperty("text", "$username asks: $prompt")
                parts.add(partObj)
                add("parts", parts)
            }
            contents.add(userContent)
            add("contents", contents)

            val genConfig = JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("maxOutputTokens", 80)
            }
            add("generationConfig", genConfig)
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string()
                if (!respBody.isNullOrEmpty()) {
                    val root = JsonParser.parseString(respBody).asJsonObject
                    val candidates = root.getAsJsonArray("candidates")
                    if (candidates != null && candidates.size() > 0) {
                        val first = candidates[0].asJsonObject
                        val contentObj = first.getAsJsonObject("content")
                        val parts = contentObj?.getAsJsonArray("parts")
                        if (parts != null && parts.size() > 0) {
                            val text = parts[0].asJsonObject.get("text")?.asString?.trim()
                            if (!text.isNullOrBlank()) {
                                recordHistory(username, prompt, text)
                                return@withContext text
                            }
                        }
                    }
                }
            } else {
                onLog("[Gemini AI] API error HTTP ${response.code}: ${response.message}", "error")
            }
        } catch (e: Exception) {
            onLog("[Gemini AI] Connection error: ${e.message}", "error")
        }
        null
    }

    // 2. OpenAI / ChatGPT API (or compatible Groq, OpenRouter, DeepSeek)
    suspend fun generateOpenAiResponse(
        prompt: String,
        username: String,
        extraContext: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = prefs.openaiApiKey.trim()
        if (apiKey.isBlank()) {
            onLog("[ChatGPT] No API Key configured. Please enter your OpenAI API key in settings.", "error")
            return@withContext "Please configure your OpenAI API key in the AI Bot tab."
        }

        val baseUrl = prefs.openaiBaseUrl.ifEmpty { "https://api.openai.com/v1" }.trimEnd('/')
        val model = prefs.openaiModel.ifEmpty { "gpt-4o-mini" }
        val systemInstruction = buildSystemPrompt(extraContext)

        val messagesArray = JsonArray()
        val sysMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemInstruction)
        }
        messagesArray.add(sysMsg)

        val userMsg = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", "$username: $prompt")
        }
        messagesArray.add(userMsg)

        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messagesArray)
            addProperty("max_tokens", 80)
            addProperty("temperature", 0.7)
        }

        try {
            val url = "$baseUrl/chat/completions"
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string()
                if (!respBody.isNullOrEmpty()) {
                    val root = JsonParser.parseString(respBody).asJsonObject
                    val choices = root.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val choice = choices[0].asJsonObject
                        val msg = choice.getAsJsonObject("message")
                        val content = msg?.get("content")?.asString?.trim()
                        if (!content.isNullOrBlank()) {
                            recordHistory(username, prompt, content)
                            return@withContext content
                        }
                    }
                }
            } else {
                onLog("[ChatGPT] API error HTTP ${response.code}: ${response.message}", "error")
            }
        } catch (e: Exception) {
            onLog("[ChatGPT] Connection error: ${e.message}", "error")
        }
        null
    }

    // 3. Local / Remote Ollama API
    suspend fun generateOllamaResponse(
        prompt: String,
        username: String,
        extraContext: String = "",
        overrideModel: String? = null,
        overrideUrl: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(overrideUrl ?: prefs.ollamaUrl)
        val model = overrideModel ?: prefs.ollamaModel
        val systemContent = buildSystemPrompt(extraContext)

        val messagesArray = JsonArray()
        val sysMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemContent)
        }
        messagesArray.add(sysMsg)

        val userMsg = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", "$username: $prompt")
        }
        messagesArray.add(userMsg)

        val requestPayload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messagesArray)
            addProperty("stream", false)
            val options = JsonObject().apply {
                addProperty("num_thread", prefs.cpuThreads)
                addProperty("temperature", 0.7)
            }
            add("options", options)
        }

        try {
            val body = requestPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string()
                if (!respBody.isNullOrEmpty()) {
                    val root = JsonParser.parseString(respBody).asJsonObject
                    val messageObj = root.getAsJsonObject("message")
                    val content = messageObj?.get("content")?.asString?.trim()
                    if (!content.isNullOrBlank()) {
                        recordHistory(username, prompt, content)
                        return@withContext content
                    }
                }
            } else {
                onLog("[Ollama] API returned HTTP ${response.code}", "error")
            }
        } catch (e: Exception) {
            onLog("[Ollama] Inference error: ${e.message}", "error")
        }
        null
    }

    private fun buildSystemPrompt(extraContext: String): String {
        return buildString {
            append(prefs.systemPrompt)
            append("\n\nStreamer Knowledge & FAQ:\n")
            append(prefs.streamerKnowledge)
            if (prefs.streamDescription.isNotBlank()) {
                append("\n\nStream Description:\n")
                append(prefs.streamDescription)
            }
            if (extraContext.isNotBlank()) {
                append("\n\n$extraContext")
            }
            append("\n\nCRITICAL LIVE STREAM CHAT RULES:\n" +
                   "- Strictly 1 SHORT sentence only (maximum 15 words).\n" +
                   "- Answer directly without greetings or filler.\n" +
                   "- NEVER write multiple sentences, lists, or paragraphs. Keep it to a single chat message.\n" +
                   "- Natural gamer/streamer tone.")
        }
    }

    private fun recordHistory(username: String, prompt: String, reply: String) {
        synchronized(conversationHistory) {
            conversationHistory.add("user" to "$username: $prompt")
            conversationHistory.add("assistant" to reply)
            if (conversationHistory.size > 10) {
                conversationHistory.removeAt(0)
                conversationHistory.removeAt(0)
            }
        }
    }

    private fun dispatchBotReply(text: String, platform: String) {
        val botMessage = ChatMessage(
            type = "chat",
            platform = platform,
            id = "bot_${System.currentTimeMillis()}",
            username = prefs.botName,
            message = text,
            badges = listOf("moderator", "bot"),
            color = "#ef4444",
            timestamp = System.currentTimeMillis()
        )
        onBotReply(botMessage)
        onLog("[AI Bot Reply] ${prefs.botName}: $text", "success")
    }

    // Timer state management (chat-activity gated)
    private var totalUserMessages = 0
    private var messagesSinceLastTimer = 0
    private var neededMessages = 1 // Start with 1 so first timer pops after 1st user message
    private var lastPostTime = 0L
    private var firstTimerScheduled = false

    fun resetTimerState() {
        totalUserMessages = 0
        messagesSinceLastTimer = 0
        neededMessages = 1
        lastPostTime = 0L
        firstTimerScheduled = false
    }

    fun recordUserMessageForTimers(platform: String) {
        totalUserMessages++
        messagesSinceLastTimer++

        // 1st message after 1st message of a user then random.
        if (totalUserMessages == 1 && !firstTimerScheduled) {
            firstTimerScheduled = true
            onLog("[Ollama Bot Timer] 1st user message detected on $platform. Scheduling first timer message.", "info")
            scope.launch {
                delay(5500L) // Wait 5.5s so greeting is dispatched first
                postNextTimer(platform, "after 1st user welcome message")
            }
        } else if (totalUserMessages > 1) {
            checkTimerTrigger(platform)
        }
    }

    private fun checkTimerTrigger(platform: String) {
        val enabledTimers = getParsedTimers().filter { it.enabled && it.message.isNotBlank() && it.intervalMinutes > 0 }
        if (enabledTimers.isEmpty()) return

        // 1. Only pop if it saw active viewer messages on chat (>= neededMessages)
        if (messagesSinceLastTimer < neededMessages) {
            return
        }

        // 2. Minimum interval elapsed check
        val minIntervalMinutes = enabledTimers.minOfOrNull { it.intervalMinutes } ?: 5L
        val minIntervalMs = minIntervalMinutes * 60 * 1000L
        val timeSinceLast = System.currentTimeMillis() - lastPostTime

        if (lastPostTime > 0 && timeSinceLast < minIntervalMs) {
            return
        }

        postNextTimer(platform, "interval & chat activity threshold met")
    }

    private fun postNextTimer(platform: String, reason: String = "") {
        val enabledTimers = getParsedTimers().filter { it.enabled && it.message.isNotBlank() && it.intervalMinutes > 0 }
        if (enabledTimers.isEmpty()) return

        // Safety: never post to a completely dead/empty chat
        if (totalUserMessages == 0 && messagesSinceLastTimer == 0) {
            return
        }

        // Pick one timer randomly from enabled timers
        val selectedTimer = enabledTimers.random()

        onLog("[Ollama Bot Timer -> ${platform.uppercase()}] Triggered ($reason): \"${selectedTimer.message}\" (after $messagesSinceLastTimer viewer chats)", "info")
        dispatchBotReply(selectedTimer.message, platform)

        // Reset counters and randomly choose next threshold between 1 and 5 user messages
        lastPostTime = System.currentTimeMillis()
        messagesSinceLastTimer = 0
        neededMessages = (1..5).random() // Random integer: 1, 2, 3, 4, or 5
        onLog("[Ollama Bot Timer] Next timer on $platform requires $neededMessages viewer chats.", "info")
    }

    fun startTimers() {
        timersJob?.cancel()
        val timers = getParsedTimers()
        if (timers.isEmpty()) return

        timersJob = scope.launch {
            while (isActive) {
                delay(15_000L) // Periodic check every 15s
                checkTimerTrigger("youtube")
            }
        }
    }

    fun getParsedCommands(): List<Pair<String, String>> {
        val raw = prefs.customCommandsJson
        if (raw.isBlank()) {
            return listOf(
                "!discord" to "Join our Discord community: https://discord.gg/UBsenpp",
                "!specs" to "PC Specs: i5-10600K | RX 550 | 16GB RAM | Mouse: VXE R1se+",
                "!socials" to "Follow on YouTube @steushio and Discord!"
            )
        }
        return try {
            val list = mutableListOf<Pair<String, String>>()
            val arr = JsonParser.parseString(raw).asJsonArray
            arr.forEach {
                val obj = it.asJsonObject
                list.add(obj.get("trigger").asString to obj.get("response").asString)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCommands(commands: List<Pair<String, String>>) {
        val arr = JsonArray()
        commands.forEach {
            val obj = JsonObject()
            obj.addProperty("trigger", it.first)
            obj.addProperty("response", it.second)
            arr.add(obj)
        }
        prefs.customCommandsJson = arr.toString()
    }

    data class BotTimer(
        val id: String = java.util.UUID.randomUUID().toString(),
        val enabled: Boolean = true,
        val intervalMinutes: Long = 10,
        val message: String = ""
    )

    fun getParsedTimers(): List<BotTimer> {
        val raw = prefs.timersJson
        if (raw.isBlank()) {
            return listOf(
                BotTimer(
                    id = "timer_default_1",
                    enabled = true,
                    intervalMinutes = 10,
                    message = "Enjoying the stream? Make sure to subscribe and drop a like!"
                )
            )
        }
        return try {
            val list = mutableListOf<BotTimer>()
            val arr = JsonParser.parseString(raw).asJsonArray
            arr.forEach {
                val obj = it.asJsonObject
                list.add(
                    BotTimer(
                        id = obj.get("id")?.asString ?: java.util.UUID.randomUUID().toString(),
                        enabled = obj.get("enabled")?.asBoolean ?: true,
                        intervalMinutes = (obj.get("intervalMinutes")?.asLong ?: 10).coerceAtLeast(1),
                        message = obj.get("message")?.asString ?: ""
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTimers(timers: List<BotTimer>) {
        val arr = JsonArray()
        timers.forEach {
            val obj = JsonObject()
            obj.addProperty("id", it.id)
            obj.addProperty("enabled", it.enabled)
            obj.addProperty("intervalMinutes", it.intervalMinutes)
            obj.addProperty("message", it.message)
            arr.add(obj)
        }
        prefs.timersJson = arr.toString()
        startTimers()
    }

    fun resetConversation() {
        synchronized(conversationHistory) {
            conversationHistory.clear()
        }
        seenChatters.clear()
        resetTimerState()
        onLog("[AI Bot] Memory, chatter cache, and timer state reset", "info")
    }

    fun stop() {
        timersJob?.cancel()
        scope.cancel()
    }
}
