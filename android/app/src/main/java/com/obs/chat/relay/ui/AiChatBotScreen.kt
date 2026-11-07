package com.obs.chat.relay.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obs.chat.relay.OBSChatRelayApp
import com.obs.chat.relay.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatBotScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { OBSChatRelayApp.instance.controller }
    val bot = controller.ollamaBot
    val prefs = controller.prefs
    val sessionManager = controller.sessionManager
    val ytPostingMode by sessionManager.ytPostingModeFlow.collectAsState()
    val kickPostingMode by sessionManager.kickPostingModeFlow.collectAsState()

    // Provider: "nvidia" | "opencode_zen" | "gemini" | "openai" | "ollama"
    var aiProvider by remember { mutableStateOf(prefs.aiProvider) }

    // NVIDIA NIM
    var nvidiaKey by remember { mutableStateOf(prefs.nvidiaApiKey) }
    var nvidiaModel by remember { mutableStateOf(prefs.nvidiaModel) }

    // OpenCode Zen
    var opencodeZenKey by remember { mutableStateOf(prefs.opencodeZenApiKey) }
    var opencodeZenModel by remember { mutableStateOf(prefs.opencodeZenModel) }
    var opencodeZenFreeOnly by remember { mutableStateOf(prefs.opencodeZenFreeOnly) }
    var opencodeZenAutoSwitch by remember { mutableStateOf(prefs.opencodeZenAutoSwitch) }
    var availableOpenCodeModels by remember {
        mutableStateOf(com.obs.chat.relay.bot.OllamaBotEngine.DEFAULT_FREE_MODELS)
    }

    // Gemini
    var geminiKey by remember { mutableStateOf(prefs.geminiApiKey) }
    var geminiModel by remember { mutableStateOf(prefs.geminiModel) }

    // OpenAI
    var openaiKey by remember { mutableStateOf(prefs.openaiApiKey) }
    var openaiModel by remember { mutableStateOf(prefs.openaiModel) }
    var openaiBaseUrl by remember { mutableStateOf(prefs.openaiBaseUrl) }

    // Ollama
    var ollamaUrl by remember { mutableStateOf(prefs.ollamaUrl) }
    var selectedOllamaModel by remember { mutableStateOf(prefs.ollamaModel) }
    var availableOllamaModels by remember { mutableStateOf(listOf("qwen2.5:1.5b", "qwen2.5:0.5b", "llama3.2:1b", "qwen2.5:3b")) }
    var isFetchingModels by remember { mutableStateOf(false) }

    // General Bot Settings
    var botName by remember { mutableStateOf(prefs.botName) }
    var autoRespond by remember { mutableStateOf(prefs.autoRespond) }
    var cooldown by remember { mutableStateOf(prefs.cooldownSeconds.toFloat()) }
    var webSearch by remember { mutableStateOf(prefs.enableWebSearch) }
    var firstWordsEnabled by remember { mutableStateOf(prefs.firstWordsEnabled) }
    var firstWordsMode by remember { mutableStateOf(prefs.firstWordsMode) }
    var firstWordsTemplate by remember { mutableStateOf(prefs.firstWordsTemplate) }
    var streamerKnowledge by remember { mutableStateOf(prefs.streamerKnowledge) }
    var systemPrompt by remember { mutableStateOf(prefs.systemPrompt) }

    // Test prompt states
    var testPrompt by remember { mutableStateOf("Why do you play without a keyboard?") }
    var testResponse by remember { mutableStateOf<String?>(null) }
    var isTestingAi by remember { mutableStateOf(false) }

    // Custom commands states
    var commandsList by remember { mutableStateOf(bot.getParsedCommands()) }
    var newCmdTrigger by remember { mutableStateOf("") }
    var newCmdResponse by remember { mutableStateOf("") }
    var showAddCmdDialog by remember { mutableStateOf(false) }

    // Scheduled Timers states
    var timersList by remember { mutableStateOf(bot.getParsedTimers()) }
    var newTimerMinutes by remember { mutableStateOf("10") }
    var newTimerMessage by remember { mutableStateOf("") }
    var showAddTimerDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header
        item {
            Text(
                "Live Stream AI Bot",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain
            )
            Text(
                "Use Google Gemini, OpenAI ChatGPT, or Local On-Device Ollama to automatically answer viewer chat.",
                fontSize = 12.sp,
                color = TextMuted
            )
        }

        // 2. AI Engine / Provider Selector Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select AI Engine Provider", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = aiProvider == "nvidia",
                            onClick = {
                                aiProvider = "nvidia"
                                prefs.aiProvider = "nvidia"
                            },
                            label = { Text("🟢 NVIDIA AI (Free NIM)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = KickGreen,
                                selectedLabelColor = Color.Black
                            )
                        )

                        FilterChip(
                            selected = aiProvider == "gemini",
                            onClick = {
                                aiProvider = "gemini"
                                prefs.aiProvider = "gemini"
                            },
                            label = { Text("Google Gemini", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentBlue,
                                selectedLabelColor = Color.Black
                            )
                        )

                        FilterChip(
                            selected = aiProvider == "opencode_zen",
                            onClick = {
                                aiProvider = "opencode_zen"
                                prefs.aiProvider = "opencode_zen"
                            },
                            label = { Text("⚡ OpenCode Zen", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPurple,
                                selectedLabelColor = Color.White
                            )
                        )

                        FilterChip(
                            selected = aiProvider == "openai",
                            onClick = {
                                aiProvider = "openai"
                                prefs.aiProvider = "openai"
                            },
                            label = { Text("OpenAI / ChatGPT", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF10A37F),
                                selectedLabelColor = Color.White
                            )
                        )

                        FilterChip(
                            selected = aiProvider == "ollama",
                            onClick = {
                                aiProvider = "ollama"
                                prefs.aiProvider = "ollama"
                            },
                            label = { Text("Local Ollama", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentRed,
                                selectedLabelColor = Color.White
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // PROVIDER: NVIDIA AI Configuration
                    if (aiProvider == "nvidia") {
                        Text("🟢 NVIDIA NIM Cloud AI (Free Models)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = KickGreen)
                        Text(
                            "High-speed cloud AI powered by NVIDIA Build. Simply paste your API key from build.nvidia.com (starts with nvapi-).",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = nvidiaKey,
                            onValueChange = {
                                nvidiaKey = it
                                prefs.nvidiaApiKey = it
                            },
                            label = { Text("NVIDIA API Key (nvapi-...)") },
                            placeholder = { Text("Paste your NVIDIA key here") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = KickGreen,
                                unfocusedBorderColor = BorderDark
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Active Model: $nvidiaModel", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KickGreen)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            com.obs.chat.relay.bot.OllamaBotEngine.NVIDIA_FREE_MODELS.forEach { m ->
                                FilterChip(
                                    selected = nvidiaModel == m,
                                    onClick = {
                                        nvidiaModel = m
                                        prefs.nvidiaModel = m
                                    },
                                    label = { Text(m.substringAfter("/"), fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = KickGreen,
                                        selectedLabelColor = Color.Black
                                    )
                                )
                            }
                        }
                    }

                    // PROVIDER 0: OpenCode Zen Configuration
                    if (aiProvider == "opencode_zen") {
                        Text("⚡ OpenCode Zen Configuration", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AccentPurple)
                        Text(
                            "Gateway with dozens of 100% Free AI models (Big Pickle, DeepSeek V4.1 Flash, GLM-5.3, MiMo Flash, Beam). Grab your key at opencode.ai/auth.",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = opencodeZenKey,
                            onValueChange = {
                                opencodeZenKey = it
                                prefs.opencodeZenApiKey = it
                            },
                            label = { Text("OpenCode Zen API Key") },
                            placeholder = { Text("Paste your OpenCode Zen key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPurple,
                                unfocusedBorderColor = BorderDark
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Free Models Only Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Only use free models", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                Text("Prevents accidental credit spend. Only selects $0 cost models.", fontSize = 11.sp, color = TextMuted)
                            }
                            Switch(
                                checked = opencodeZenFreeOnly,
                                onCheckedChange = {
                                    opencodeZenFreeOnly = it
                                    prefs.opencodeZenFreeOnly = it
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Auto-switch models toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-switch models on rate limit / credit run-out", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                Text("If current model runs out of credits or is rate limited, automatically switches to next free model.", fontSize = 11.sp, color = TextMuted)
                            }
                            Switch(
                                checked = opencodeZenAutoSwitch,
                                onCheckedChange = {
                                    opencodeZenAutoSwitch = it
                                    prefs.opencodeZenAutoSwitch = it
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Active Model: $opencodeZenModel", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentPurple)
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val models = bot.fetchOpenCodeZenModels(opencodeZenFreeOnly)
                                        if (models.isNotEmpty()) {
                                            availableOpenCodeModels = models
                                            Toast.makeText(context, "Fetched ${models.size} models from OpenCode Zen!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ) {
                                Text("🔄 Refresh Models", fontSize = 11.sp, color = AccentPurple)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Model Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableOpenCodeModels.forEach { m ->
                                FilterChip(
                                    selected = opencodeZenModel == m,
                                    onClick = {
                                        opencodeZenModel = m
                                        prefs.opencodeZenModel = m
                                    },
                                    label = { Text(m, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentPurple,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }

                    // PROVIDER 1: Google Gemini Configuration
                    if (aiProvider == "gemini") {
                        Text("✨ Google Gemini Configuration", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AccentBlue)
                        Text(
                            "Ultra-fast & free tier (15 RPM). Get your free key at aistudio.google.com with no credit card required.",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = geminiKey,
                            onValueChange = {
                                geminiKey = it
                                prefs.geminiApiKey = it
                            },
                            label = { Text("Gemini API Key (AIzaSy...)") },
                            placeholder = { Text("Paste your Google Gemini API key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = BorderDark
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Select Gemini Model:", fontSize = 12.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(4.dp))

                        val geminiModels = listOf("gemini-1.5-flash", "gemini-2.0-flash", "gemini-1.5-pro")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            geminiModels.forEach { m ->
                                FilterChip(
                                    selected = geminiModel == m,
                                    onClick = {
                                        geminiModel = m
                                        prefs.geminiModel = m
                                    },
                                    label = { Text(m, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    // PROVIDER 2: OpenAI / ChatGPT Configuration
                    if (aiProvider == "openai") {
                        Text("⚡ OpenAI ChatGPT Configuration", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = KickGreen)
                        Text(
                            "Supports standard OpenAI API keys or OpenAI-compatible endpoints (Groq, DeepSeek, OpenRouter).",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = openaiKey,
                            onValueChange = {
                                openaiKey = it
                                prefs.openaiApiKey = it
                            },
                            label = { Text("OpenAI API Key (sk-...)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = KickGreen,
                                unfocusedBorderColor = BorderDark
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = openaiModel,
                            onValueChange = {
                                openaiModel = it
                                prefs.openaiModel = it
                            },
                            label = { Text("Model Name (e.g. gpt-4o-mini, gpt-4o)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = openaiBaseUrl,
                            onValueChange = {
                                openaiBaseUrl = it
                                prefs.openaiBaseUrl = it
                            },
                            label = { Text("API Base URL (Default: https://api.openai.com/v1)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    // PROVIDER 3: Local Ollama Configuration
                    if (aiProvider == "ollama") {
                        Text("🦙 Local / On-Device Ollama Configuration", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AccentRed)
                        Text(
                            "Runs directly on your phone via Termux or connects to local network Ollama.",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = ollamaUrl,
                            onValueChange = {
                                ollamaUrl = it
                                prefs.ollamaUrl = it
                            },
                            label = { Text("Ollama Server URL (Default: http://127.0.0.1:11434)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentRed,
                                unfocusedBorderColor = BorderDark
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Model: $selectedOllamaModel", fontSize = 12.sp, color = AccentRed, fontWeight = FontWeight.SemiBold)
                            TextButton(
                                onClick = {
                                    isFetchingModels = true
                                    scope.launch {
                                        val list = bot.fetchModels(ollamaUrl)
                                        isFetchingModels = false
                                        if (list.isNotEmpty()) {
                                            availableOllamaModels = list
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isFetchingModels) "Fetching..." else "Fetch Models", fontSize = 12.sp)
                            }
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(availableOllamaModels) { model ->
                                FilterChip(
                                    selected = selectedOllamaModel == model,
                                    onClick = {
                                        selectedOllamaModel = model
                                        prefs.ollamaModel = model
                                    },
                                    label = { Text(model, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Bot Persona & Triggers
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bot Identity & Trigger Rules", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = botName,
                        onValueChange = {
                            botName = it
                            prefs.botName = it
                        },
                        label = { Text("Bot Name in Chat") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = BorderDark
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("AI Bot Posting Identity (Account Switching):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                    Spacer(modifier = Modifier.height(6.dp))

                    // YouTube Account Switcher
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("YouTube Bot Identity", fontSize = 11.sp, color = AccentRed, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = { sessionManager.updateYtPostingMode("broadcaster") },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (ytPostingMode == "broadcaster") AccentRed.copy(alpha = 0.2f) else Color.Transparent,
                                    contentColor = if (ytPostingMode == "broadcaster") AccentRed else TextMuted
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (ytPostingMode == "broadcaster") AccentRed else BorderDark),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Broadcaster", fontSize = 10.sp)
                            }

                            OutlinedButton(
                                onClick = { sessionManager.updateYtPostingMode("bot_account") },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (ytPostingMode == "bot_account") AccentPurple.copy(alpha = 0.2f) else Color.Transparent,
                                    contentColor = if (ytPostingMode == "bot_account") AccentPurple else TextMuted
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (ytPostingMode == "bot_account") AccentPurple else BorderDark),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Dedicated Bot", fontSize = 10.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Kick Account Switcher
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Kick Bot Identity", fontSize = 11.sp, color = KickGreen, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = { sessionManager.updateKickPostingMode("broadcaster") },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (kickPostingMode == "broadcaster") KickGreen.copy(alpha = 0.2f) else Color.Transparent,
                                    contentColor = if (kickPostingMode == "broadcaster") KickGreen else TextMuted
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (kickPostingMode == "broadcaster") KickGreen else BorderDark),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Broadcaster", fontSize = 10.sp)
                            }

                            OutlinedButton(
                                onClick = { sessionManager.updateKickPostingMode("bot_account") },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (kickPostingMode == "bot_account") AccentPurple.copy(alpha = 0.2f) else Color.Transparent,
                                    contentColor = if (kickPostingMode == "bot_account") AccentPurple else TextMuted
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (kickPostingMode == "bot_account") AccentPurple else BorderDark),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Dedicated Bot", fontSize = 10.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Auto-Respond Trigger Mode:", fontSize = 12.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(4.dp))

                    val triggerModes = listOf(
                        "questions_and_mentions" to "Questions & Mentions (@bot)",
                        "mention_only" to "Mentions Only",
                        "all_chat" to "All Chat Messages",
                        "off" to "Off (Commands Only)"
                    )

                    triggerModes.forEach { (modeKey, modeTitle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autoRespond = modeKey
                                    prefs.autoRespond = modeKey
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoRespond == modeKey,
                                onClick = {
                                    autoRespond = modeKey
                                    prefs.autoRespond = modeKey
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(modeTitle, fontSize = 13.sp, color = TextMain)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Cooldown: ${cooldown.toInt()}s between replies", fontSize = 12.sp, color = TextMuted)
                    Slider(
                        value = cooldown,
                        onValueChange = {
                            cooldown = it
                            prefs.cooldownSeconds = it.toInt()
                        },
                        valueRange = 2f..30f,
                        steps = 28
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // DuckDuckGo Web Search toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DuckDuckGo Live Web Search", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextMain)
                            Text("Queries internet for real-time facts and scores", fontSize = 11.sp, color = TextMuted)
                        }
                        Switch(
                            checked = webSearch,
                            onCheckedChange = {
                                webSearch = it
                                prefs.enableWebSearch = it
                            }
                        )
                    }
                }
            }
        }

        // 4. First-Words Welcoming
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("First-Words Welcoming", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                            Text("Warmly greets new viewers on their very first message", fontSize = 11.sp, color = TextMuted)
                        }
                        Switch(
                            checked = firstWordsEnabled,
                            onCheckedChange = {
                                firstWordsEnabled = it
                                prefs.firstWordsEnabled = it
                            }
                        )
                    }

                    if (firstWordsEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = firstWordsMode == "ai",
                                onClick = {
                                    firstWordsMode = "ai"
                                    prefs.firstWordsMode = "ai"
                                },
                                label = { Text("Personalized AI Greeting", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = firstWordsMode == "template",
                                onClick = {
                                    firstWordsMode = "template"
                                    prefs.firstWordsMode = "template"
                                },
                                label = { Text("Template Greeting", fontSize = 11.sp) }
                            )
                        }

                        if (firstWordsMode == "template") {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = firstWordsTemplate,
                                onValueChange = {
                                    firstWordsTemplate = it
                                    prefs.firstWordsTemplate = it
                                },
                                label = { Text("Welcome Template ({username})") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }

        // 5. Streamer Knowledge & FAQ Base
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Streamer Knowledge & FAQ Context", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Text("The AI answers questions about your gear, hardware, backstory, and playstyle based on this text.", fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = streamerKnowledge,
                        onValueChange = {
                            streamerKnowledge = it
                            prefs.streamerKnowledge = it
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = BorderDark
                        )
                    )
                }
            }
        }

        // 6. Custom Commands Manager
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Custom Chat Commands", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                        IconButton(onClick = { showAddCmdDialog = true }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add Command", tint = KickGreen)
                        }
                    }

                    commandsList.forEach { (cmd, resp) ->
                        Surface(
                            color = BgDark,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cmd, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = KickGreen)
                                    Text(resp, fontSize = 11.sp, color = TextMuted)
                                }
                                IconButton(
                                    onClick = {
                                        val updated = commandsList.filter { it.first != cmd }
                                        commandsList = updated
                                        bot.saveCommands(updated)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Scheduled Chat Timers (Activity-gated)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scheduled Chat Timers", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                            Text("Activity-gated: pops after 1st chat, then random 1-5 viewer messages.", fontSize = 11.sp, color = TextMuted)
                        }
                        IconButton(onClick = { showAddTimerDialog = true }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add Timer", tint = KickGreen)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (timersList.isEmpty()) {
                        Text("No timers configured. Tap + to create one.", fontSize = 12.sp, color = TextMuted)
                    }

                    timersList.forEach { timer ->
                        Surface(
                            color = BgDark,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Switch(
                                    checked = timer.enabled,
                                    onCheckedChange = { checked ->
                                        val updated = timersList.map {
                                            if (it.id == timer.id) it.copy(enabled = checked) else it
                                        }
                                        timersList = updated
                                        bot.saveTimers(updated)
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Every ${timer.intervalMinutes} min", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = KickGreen)
                                    Text(timer.message, fontSize = 12.sp, color = TextMain)
                                }
                                IconButton(
                                    onClick = {
                                        val updated = timersList.filter { it.id != timer.id }
                                        timersList = updated
                                        bot.saveTimers(updated)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. Interactive Test AI Tool
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Interactive AI Test Prompt", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Text("Tests the active AI provider (${aiProvider.uppercase()}) using your streamer knowledge", fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = testPrompt,
                        onValueChange = { testPrompt = it },
                        label = { Text("Viewer Question / Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            isTestingAi = true
                            testResponse = null
                            scope.launch {
                                val reply = bot.generateUnifiedAiResponse(testPrompt, "Tester")
                                isTestingAi = false
                                testResponse = reply ?: "No response received. Check your API key or server URL."
                            }
                        },
                        enabled = !isTestingAi && testPrompt.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTestingAi) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generating with ${aiProvider.uppercase()}...")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send Test Prompt", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (testResponse != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = BgDark,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Bot Reply (${aiProvider.uppercase()}):", fontSize = 11.sp, color = AccentBlue, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(testResponse ?: "", fontSize = 13.sp, color = TextMain)
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Command Dialog
    if (showAddCmdDialog) {
        AlertDialog(
            onDismissRequest = { showAddCmdDialog = false },
            title = { Text("Add Custom Command", color = TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCmdTrigger,
                        onValueChange = { newCmdTrigger = it },
                        label = { Text("Trigger (e.g. !specs)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newCmdResponse,
                        onValueChange = { newCmdResponse = it },
                        label = { Text("Response Text") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCmdTrigger.isNotBlank() && newCmdResponse.isNotBlank()) {
                            val updated = commandsList.toMutableList()
                            val trigger = if (newCmdTrigger.startsWith("!")) newCmdTrigger else "!$newCmdTrigger"
                            updated.add(trigger to newCmdResponse)
                            commandsList = updated
                            bot.saveCommands(updated)
                            newCmdTrigger = ""
                            newCmdResponse = ""
                            showAddCmdDialog = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCmdDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = CardDark
        )
    }

    // Add Timer Dialog
    if (showAddTimerDialog) {
        AlertDialog(
            onDismissRequest = { showAddTimerDialog = false },
            title = { Text("Add Scheduled Chat Timer", color = TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Activity-Gated: Pops after 1st viewer chat, then waits for a random 1-5 chats & time interval so it never spams empty chat.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = newTimerMinutes,
                        onValueChange = { newTimerMinutes = it.filter { char -> char.isDigit() } },
                        label = { Text("Interval (Minutes)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newTimerMessage,
                        onValueChange = { newTimerMessage = it },
                        label = { Text("Scheduled Message Text") },
                        placeholder = { Text("e.g. Enjoying the stream? Make sure to follow!") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val minutes = newTimerMinutes.toLongOrNull() ?: 10L
                        if (newTimerMessage.isNotBlank()) {
                            val updated = timersList.toMutableList()
                            updated.add(
                                com.obs.chat.relay.bot.OllamaBotEngine.BotTimer(
                                    id = java.util.UUID.randomUUID().toString(),
                                    enabled = true,
                                    intervalMinutes = minutes.coerceAtLeast(1L),
                                    message = newTimerMessage.trim()
                                )
                            )
                            timersList = updated
                            bot.saveTimers(updated)
                            newTimerMessage = ""
                            newTimerMinutes = "10"
                            showAddTimerDialog = false
                        }
                    }
                ) {
                    Text("Add Timer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTimerDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = CardDark
        )
    }
}
