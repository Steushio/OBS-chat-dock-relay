package com.obs.chat.relay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.obs.chat.relay.session.AccountType
import com.obs.chat.relay.ui.components.AccountLoginDialog
import com.obs.chat.relay.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { OBSChatRelayApp.instance.controller }
    val prefs = controller.prefs
    val sessionManager = controller.sessionManager
    val backupManager = controller.backupManager

    var httpPort by remember { mutableStateOf(prefs.httpPort.toString()) }
    var wsPort by remember { mutableStateOf(prefs.wsPort.toString()) }
    var chatSound by remember { mutableStateOf(prefs.chatSoundEnabled) }
    var alertSound by remember { mutableStateOf(prefs.alertSoundEnabled) }
    var keepScreenAwake by remember { mutableStateOf(prefs.keepScreenAwake) }
    var autoStart by remember { mutableStateOf(prefs.autoStartMonitoring) }
    var bgMode by remember { mutableStateOf(prefs.backgroundModeEnabled) }
    var lowResource by remember { mutableStateOf(prefs.lowResourceMode) }
    var clearOldChat by remember { mutableStateOf(prefs.clearOldChat) }

    // Account States
    val ytBroadcasterLoggedIn by sessionManager.ytBroadcasterLoggedIn.collectAsState()
    val ytBroadcasterName by sessionManager.ytBroadcasterName.collectAsState()
    val ytBroadcasterEmail by sessionManager.ytBroadcasterEmail.collectAsState()
    val ytBotLoggedIn by sessionManager.ytBotLoggedIn.collectAsState()
    val ytBotName by sessionManager.ytBotName.collectAsState()
    val ytBotEmail by sessionManager.ytBotEmail.collectAsState()

    val kickBroadcasterLoggedIn by sessionManager.kickBroadcasterLoggedIn.collectAsState()
    val kickBroadcasterName by sessionManager.kickBroadcasterName.collectAsState()
    val kickBroadcasterEmail by sessionManager.kickBroadcasterEmail.collectAsState()
    val kickBotLoggedIn by sessionManager.kickBotLoggedIn.collectAsState()
    val kickBotName by sessionManager.kickBotName.collectAsState()
    val kickBotEmail by sessionManager.kickBotEmail.collectAsState()

    val ytPostingMode by sessionManager.ytPostingModeFlow.collectAsState()
    val kickPostingMode by sessionManager.kickPostingModeFlow.collectAsState()

    var activeLoginType by remember { mutableStateOf<AccountType?>(null) }
    var isChannelSwitcherMode by remember { mutableStateOf(false) }
    var isAccountSwitcherMode by remember { mutableStateOf(false) }
    var showQuickJsonDialog by remember { mutableStateOf(false) }
    var quickJsonText by remember { mutableStateOf("") }

    // File picker launcher for importing settings .json
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val ok = backupManager.importFromUri(context, uri)
            if (ok) {
                // Refresh local states
                httpPort = prefs.httpPort.toString()
                wsPort = prefs.wsPort.toString()
                chatSound = prefs.chatSoundEnabled
                alertSound = prefs.alertSoundEnabled
                keepScreenAwake = prefs.keepScreenAwake
                autoStart = prefs.autoStartMonitoring
                lowResource = prefs.lowResourceMode
                clearOldChat = prefs.clearOldChat
                controller.ollamaBot.startTimers()
                Toast.makeText(context, "Settings successfully imported!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to import settings file. Invalid format.", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "App Settings & Accounts",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain
            )
        }

        // 1. Settings Backup & Restore Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("💾 Settings Backup & Restore", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Text("Export your entire configuration, AI prompts, and commands to a .json file or transfer it between devices.", fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { backupManager.shareBackupFile(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export (.json)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { importFileLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = KickGreen, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import (.json)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                val json = backupManager.exportToJsonString()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("OBS Settings JSON", json))
                                Toast.makeText(context, "Full Settings JSON copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextMuted)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy JSON", fontSize = 11.sp, color = TextMuted)
                        }

                        TextButton(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
                                    if (text.isNotBlank()) quickJsonText = text
                                } catch (e: Exception) {}
                                showQuickJsonDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp), tint = KickGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paste JSON", fontSize = 11.sp, color = KickGreen)
                        }
                    }
                }
            }
        }

        // 2. AI Bot Account Mode & Switching Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🤖", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("AI Bot Account Switching & Posting Identity", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
                            Text("Select whether your AI bot speaks as You (Broadcaster) or a Dedicated Bot account.", fontSize = 11.sp, color = TextMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // YouTube Bot Account Switcher
                    Surface(
                        color = BgDark,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔴 YouTube Bot Identity", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AccentRed)
                                Surface(
                                    color = if (ytPostingMode == "bot_account") AccentPurple.copy(alpha = 0.2f) else AccentRed.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (ytPostingMode == "bot_account") "Posting as: Dedicated Bot" else "Posting as: Broadcaster",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (ytPostingMode == "bot_account") AccentPurple else AccentRed,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { sessionManager.updateYtPostingMode("broadcaster") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (ytPostingMode == "broadcaster") AccentRed.copy(alpha = 0.2f) else Color.Transparent,
                                        contentColor = if (ytPostingMode == "broadcaster") AccentRed else TextMuted
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (ytPostingMode == "broadcaster") AccentRed else BorderDark),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("Broadcaster (You)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = { sessionManager.updateYtPostingMode("bot_account") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (ytPostingMode == "bot_account") AccentPurple.copy(alpha = 0.2f) else Color.Transparent,
                                        contentColor = if (ytPostingMode == "bot_account") AccentPurple else TextMuted
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (ytPostingMode == "bot_account") AccentPurple else BorderDark),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("Dedicated Bot Account", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Kick Bot Account Switcher
                    Surface(
                        color = BgDark,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🟢 Kick Bot Identity", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = KickGreen)
                                Surface(
                                    color = if (kickPostingMode == "bot_account") AccentPurple.copy(alpha = 0.2f) else KickGreen.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (kickPostingMode == "bot_account") "Posting as: Dedicated Bot" else "Posting as: Broadcaster",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (kickPostingMode == "bot_account") AccentPurple else KickGreen,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { sessionManager.updateKickPostingMode("broadcaster") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (kickPostingMode == "broadcaster") KickGreen.copy(alpha = 0.2f) else Color.Transparent,
                                        contentColor = if (kickPostingMode == "broadcaster") KickGreen else TextMuted
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (kickPostingMode == "broadcaster") KickGreen else BorderDark),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("Broadcaster (You)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = { sessionManager.updateKickPostingMode("bot_account") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (kickPostingMode == "bot_account") AccentPurple.copy(alpha = 0.2f) else Color.Transparent,
                                        contentColor = if (kickPostingMode == "bot_account") AccentPurple else TextMuted
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (kickPostingMode == "bot_account") AccentPurple else BorderDark),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("Dedicated Bot Account", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Streamer & Bot Accounts Card with Email & Channel Selection
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("👤 Connected Accounts & Channel Selector", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Text("Manage account emails and channels for both Streamer and Dedicated Bot accounts.", fontSize = 11.sp, color = TextMuted)
                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. YouTube Broadcaster (Streamer)
                    AccountDetailCard(
                        title = "YouTube Streamer Account",
                        roleTag = "Streamer",
                        isLoggedIn = ytBroadcasterLoggedIn,
                        channelName = ytBroadcasterName,
                        email = ytBroadcasterEmail,
                        color = AccentRed,
                        supportsChannelSwitch = true,
                        onConnect = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = false
                            activeLoginType = AccountType.YT_BROADCASTER
                        },
                        onSwitchChannel = {
                            isChannelSwitcherMode = true
                            isAccountSwitcherMode = false
                            activeLoginType = AccountType.YT_BROADCASTER
                        },
                        onSwitchAccount = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = true
                            activeLoginType = AccountType.YT_BROADCASTER
                        },
                        onDisconnect = { sessionManager.logout(AccountType.YT_BROADCASTER) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. YouTube Dedicated Bot
                    AccountDetailCard(
                        title = "YouTube Dedicated Bot Account",
                        roleTag = "Dedicated Bot",
                        isLoggedIn = ytBotLoggedIn,
                        channelName = ytBotName,
                        email = ytBotEmail,
                        color = AccentPurple,
                        supportsChannelSwitch = true,
                        onConnect = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = false
                            activeLoginType = AccountType.YT_BOT
                        },
                        onSwitchChannel = {
                            isChannelSwitcherMode = true
                            isAccountSwitcherMode = false
                            activeLoginType = AccountType.YT_BOT
                        },
                        onSwitchAccount = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = true
                            activeLoginType = AccountType.YT_BOT
                        },
                        onDisconnect = { sessionManager.logout(AccountType.YT_BOT) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. Kick Broadcaster (Streamer)
                    AccountDetailCard(
                        title = "Kick Streamer Account",
                        roleTag = "Streamer",
                        isLoggedIn = kickBroadcasterLoggedIn,
                        channelName = kickBroadcasterName,
                        email = kickBroadcasterEmail,
                        color = KickGreen,
                        supportsChannelSwitch = false,
                        onConnect = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = false
                            activeLoginType = AccountType.KICK_BROADCASTER
                        },
                        onSwitchAccount = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = true
                            activeLoginType = AccountType.KICK_BROADCASTER
                        },
                        onDisconnect = { sessionManager.logout(AccountType.KICK_BROADCASTER) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 4. Kick Dedicated Bot
                    AccountDetailCard(
                        title = "Kick Dedicated Bot Account",
                        roleTag = "Dedicated Bot",
                        isLoggedIn = kickBotLoggedIn,
                        channelName = kickBotName,
                        email = kickBotEmail,
                        color = KickGreen,
                        supportsChannelSwitch = false,
                        onConnect = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = false
                            activeLoginType = AccountType.KICK_BOT
                        },
                        onSwitchAccount = {
                            isChannelSwitcherMode = false
                            isAccountSwitcherMode = true
                            activeLoginType = AccountType.KICK_BOT
                        },
                        onDisconnect = { sessionManager.logout(AccountType.KICK_BOT) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val target = prefs.remoteTargetHost.trim()
                            if (target.isBlank()) {
                                Toast.makeText(context, "Connect to PC first via 'Scan OBS QR' on Dashboard", Toast.LENGTH_LONG).show()
                            } else {
                                scope.launch {
                                    try {
                                        var httpTarget = target
                                        if (!httpTarget.startsWith("http://") && !httpTarget.startsWith("https://")) {
                                            httpTarget = "http://" + httpTarget.removePrefix("ws://").removePrefix("wss://")
                                        }
                                        val url = httpTarget.trimEnd('/') + "/api/sync-accounts"
                                        val client = okhttp3.OkHttpClient()
                                        val req = okhttp3.Request.Builder().url(url).build()
                                        val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.newCall(req).execute() }
                                        if (resp.isSuccessful) {
                                            val body = resp.body?.string() ?: ""
                                            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                                            
                                            // Kick Broadcaster
                                            val kickUser = if (json.has("kickUsername") && !json.get("kickUsername").isJsonNull) json.get("kickUsername").asString else ""
                                            val kickEmail = if (json.has("kickBroadcasterEmail") && !json.get("kickBroadcasterEmail").isJsonNull) json.get("kickBroadcasterEmail").asString else ""
                                            if (kickUser.isNotBlank()) {
                                                sessionManager.saveLogin(AccountType.KICK_BROADCASTER, kickUser, kickEmail)
                                            }

                                            // Kick Bot
                                            val kickBot = if (json.has("kickBotUsername") && !json.get("kickBotUsername").isJsonNull) json.get("kickBotUsername").asString else ""
                                            val kickBotEmail = if (json.has("kickBotEmail") && !json.get("kickBotEmail").isJsonNull) json.get("kickBotEmail").asString else ""
                                            if (kickBot.isNotBlank()) {
                                                sessionManager.saveLogin(AccountType.KICK_BOT, kickBot, kickBotEmail)
                                            }

                                            // YouTube Broadcaster
                                            val ytChannel = if (json.has("youtubeChannel") && !json.get("youtubeChannel").isJsonNull) json.get("youtubeChannel").asString else ""
                                            val ytEmail = if (json.has("youtubeBroadcasterEmail") && !json.get("youtubeBroadcasterEmail").isJsonNull) json.get("youtubeBroadcasterEmail").asString else ""
                                            if (ytChannel.isNotBlank()) {
                                                sessionManager.saveLogin(AccountType.YT_BROADCASTER, ytChannel, ytEmail)
                                            }

                                            // YouTube Bot
                                            val ytBot = if (json.has("youtubeBotChannel") && !json.get("youtubeBotChannel").isJsonNull) json.get("youtubeBotChannel").asString else ""
                                            val ytBotEmail = if (json.has("youtubeBotEmail") && !json.get("youtubeBotEmail").isJsonNull) json.get("youtubeBotEmail").asString else ""
                                            if (ytBot.isNotBlank()) {
                                                sessionManager.saveLogin(AccountType.YT_BOT, ytBot, ytBotEmail)
                                            }

                                            // YouTube Video ID
                                            val ytVideoId = if (json.has("youtubeVideoId") && !json.get("youtubeVideoId").isJsonNull) json.get("youtubeVideoId").asString else ""
                                            if (ytVideoId.isNotBlank()) {
                                                controller.updateYouTubeCapture(ytVideoId)
                                            }

                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(context, "🎉 Synced Streamer & Bot accounts from PC!", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(context, "PC sync response: HTTP ${resp.code}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(context, "Sync error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Accounts from PC (1-Tap)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Network Ports Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Network & Ports", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = httpPort,
                        onValueChange = {
                            httpPort = it
                            it.toIntOrNull()?.let { p -> prefs.httpPort = p }
                        },
                        label = { Text("HTTP Overlay Port (Default: 3000)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = BorderDark
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = wsPort,
                        onValueChange = {
                            wsPort = it
                            it.toIntOrNull()?.let { p -> prefs.wsPort = p }
                        },
                        label = { Text("WebSocket Port (Default: 3001)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = BorderDark
                        )
                    )
                }
            }
        }

        // 4. Audio & Alerts Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio & Alerts", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingToggleRow(
                        title = "Chat Sound Tone",
                        subtitle = "Play a soft click on each incoming chat message",
                        checked = chatSound,
                        onCheckedChange = {
                            chatSound = it
                            prefs.chatSoundEnabled = it
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingToggleRow(
                        title = "Super Chat & Alert Tone",
                        subtitle = "Play a fanfare sound on Super Chats, memberships, and gifts",
                        checked = alertSound,
                        onCheckedChange = {
                            alertSound = it
                            prefs.alertSoundEnabled = it
                        }
                    )
                }
            }
        }

        // 5. Performance & Behavior Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Behavior & Performance", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingToggleRow(
                        title = "Keep Screen Awake",
                        subtitle = "Prevents device display from sleeping while app is open",
                        checked = keepScreenAwake,
                        onCheckedChange = {
                            keepScreenAwake = it
                            prefs.keepScreenAwake = it
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingToggleRow(
                        title = "Auto-Start Monitoring",
                        subtitle = "Automatically starts relay service on app launch",
                        checked = autoStart,
                        onCheckedChange = {
                            autoStart = it
                            prefs.autoStartMonitoring = it
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingToggleRow(
                        title = "Low Resource Mode",
                        subtitle = "Disables image rendering in background scraper to save RAM and battery",
                        checked = lowResource,
                        onCheckedChange = {
                            lowResource = it
                            controller.toggleLowResourceMode()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingToggleRow(
                        title = "Auto-Clear Old Chat",
                        subtitle = "Prune chat messages in OBS docks after time limit",
                        checked = clearOldChat,
                        onCheckedChange = {
                            clearOldChat = it
                            controller.toggleClearChat()
                        }
                    )
                }
            }
        }
    }

    // In-App Account Login WebView Dialog
    if (activeLoginType != null) {
        AccountLoginDialog(
            accountType = activeLoginType!!,
            isChannelSwitcher = isChannelSwitcherMode,
            isAccountSwitcher = isAccountSwitcherMode,
            onDismiss = {
                activeLoginType = null
                isChannelSwitcherMode = false
                isAccountSwitcherMode = false
            },
            onLoginSuccess = { username, email, token ->
                val type = activeLoginType!!
                sessionManager.saveLogin(type, username, email, token)
                activeLoginType = null
                isChannelSwitcherMode = false
                isAccountSwitcherMode = false

                if (type == AccountType.YT_BROADCASTER) {
                    controller.setYouTubeEnabled(true)
                    Toast.makeText(context, "🎉 YouTube channel: $username", Toast.LENGTH_SHORT).show()
                } else if (type == AccountType.KICK_BROADCASTER) {
                    controller.updateKickCapture(username)
                    controller.setKickEnabled(true)
                    Toast.makeText(context, "🎉 Kick broadcaster: $username", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "🎉 Bot account connected: $username", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showQuickJsonDialog) {
        AlertDialog(
            onDismissRequest = { showQuickJsonDialog = false },
            containerColor = CardDark,
            title = { Text("Paste Settings JSON", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text("Paste your exported PC or Mobile JSON settings below to import:", color = TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = quickJsonText,
                        onValueChange = { quickJsonText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        placeholder = { Text("Paste JSON here...", color = TextMuted, fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = TextMain)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ok = backupManager.importFromJsonString(quickJsonText)
                        if (ok) {
                            httpPort = prefs.httpPort.toString()
                            wsPort = prefs.wsPort.toString()
                            chatSound = prefs.chatSoundEnabled
                            alertSound = prefs.alertSoundEnabled
                            keepScreenAwake = prefs.keepScreenAwake
                            autoStart = prefs.autoStartMonitoring
                            lowResource = prefs.lowResourceMode
                            clearOldChat = prefs.clearOldChat
                            controller.ollamaBot.startTimers()
                            showQuickJsonDialog = false
                            Toast.makeText(context, "Settings & timers successfully imported!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Invalid JSON format.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KickGreen, contentColor = Color.Black)
                ) {
                    Text("Import Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickJsonDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
fun AccountDetailCard(
    title: String,
    roleTag: String,
    isLoggedIn: Boolean,
    channelName: String,
    email: String,
    color: Color,
    supportsChannelSwitch: Boolean = false,
    onConnect: () -> Unit,
    onSwitchChannel: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onDisconnect: () -> Unit
) {
    Surface(
        color = BgDark,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextMain)
                Surface(
                    color = color.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = roleTag,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (isLoggedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Channel / User: ", fontSize = 11.sp, color = TextMuted)
                    Text(
                        text = channelName.ifBlank { "Channel Connected" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KickGreen
                    )
                }
                if (email.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Account Email: ", fontSize = 11.sp, color = TextMuted)
                        Text(
                            text = email,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentBlue
                        )
                    }
                }
            } else {
                Text("Not Connected • Sign in to enable chat bot posting", fontSize = 11.sp, color = AccentRed)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons
            if (!isLoggedIn) {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == KickGreen) Color.Black else Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text("Connect Account", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (supportsChannelSwitch) {
                        Button(
                            onClick = onSwitchChannel,
                            colors = ButtonDefaults.buttonColors(containerColor = CardDark, contentColor = AccentBlue),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1.1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Text("🔄 Switch Channel", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onSwitchAccount,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text("👤 Switch Account", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(0.9f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text("Disconnect", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextMain)
            Text(subtitle, fontSize = 11.sp, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
