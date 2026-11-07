package com.obs.chat.relay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obs.chat.relay.OBSChatRelayApp
import com.obs.chat.relay.model.ChatMessage
import com.obs.chat.relay.service.FloatingDockService
import com.obs.chat.relay.service.RelayForegroundService
import com.obs.chat.relay.session.AccountType
import com.obs.chat.relay.ui.components.AccountLoginDialog
import com.obs.chat.relay.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { OBSChatRelayApp.instance.controller }

    val isRunning by controller.isServerRunning.collectAsState()
    val localIp by controller.localIp.collectAsState()
    val clientCount by controller.connectedClientsCount.collectAsState()
    val messageCount by controller.messageCount.collectAsState()
    val alertCount by controller.alertCount.collectAsState()
    val recentChats by controller.recentChats.collectAsState()
    val streamState by controller.streamState.collectAsState()
    val kickState by controller.kickState.collectAsState()

    val ytBroadcasterLoggedIn by controller.sessionManager.ytBroadcasterLoggedIn.collectAsState()
    val ytBroadcasterName by controller.sessionManager.ytBroadcasterName.collectAsState()
    val ytBroadcasterEmail by controller.sessionManager.ytBroadcasterEmail.collectAsState()
    val kickBroadcasterLoggedIn by controller.sessionManager.kickBroadcasterLoggedIn.collectAsState()
    val kickBroadcasterName by controller.sessionManager.kickBroadcasterName.collectAsState()
    val kickBroadcasterEmail by controller.sessionManager.kickBroadcasterEmail.collectAsState()

    var showLoginDialogFor by remember { mutableStateOf<AccountType?>(null) }
    var isChannelSwitcherMode by remember { mutableStateOf(false) }
    var isAccountSwitcherMode by remember { mutableStateOf(false) }

    var ytInput by remember { mutableStateOf(controller.prefs.youtubeVideoId) }
    var kickInput by remember { mutableStateOf(controller.prefs.kickUsername) }
    var ytEnabled by remember { mutableStateOf(controller.prefs.youtubeEnabled) }
    var kickEnabled by remember { mutableStateOf(controller.prefs.kickEnabled) }

    // Keep inputs synchronized if updated from background/account sync
    LaunchedEffect(controller.prefs.youtubeVideoId) {
        if (controller.prefs.youtubeVideoId.isNotBlank()) {
            ytInput = controller.prefs.youtubeVideoId
        }
    }
    LaunchedEffect(controller.prefs.kickUsername) {
        if (controller.prefs.kickUsername.isNotBlank()) {
            kickInput = controller.prefs.kickUsername
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    fun syncFromPc() {
        val target = controller.prefs.remoteTargetHost.trim()
        if (target.isBlank()) {
            Toast.makeText(context, "Connect to PC first via 'Scan OBS QR' above", Toast.LENGTH_LONG).show()
            return
        }
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

                    val kickUser = if (json.has("kickUsername") && !json.get("kickUsername").isJsonNull) json.get("kickUsername").asString else ""
                    val kickEmail = if (json.has("kickBroadcasterEmail") && !json.get("kickBroadcasterEmail").isJsonNull) json.get("kickBroadcasterEmail").asString else ""
                    if (kickUser.isNotBlank()) {
                        controller.sessionManager.saveLogin(AccountType.KICK_BROADCASTER, kickUser, kickEmail)
                        kickInput = kickUser
                        controller.updateKickCapture(kickUser)
                    }

                    val kickBot = if (json.has("kickBotUsername") && !json.get("kickBotUsername").isJsonNull) json.get("kickBotUsername").asString else ""
                    val kickBotEmail = if (json.has("kickBotEmail") && !json.get("kickBotEmail").isJsonNull) json.get("kickBotEmail").asString else ""
                    if (kickBot.isNotBlank()) {
                        controller.sessionManager.saveLogin(AccountType.KICK_BOT, kickBot, kickBotEmail)
                    }

                    val ytChannel = if (json.has("youtubeChannel") && !json.get("youtubeChannel").isJsonNull) json.get("youtubeChannel").asString else ""
                    val ytEmail = if (json.has("youtubeBroadcasterEmail") && !json.get("youtubeBroadcasterEmail").isJsonNull) json.get("youtubeBroadcasterEmail").asString else ""
                    if (ytChannel.isNotBlank()) {
                        controller.sessionManager.saveLogin(AccountType.YT_BROADCASTER, ytChannel, ytEmail)
                    }

                    val ytBot = if (json.has("youtubeBotChannel") && !json.get("youtubeBotChannel").isJsonNull) json.get("youtubeBotChannel").asString else ""
                    val ytBotEmail = if (json.has("youtubeBotEmail") && !json.get("youtubeBotEmail").isJsonNull) json.get("youtubeBotEmail").asString else ""
                    if (ytBot.isNotBlank()) {
                        controller.sessionManager.saveLogin(AccountType.YT_BOT, ytBot, ytBotEmail)
                    }

                    val ytVideoId = if (json.has("youtubeVideoId") && !json.get("youtubeVideoId").isJsonNull) json.get("youtubeVideoId").asString else ""
                    if (ytVideoId.isNotBlank()) {
                        ytInput = ytVideoId
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Status & Header Banner
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) KickGreen else AccentRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "RELAY ACTIVE" else "RELAY STOPPED",
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) KickGreen else AccentRed,
                                fontSize = 14.sp
                            )
                        }

                        // Connected Docks pill
                        Surface(
                            color = BgDark,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$clientCount OBS Dock(s)",
                                    fontSize = 12.sp,
                                    color = TextMain
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // IP Address
                    val isWifi = com.obs.chat.relay.util.NetworkUtils.isWifi(context)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                if (isWifi) "Local Wi-Fi IP Address" else "Mobile Data IP (Cellular)",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                            Text(localIp, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        }
                        IconButton(onClick = { controller.refreshIp() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh IP", tint = AccentBlue)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Start/Stop Primary Button & QR Scanner
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isRunning) {
                                    RelayForegroundService.stop(context)
                                } else {
                                    RelayForegroundService.start(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) AccentRed else KickGreen,
                                contentColor = if (isRunning) Color.White else Color.Black
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRunning) "Stop Relay" else "Start Relay",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                com.obs.chat.relay.util.QrCodeScannerHelper.scanQrCode(
                                    context = context,
                                    onSuccess = { targetUrl, _ ->
                                        controller.prefs.remoteTargetHost = targetUrl
                                        controller.prefs.remoteForwardingEnabled = true
                                        controller.remoteTransmitter.start()
                                        Toast.makeText(context, "🎉 Connected to OBS: $targetUrl", Toast.LENGTH_LONG).show()
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, "Scan error: $err", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan OBS QR", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. OBS Custom Browser Dock URLs Card
        item {
            val isWifi = com.obs.chat.relay.util.NetworkUtils.isWifi(context)
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (!isWifi) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CellTower, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Mobile Data Remote Streaming", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Your phone streams chats & alerts directly into your PC's OBS overlay. In OBS Custom Browser Docks, keep your dock set to your local PC dock:",
                            fontSize = 11.sp,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        DockUrlRow(
                            title = "💬 OBS Dock URL (Local PC)",
                            url = "http://127.0.0.1:3000/chatdock.html",
                            onCopy = { copyToClipboard("OBS Dock URL", "http://127.0.0.1:3000/chatdock.html") }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "⚠️ Note: Do NOT put your phone's cellular IP ($localIp) into OBS because cellular carriers block direct incoming connections. Simply tap 'Scan OBS QR' above to stream directly to OBS!",
                            fontSize = 11.sp,
                            color = Color(0xFFFFB800)
                        )
                    } else {
                        Text(
                            "OBS Browser Dock URLs (Wi-Fi Mode)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain
                        )
                        Text(
                            "Paste into OBS -> Docks -> Custom Browser Docks (Same Wi-Fi)",
                            fontSize = 11.sp,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Chat Dock URL
                        DockUrlRow(
                            title = "💬 Chat Dock",
                            url = controller.getChatDockUrl(),
                            onCopy = { copyToClipboard("Chat Dock URL", controller.getChatDockUrl()) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Alert Dock URL
                        DockUrlRow(
                            title = "🔔 Alert Dock",
                            url = controller.getAlertDockUrl(),
                            onCopy = { copyToClipboard("Alert Dock URL", controller.getAlertDockUrl()) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Event Dock URL
                        DockUrlRow(
                            title = "📋 Event Log",
                            url = controller.getEventDockUrl(),
                            onCopy = { copyToClipboard("Event Dock URL", controller.getEventDockUrl()) }
                        )
                    }
                }
            }
        }

        // 3. YouTube Authentication Card
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
                        Text("🔑", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("YouTube Authentication", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Persistently authenticate with Google/YouTube. No API quotas or keys needed.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))

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
                                Column {
                                    Text("Session Status", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = if (ytBroadcasterLoggedIn) {
                                            if (ytBroadcasterName.isNotBlank()) "Authenticated ($ytBroadcasterName)" else "Authenticated (YouTube Studio)"
                                        } else "Not Authenticated",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (ytBroadcasterLoggedIn) KickGreen else AccentRed
                                    )
                                    if (ytBroadcasterLoggedIn && ytBroadcasterEmail.isNotBlank()) {
                                        Text(
                                            text = "Email: $ytBroadcasterEmail",
                                            fontSize = 11.sp,
                                            color = AccentBlue
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (!ytBroadcasterLoggedIn) {
                                    Button(
                                        onClick = {
                                            isChannelSwitcherMode = false
                                            isAccountSwitcherMode = false
                                            showLoginDialogFor = AccountType.YT_BROADCASTER
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Connect Account", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            isChannelSwitcherMode = true
                                            isAccountSwitcherMode = false
                                            showLoginDialogFor = AccountType.YT_BROADCASTER
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CardDark, contentColor = AccentBlue),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.6f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1.1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text("🔄 Switch Channel", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            isChannelSwitcherMode = false
                                            isAccountSwitcherMode = true
                                            showLoginDialogFor = AccountType.YT_BROADCASTER
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text("👤 Switch", fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            controller.sessionManager.logout(AccountType.YT_BROADCASTER)
                                            controller.setYouTubeEnabled(false)
                                            ytEnabled = false
                                            Toast.makeText(context, "YouTube account disconnected", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(0.9f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text("Disconnect", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. YouTube Studio Scanner Card
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text("🛰️", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("YouTube Studio Scanner", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                Text("Silently monitor Studio to auto-detect livestreams", fontSize = 11.sp, color = TextMuted)
                            }
                        }
                        Switch(
                            checked = ytEnabled,
                            onCheckedChange = {
                                if (it && !ytBroadcasterLoggedIn && streamState.videoId.isNullOrBlank()) {
                                    Toast.makeText(context, "Connect YouTube account or specify a Video ID below to monitor", Toast.LENGTH_SHORT).show()
                                }
                                ytEnabled = it
                                controller.setYouTubeEnabled(it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Metadata Grid Row 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val statusColor = when (streamState.liveState.lowercase()) {
                            "live" -> KickGreen
                            "upcoming", "monitoring" -> AccentOrange
                            else -> TextMuted
                        }
                        MetadataItem(
                            title = "Stream Status",
                            value = streamState.liveState.uppercase(),
                            valueColor = statusColor,
                            modifier = Modifier.weight(1f)
                        )
                        MetadataItem(
                            title = "YouTube Viewers",
                            value = "${streamState.viewers}",
                            valueColor = AccentRed,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Metadata Grid Row 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetadataItem(
                            title = "Kick Viewers",
                            value = "${kickState.viewers}",
                            valueColor = KickGreen,
                            modifier = Modifier.weight(1f)
                        )
                        MetadataItem(
                            title = "Video ID",
                            value = streamState.videoId ?: "None",
                            valueColor = AccentBlue,
                            isMonospace = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Metadata Grid Row 3: Stream Title (Full span)
                    MetadataItem(
                        title = "Stream Title",
                        value = streamState.title ?: if (streamState.liveState.equals("live", true)) "Active Studio Livestream" else "No stream active",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Collapsible Manual Video ID Override
                    var showManualYtInput by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showManualYtInput = !showManualYtInput },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                if (showManualYtInput) Icons.Default.ExpandLess else Icons.Default.Tune,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (showManualYtInput) "Hide Manual Video ID" else "Manual Video ID / URL Override",
                                fontSize = 11.sp,
                                color = AccentBlue
                            )
                        }
                    }

                    if (showManualYtInput) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ytInput,
                                onValueChange = { ytInput = it },
                                placeholder = { Text("e.g. dQw4w9WgXcQ or live URL", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentRed,
                                    unfocusedBorderColor = BorderDark
                                )
                            )
                            Button(
                                onClick = {
                                    controller.updateYouTubeCapture(ytInput)
                                    Toast.makeText(context, "Stream target applied!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CardDark, contentColor = AccentBlue),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Apply", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // 5. Kick Authentication & Scanner Card
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
                        Text("🟢", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Kick Authentication & Scanner", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Persistently authenticate with Kick as Broadcaster. No API keys needed.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Broadcaster Status
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
                                Column {
                                    Text("Broadcaster Status", fontSize = 11.sp, color = TextMuted)
                                    Text(
                                        text = if (kickBroadcasterLoggedIn) {
                                            if (kickBroadcasterName.isNotBlank()) "Authenticated ($kickBroadcasterName)" else "Authenticated (Broadcaster)"
                                        } else "Not Authenticated",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (kickBroadcasterLoggedIn) KickGreen else AccentRed
                                    )
                                    if (kickBroadcasterLoggedIn && kickBroadcasterEmail.isNotBlank()) {
                                        Text(
                                            text = "Email: $kickBroadcasterEmail",
                                            fontSize = 11.sp,
                                            color = AccentBlue
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!kickBroadcasterLoggedIn) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                isChannelSwitcherMode = false
                                                isAccountSwitcherMode = false
                                                showLoginDialogFor = AccountType.KICK_BROADCASTER
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = KickGreen, contentColor = Color.Black),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1.2f)
                                        ) {
                                            Text("Connect Account", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }

                                        OutlinedButton(
                                            onClick = { syncFromPc() },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple.copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("⚡ Sync PC", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            isChannelSwitcherMode = false
                                            isAccountSwitcherMode = true
                                            showLoginDialogFor = AccountType.KICK_BROADCASTER
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CardDark, contentColor = KickGreen),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, KickGreen.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("👤 Switch", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            controller.sessionManager.logout(AccountType.KICK_BROADCASTER)
                                            controller.setKickEnabled(false)
                                            kickEnabled = false
                                            Toast.makeText(context, "Kick broadcaster disconnected", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Disconnect", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Kick Channel Username
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Kick Channel Username", fontSize = 12.sp, color = TextMuted)
                        if (kickBroadcasterLoggedIn) {
                            Surface(
                                color = KickGreen.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, KickGreen.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    "🔒 Account-Linked",
                                    color = KickGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = kickInput,
                        onValueChange = {
                            kickInput = it
                            controller.updateKickCapture(it)
                        },
                        placeholder = { Text("e.g. steushio", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KickGreen,
                            unfocusedBorderColor = BorderDark
                        )
                    )

                    if (!kickBroadcasterLoggedIn && kickInput.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    val clean = kickInput.trim().removePrefix("@")
                                    controller.sessionManager.saveLogin(AccountType.KICK_BROADCASTER, clean)
                                    Toast.makeText(context, "Saved Kick Broadcaster: $clean", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("⚡ Link '$kickInput' as Broadcaster", fontSize = 11.sp, color = KickGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Background Kick Monitoring Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Background Kick Monitoring", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                            Text("Enable real-time Kick chat & alert capture", fontSize = 11.sp, color = TextMuted)
                        }
                        Switch(
                            checked = kickEnabled,
                            onCheckedChange = {
                                kickEnabled = it
                                controller.setKickEnabled(it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Kick Metadata Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val kickRelayStatus = when {
                            kickState.isLive -> "Live"
                            kickEnabled && kickState.chatroomId != null -> "Connected"
                            kickEnabled -> "Connecting..."
                            else -> "Idle"
                        }
                        val kickRelayColor = if (kickState.isLive || kickState.chatroomId != null) KickGreen else TextMuted
                        MetadataItem(
                            title = "Kick Relay Status",
                            value = kickRelayStatus,
                            valueColor = kickRelayColor,
                            modifier = Modifier.weight(1f)
                        )
                        MetadataItem(
                            title = "Kick Viewers",
                            value = "${kickState.viewers}",
                            valueColor = KickGreen,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 4. Action Shortcuts (Clear Chat, Floating HUD, Eco Mode)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Clear Chat button
                OutlinedButton(
                    onClick = { controller.clearChat() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Docks", fontSize = 12.sp)
                }

                // Floating Overlay HUD button
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            FloatingDockService.start(context)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardDark, contentColor = AccentBlue)
                ) {
                    Icon(Icons.Default.PictureInPicture, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Floating HUD", fontSize = 12.sp)
                }
            }
        }

        // 5. Live Feed Preview Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Live Chat Feed Preview",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
                Text(
                    "$messageCount msgs • $alertCount alerts",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        if (recentChats.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Waiting for chat messages or alerts...",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            items(recentChats.takeLast(15).reversed()) { msg ->
                ChatMessageItem(msg)
            }
        }
    }

    if (showLoginDialogFor != null) {
        AccountLoginDialog(
            accountType = showLoginDialogFor!!,
            isChannelSwitcher = isChannelSwitcherMode,
            isAccountSwitcher = isAccountSwitcherMode,
            onDismiss = {
                showLoginDialogFor = null
                isChannelSwitcherMode = false
                isAccountSwitcherMode = false
            },
            onLoginSuccess = { username, email, token ->
                val type = showLoginDialogFor!!
                showLoginDialogFor = null
                isChannelSwitcherMode = false
                isAccountSwitcherMode = false
                controller.sessionManager.saveLogin(type, username, email, token)

                if (type == AccountType.YT_BROADCASTER) {
                    Toast.makeText(context, "🎉 YouTube channel: $username", Toast.LENGTH_SHORT).show()
                    ytEnabled = true
                    controller.setYouTubeEnabled(true)
                } else if (type == AccountType.KICK_BROADCASTER) {
                    kickInput = username
                    controller.updateKickCapture(username)
                    kickEnabled = true
                    controller.setKickEnabled(true)
                    Toast.makeText(context, "🎉 Kick broadcaster: $username", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
fun DockUrlRow(title: String, url: String, onCopy: () -> Unit) {
    Surface(
        color = BgDark,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                Text(url, fontSize = 11.sp, color = AccentBlue, maxLines = 1)
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun ChatMessageItem(msg: ChatMessage) {
    val isAlert = msg.type != "chat"
    val alertBorderColor = when (msg.type) {
        "superchat" -> AccentRed
        "sticker" -> Color(0xFFF97316)
        "membership" -> Color(0xFFEAB308)
        "membership_gift", "membership_redeemed" -> Color(0xFFA855F7)
        else -> BorderDark
    }

    val alertTitle = when (msg.type) {
        "superchat" -> "💰 SUPER CHAT"
        "sticker" -> "🎨 SUPER STICKER"
        "membership" -> "⭐ NEW MEMBER"
        "membership_gift" -> "🎁 GIFT SUB"
        "membership_redeemed" -> "🎉 SUB CLAIMED"
        else -> null
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isAlert) Color(0xFF1E1E24) else CardDark
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(
                if (isAlert) 1.5.dp else 1.dp,
                alertBorderColor,
                RoundedCornerShape(8.dp)
            )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Alert Header if alert
            if (alertTitle != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = alertTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = alertBorderColor
                    )
                    if (!msg.amount.isNullOrEmpty()) {
                        Surface(
                            color = alertBorderColor,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = msg.amount,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(verticalAlignment = Alignment.Top) {
                // Platform badge
                Surface(
                    color = if (msg.platform == "kick") KickGreen else AccentRed,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = msg.platform.uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = msg.username,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (isAlert) alertBorderColor else AccentBlue
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = msg.message ?: "",
                        fontSize = 12.sp,
                        color = TextMain
                    )
                }
            }
        }
    }
}

@Composable
fun MetadataItem(
    title: String,
    value: String,
    valueColor: Color = TextMain,
    isMonospace: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = BgDark,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title.uppercase(),
                fontSize = 10.sp,
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                fontFamily = if (isMonospace) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                maxLines = 2
            )
        }
    }
}
