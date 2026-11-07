package com.obs.chat.relay.controller

import android.content.Context
import com.obs.chat.relay.bot.OllamaBotEngine
import com.obs.chat.relay.capture.KickChatCapture
import com.obs.chat.relay.capture.YouTubeChatCapture
import com.obs.chat.relay.capture.YouTubeStudioMonitor
import com.obs.chat.relay.model.ChatMessage
import com.obs.chat.relay.model.KickState
import com.obs.chat.relay.model.LogEntry
import com.obs.chat.relay.model.StreamState
import com.obs.chat.relay.network.RemoteRelayTransmitter
import com.obs.chat.relay.server.OverlayHttpServer
import com.obs.chat.relay.server.RelayWebSocketServer
import com.obs.chat.relay.util.NetworkUtils
import com.obs.chat.relay.util.PreferencesManager
import com.obs.chat.relay.util.SoundPlayer
import com.google.gson.JsonParser
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class RelayController(private val context: Context) {

    val prefs = PreferencesManager(context)
    private val soundPlayer = SoundPlayer()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val httpClient = OkHttpClient()
    private var kickPollJob: Job? = null
    private var ytPollJob: Job? = null

    private var httpServer: OverlayHttpServer? = null
    private var wsServer: RelayWebSocketServer? = null
    private var ytCapture: YouTubeChatCapture? = null
    private var ytStudioMonitor: YouTubeStudioMonitor? = null
    private var kickCapture: KickChatCapture? = null

    // Remote Network Sharing Transmitter (Mobile Data -> PC IP)
    val remoteTransmitter = RemoteRelayTransmitter(
        prefs = prefs,
        onLog = { msg, type -> logSystem(msg, type) },
        onRemoteCommandReceived = { cmd, data ->
            handleRemoteCommand(cmd, data)
        }
    )

    // Dedicated YouTube Bot Chat Poster (Types directly into live stream chat via background WebView)
    val ytBotSender = com.obs.chat.relay.capture.YouTubeBotChatSender(
        context = context,
        prefs = prefs,
        onLog = { msg, type -> logSystem(msg, type) }
    )

    // Full Ollama AI Bot Engine
    val ollamaBot = OllamaBotEngine(
        prefs = prefs,
        onBotReply = { botReply ->
            handleIncomingChatEvent(botReply)
            if (botReply.type == "chat") {
                sessionManager.postBotReplyToChat(
                    platform = botReply.platform,
                    videoId = prefs.youtubeVideoId,
                    chatroomId = kickState.value.chatroomId,
                    replyText = botReply.message ?: "",
                    ytSender = ytBotSender
                )
            }
        },
        onLog = { msg, type -> logSystem(msg, type) }
    )

    // Account & Login Session Manager (YouTube / Kick Broadcaster & Bot accounts)
    val sessionManager = com.obs.chat.relay.session.AccountSessionManager(
        context = context,
        onLog = { msg, type -> logSystem(msg, type) }
    )

    // Settings Backup & Restore Manager (Export / Import .json)
    val backupManager = com.obs.chat.relay.util.SettingsBackupManager(prefs)

    // Reactive UI States
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _connectedClientsCount = MutableStateFlow(0)
    val connectedClientsCount: StateFlow<Int> = _connectedClientsCount.asStateFlow()

    private val _localIp = MutableStateFlow("127.0.0.1")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val recentLogs: StateFlow<List<LogEntry>> = _recentLogs.asStateFlow()

    private val _recentChats = MutableStateFlow<List<ChatMessage>>(emptyList())
    val recentChats: StateFlow<List<ChatMessage>> = _recentChats.asStateFlow()

    private val _recentAlerts = MutableStateFlow<List<ChatMessage>>(emptyList())
    val recentAlerts: StateFlow<List<ChatMessage>> = _recentAlerts.asStateFlow()

    private val _streamState = MutableStateFlow(StreamState())
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _kickState = MutableStateFlow(KickState())
    val kickState: StateFlow<KickState> = _kickState.asStateFlow()

    private val _messageCount = MutableStateFlow(0)
    val messageCount: StateFlow<Int> = _messageCount.asStateFlow()

    private val _alertCount = MutableStateFlow(0)
    val alertCount: StateFlow<Int> = _alertCount.asStateFlow()

    init {
        refreshIp()
        initStudioMonitor()
        if (prefs.remoteForwardingEnabled && prefs.remoteTargetHost.isNotBlank()) {
            remoteTransmitter.start()
        }
        startMonitoringPollers()
    }

    private fun initStudioMonitor() {
        if (ytStudioMonitor == null) {
            ytStudioMonitor = YouTubeStudioMonitor(
                context = context,
                onStreamData = { vid, title, liveState, viewers ->
                    onStudioStreamDetected(vid, title, liveState, viewers)
                },
                onNewSubscriber = { user, avatar ->
                    onStudioNewSubscriber(user, avatar)
                },
                onLog = { msg, type -> logSystem(msg, type) }
            )
        }
        if (prefs.youtubeEnabled) {
            ytStudioMonitor?.startMonitoring()
        }
    }

    fun onStudioStreamDetected(videoId: String, title: String, liveState: String, viewers: Int) {
        val cleanId = YouTubeChatCapture.extractVideoId(videoId)
        if (cleanId.isEmpty()) return

        val oldId = _streamState.value.videoId
        val oldState = _streamState.value.liveState

        val resolvedTitle = if (title.isNotBlank()) title else _streamState.value.title ?: "Active Studio Livestream"
        val resolvedState = if (liveState.isNotBlank()) liveState else "live"
        val resolvedViewers = if (viewers > 0) viewers else _streamState.value.viewers

        _streamState.value = StreamState(
            videoId = cleanId,
            title = resolvedTitle,
            liveState = resolvedState,
            viewers = resolvedViewers
        )
        prefs.youtubeVideoId = cleanId

        // Auto start chat capture if YouTube is enabled
        if (_isServerRunning.value && prefs.youtubeEnabled) {
            if (cleanId != oldId || ytCapture?.isCapturing != true) {
                ytCapture?.startCapture(cleanId, prefs.lowResourceMode)
            }
        }

        wsServer?.currentStreamState = _streamState.value
        wsServer?.broadcastEvent("stream_update", _streamState.value)
        wsServer?.broadcastEvent("stream-state-changed", _streamState.value)
        remoteTransmitter.forwardEvent("stream_update", _streamState.value)

        if (cleanId != oldId || liveState != oldState) {
            logSystem("🛰️ [YouTube Studio] Live stream active! Video ID: $cleanId (State: $resolvedState, Viewers: $resolvedViewers)", "success")
        }
    }

    fun onStudioNewSubscriber(username: String, avatar: String) {
        val alert = ChatMessage(
            platform = "youtube",
            type = "subscription",
            id = "sub_${System.currentTimeMillis()}_${(1000..9999).random()}",
            username = username,
            avatar = avatar,
            message = "Just subscribed!",
            timestamp = System.currentTimeMillis()
        )
        handleIncomingChatEvent(alert)
        logSystem("🎉 [YouTube Studio] New subscriber: $username", "success")
    }

    fun refreshIp() {
        _localIp.value = NetworkUtils.getLocalIpAddress(context)
    }

    fun startRelayService() {
        if (_isServerRunning.value) return

        refreshIp()
        val httpPort = prefs.httpPort
        val wsPort = prefs.wsPort

        logSystem("Starting OBS Chat Relay Services...", "info")

        // 1. WebSocket Server
        try {
            wsServer = RelayWebSocketServer(
                port = wsPort,
                onLog = { msg, type -> logSystem(msg, type) },
                onClientCountChanged = { count ->
                    scope.launch { _connectedClientsCount.value = count }
                }
            ).apply {
                lowResourceMode = prefs.lowResourceMode
                clearOldChat = prefs.clearOldChat
                clearOldChatLimit = prefs.clearOldChatLimit
                clearOldChatTimeout = prefs.clearOldChatTimeout
                chatSoundEnabled = prefs.chatSoundEnabled
                alertSoundEnabled = prefs.alertSoundEnabled
                start()
            }
        } catch (e: Exception) {
            logSystem("WebSocket Server failed to start on port $wsPort: ${e.message}", "error")
        }

        // 2. HTTP Server
        try {
            httpServer = OverlayHttpServer(
                context = context,
                port = httpPort,
                wsPort = wsPort,
                onClearChatRequested = { clearChat() },
                onToggleClearChatRequested = { toggleClearChat() },
                onToggleLowResourceRequested = { toggleLowResourceMode() },
                getSettings = {
                    mapOf(
                        "lowResourceMode" to prefs.lowResourceMode,
                        "clearOldChat" to prefs.clearOldChat,
                        "clearOldChatLimit" to prefs.clearOldChatLimit,
                        "clearOldChatTimeout" to prefs.clearOldChatTimeout
                    )
                },
                onLog = { msg, type -> logSystem(msg, type) }
            ).apply {
                start()
            }
        } catch (e: Exception) {
            logSystem("HTTP Server failed to start on port $httpPort: ${e.message}", "error")
        }

        // 3. YouTube Capture
        ytCapture = YouTubeChatCapture(
            context = context,
            onChatEvent = { event -> handleIncomingChatEvent(event) },
            onLog = { msg, type -> logSystem(msg, type) }
        )

        val ytId = prefs.youtubeVideoId
        if (prefs.youtubeEnabled && ytId.isNotEmpty()) {
            ytCapture?.startCapture(ytId, prefs.lowResourceMode)
            _streamState.value = StreamState(videoId = ytId, liveState = "monitoring")
        }

        // 4. Kick Capture
        kickCapture = KickChatCapture(
            onChatEvent = { event -> handleIncomingChatEvent(event) },
            onKickStateChanged = { state ->
                scope.launch { _kickState.value = state }
                wsServer?.broadcastEvent("kick_stream_update", state)
                remoteTransmitter.forwardEvent("kick_stream_update", state)
            },
            onLog = { msg, type -> logSystem(msg, type) }
        )

        val kickUser = prefs.kickUsername
        if (prefs.kickEnabled && kickUser.isNotEmpty()) {
            kickCapture?.start(kickUser)
        }

        // 5. Remote Network Sharing Transmitter (Mobile Data -> PC IP)
        if (prefs.remoteForwardingEnabled) {
            remoteTransmitter.start()
        }

        // 6. Start AI Bot Timers
        ollamaBot.startTimers()

        _isServerRunning.value = true
        logSystem("Relay Service active! Connect OBS to http://${_localIp.value}:$httpPort/chatdock.html", "success")
    }

    fun stopRelayService() {
        if (!_isServerRunning.value) return

        ytCapture?.stopCapture()
        ytCapture = null

        ytStudioMonitor?.stopMonitoring()

        kickCapture?.stop()
        kickCapture = null

        remoteTransmitter.stop()

        try {
            wsServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wsServer = null

        try {
            httpServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        httpServer = null

        _isServerRunning.value = false
        _connectedClientsCount.value = 0
        logSystem("OBS Chat Relay Services stopped", "info")
    }

    fun updateYouTubeCapture(videoIdOrUrl: String) {
        val id = YouTubeChatCapture.extractVideoId(videoIdOrUrl)
        prefs.youtubeVideoId = id
        _streamState.value = StreamState(videoId = id, liveState = if (id.isNotEmpty()) "monitoring" else "offline")

        if (id.isNotEmpty()) {
            pollYouTubeStreamData(id)
            ytBotSender.prewarm(id)
        }

        if (_isServerRunning.value && prefs.youtubeEnabled && id.isNotEmpty()) {
            ytCapture?.startCapture(id, prefs.lowResourceMode)
        } else {
            ytCapture?.stopCapture()
        }
    }

    fun updateKickCapture(username: String) {
        prefs.kickUsername = username
        if (username.isNotEmpty()) {
            pollKickChannelData(username)
        }
        if (_isServerRunning.value && prefs.kickEnabled && username.isNotEmpty()) {
            kickCapture?.start(username)
        } else {
            kickCapture?.stop()
        }
    }

    fun setYouTubeEnabled(enabled: Boolean) {
        prefs.youtubeEnabled = enabled
        if (enabled) {
            initStudioMonitor()
            ytStudioMonitor?.startMonitoring()
            if (prefs.youtubeVideoId.isNotEmpty()) {
                pollYouTubeStreamData(prefs.youtubeVideoId)
                if (_isServerRunning.value) {
                    ytCapture?.startCapture(prefs.youtubeVideoId, prefs.lowResourceMode)
                }
            } else if (sessionManager.ytBroadcasterLoggedIn.value) {
                pollYouTubeChannelForLive(sessionManager.ytBroadcasterName.value)
            }
        } else {
            ytStudioMonitor?.stopMonitoring()
            ytCapture?.stopCapture()
        }
    }

    fun setKickEnabled(enabled: Boolean) {
        prefs.kickEnabled = enabled
        if (enabled && prefs.kickUsername.isNotEmpty()) {
            pollKickChannelData(prefs.kickUsername)
            if (_isServerRunning.value) {
                kickCapture?.start(prefs.kickUsername)
            }
        } else {
            kickCapture?.stop()
        }
    }

    fun handleIncomingChatEvent(event: ChatMessage) {
        scope.launch {
            if (event.type == "chat") {
                _messageCount.value += 1
                val list = _recentChats.value.toMutableList()
                list.add(event)
                if (list.size > 100) list.removeAt(0)
                _recentChats.value = list

                wsServer?.broadcastEvent("chat", event)
                remoteTransmitter.forwardEvent("chat", event)

                if (prefs.chatSoundEnabled) {
                    soundPlayer.playTone("chat")
                }

                // AI Bot Processing
                if (event.username != prefs.botName) {
                    ollamaBot.handleChatMessage(event)
                }
            } else if (event.type == "poll" || event.type == "poll_ended") {
                wsServer?.broadcastEvent("poll", event)
                wsServer?.broadcastEvent("chat", event)
                remoteTransmitter.forwardEvent("poll", event)
                remoteTransmitter.forwardEvent("chat", event)
                logSystem(
                    if (event.type == "poll") "📊 Live Poll captured: \"${event.question}\""
                    else "📊 Live Poll ended",
                    "info"
                )
            } else {
                // SuperChat, Sticker, Membership, Gift
                _alertCount.value += 1
                val alertList = _recentAlerts.value.toMutableList()
                alertList.add(event)
                if (alertList.size > 50) alertList.removeAt(0)
                _recentAlerts.value = alertList

                // Also add to _recentChats so the unified chat dock & feed display both chats and alerts in one place!
                val chatList = _recentChats.value.toMutableList()
                chatList.add(event)
                if (chatList.size > 100) chatList.removeAt(0)
                _recentChats.value = chatList

                wsServer?.broadcastEvent("alert", event)
                remoteTransmitter.forwardEvent("alert", event)

                logSystem("Alert Captured: [${event.type}] from ${event.username}: ${event.amount ?: event.message}", "success")

                if (prefs.alertSoundEnabled) {
                    soundPlayer.playTone("alert")
                }
            }
        }
    }

    private fun handleRemoteCommand(cmd: String, data: com.google.gson.JsonObject) {
        scope.launch {
            when (cmd) {
                "clear_chat" -> clearChat()
                "settings_update" -> {
                    if (data.has("lowResourceMode")) {
                        prefs.lowResourceMode = data.get("lowResourceMode").asBoolean
                    }
                }
                "stream_update", "stream-state-changed", "stream_status" -> {
                    val videoId = if (data.has("videoId") && !data.get("videoId").isJsonNull) data.get("videoId").asString else _streamState.value.videoId
                    val liveState = if (data.has("liveState") && !data.get("liveState").isJsonNull) data.get("liveState").asString
                                    else if (data.has("status") && !data.get("status").isJsonNull) data.get("status").asString
                                    else _streamState.value.liveState
                    val title = if (data.has("title") && !data.get("title").isJsonNull) data.get("title").asString else _streamState.value.title
                    val viewers = if (data.has("concurrentViewers") && !data.get("concurrentViewers").isJsonNull) data.get("concurrentViewers").asInt
                                  else if (data.has("viewers") && !data.get("viewers").isJsonNull) data.get("viewers").asInt
                                  else _streamState.value.viewers
                    _streamState.value = StreamState(
                        videoId = videoId,
                        liveState = liveState,
                        title = title,
                        viewers = viewers
                    )
                    if (!videoId.isNullOrBlank() && videoId != prefs.youtubeVideoId) {
                        prefs.youtubeVideoId = videoId
                    }
                }
                "kick_update", "kick-status-changed", "kick_stream_update" -> {
                    val user = if (data.has("username") && !data.get("username").isJsonNull) data.get("username").asString else _kickState.value.username
                    val viewers = if (data.has("viewers") && !data.get("viewers").isJsonNull) data.get("viewers").asInt else _kickState.value.viewers
                    val isLive = if (data.has("isLive") && !data.get("isLive").isJsonNull) data.get("isLive").asBoolean else (viewers > 0)
                    val chatroomId = if (data.has("chatroomId") && !data.get("chatroomId").isJsonNull) data.get("chatroomId").asString else _kickState.value.chatroomId
                    _kickState.value = KickState(
                        username = user,
                        chatroomId = chatroomId,
                        isLive = isLive,
                        viewers = viewers
                    )
                    if (!user.isNullOrBlank() && user != prefs.kickUsername) {
                        prefs.kickUsername = user
                    }
                }
            }
        }
    }

    fun pollKickChannelData(username: String) {
        val cleanUser = username.trim()
        if (cleanUser.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://kick.com/api/v2/channels/$cleanUser")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JsonParser.parseString(body).asJsonObject
                        val userObj = json.getAsJsonObject("user")
                        val resolvedUsername = userObj?.get("username")?.asString ?: cleanUser
                        val chatroomObj = json.getAsJsonObject("chatroom")
                        val resolvedChatroomId = chatroomObj?.get("id")?.asString ?: _kickState.value.chatroomId
                        val livestreamObj = json.get("livestream")

                        var isLive = false
                        var viewers = 0
                        if (livestreamObj != null && !livestreamObj.isJsonNull && livestreamObj.isJsonObject) {
                            val ls = livestreamObj.asJsonObject
                            isLive = ls.get("is_live")?.asBoolean ?: true
                            viewers = ls.get("viewer_count")?.asInt ?: 0
                        }

                        val newState = KickState(
                            username = resolvedUsername,
                            chatroomId = resolvedChatroomId,
                            isLive = isLive,
                            viewers = viewers
                        )
                        _kickState.value = newState
                        wsServer?.broadcastEvent("kick_stream_update", newState)
                        remoteTransmitter.forwardEvent("kick_stream_update", newState)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun pollYouTubeStreamData(videoId: String) {
        val cleanId = videoId.trim()
        if (cleanId.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://www.youtube.com/watch?v=$cleanId")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val isLive = body.contains("VIEW_COUNT_VIDEO_TYPE_LIVE") || body.contains("\"isLive\":true")
                        val isUpcoming = body.contains("\"status\":\"UPCOMING\"") || body.contains("\"upcomingEventData\"")
                        val viewersPattern = Pattern.compile("\"viewCount\":\\{\"runs\":\\[\\{\"text\":\"([0-9,]+)\\s+watching")
                        val viewersMatcher = viewersPattern.matcher(body)
                        val viewers = if (viewersMatcher.find()) {
                            viewersMatcher.group(1)?.replace(",", "")?.toIntOrNull() ?: 0
                        } else 0

                        val titlePattern = Pattern.compile("<title>(.*?)(?: - YouTube)?</title>")
                        val titleMatcher = titlePattern.matcher(body)
                        val title = if (titleMatcher.find()) {
                            titleMatcher.group(1)?.replace(" - YouTube", "")?.trim() ?: "YouTube Live"
                        } else _streamState.value.title ?: "Active Livestream"

                        val liveState = if (isLive) "live" else if (isUpcoming) "upcoming" else "offline"

                        val newState = StreamState(
                            videoId = cleanId,
                            title = title,
                            liveState = liveState,
                            viewers = viewers
                        )
                        _streamState.value = newState
                        wsServer?.currentStreamState = newState
                        wsServer?.broadcastEvent("stream_update", newState)
                        wsServer?.broadcastEvent("stream-state-changed", newState)
                        remoteTransmitter.forwardEvent("stream_update", newState)
                    }
                }
            } catch (e: Exception) {
                logSystem("Failed to poll YouTube video data: ${e.message}", "warn")
            }
        }
    }

    fun pollYouTubeChannelForLive(channelOrHandle: String) {
        var clean = channelOrHandle.trim().removePrefix("@")
        val isInvalid = clean.isBlank() ||
                clean.equals("YouTube Channel", true) ||
                clean.equals("YouTube Streamer", true) ||
                clean.contains("Creator Studio", true) ||
                clean.contains("YouTube Studio", true)

        if (isInvalid) {
            // Fallback to Kick username if available, as streamers often share handles across platforms
            val kickUser = prefs.kickUsername.trim()
            if (kickUser.isNotBlank() && !kickUser.equals("Kick Streamer", true)) {
                clean = kickUser
            } else {
                return
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val url = "https://www.youtube.com/@$clean/live"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val vidPattern = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                        val matcher = vidPattern.matcher(body)
                        if (matcher.find()) {
                            val vid = matcher.group(1)
                            if (!vid.isNullOrEmpty()) {
                                val isLive = body.contains("VIEW_COUNT_VIDEO_TYPE_LIVE") || body.contains("\"isLive\":true")
                                val isUpcoming = body.contains("\"status\":\"UPCOMING\"") || body.contains("\"upcomingEventData\"")
                                val viewersPattern = Pattern.compile("\"viewCount\":\\{\"runs\":\\[\\{\"text\":\"([0-9,]+)\\s+watching")
                                val viewersMatcher = viewersPattern.matcher(body)
                                val viewers = if (viewersMatcher.find()) {
                                    viewersMatcher.group(1)?.replace(",", "")?.toIntOrNull() ?: 0
                                } else 0

                                val titlePattern = Pattern.compile("<title>(.*?)(?: - YouTube)?</title>")
                                val titleMatcher = titlePattern.matcher(body)
                                val title = if (titleMatcher.find()) {
                                    titleMatcher.group(1)?.replace(" - YouTube", "")?.trim() ?: "YouTube Live"
                                } else "Active Livestream"

                                val liveState = if (isLive) "live" else if (isUpcoming) "upcoming" else "offline"

                                scope.launch(Dispatchers.Main) {
                                    onStudioStreamDetected(vid, title, liveState, viewers)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logSystem("Failed to poll YouTube channel for live: ${e.message}", "warn")
            }
        }
    }

    fun startMonitoringPollers() {
        stopMonitoringPollers()
        kickPollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                if (prefs.kickEnabled && prefs.kickUsername.isNotBlank()) {
                    pollKickChannelData(prefs.kickUsername)
                }
                delay(20000)
            }
        }
        ytPollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                if (prefs.youtubeEnabled) {
                    if (prefs.youtubeVideoId.isNotBlank()) {
                        pollYouTubeStreamData(prefs.youtubeVideoId)
                    } else {
                        scope.launch(Dispatchers.Main) {
                            initStudioMonitor()
                            ytStudioMonitor?.startMonitoring()
                        }
                        val handleToTry = sessionManager.ytBroadcasterName.value.ifBlank { prefs.kickUsername }
                        pollYouTubeChannelForLive(handleToTry)
                    }
                }
                delay(20000)
            }
        }
    }

    fun stopMonitoringPollers() {
        kickPollJob?.cancel()
        kickPollJob = null
        ytPollJob?.cancel()
        ytPollJob = null
    }

    fun clearChat() {
        scope.launch {
            _recentChats.value = emptyList()
            wsServer?.clearChat()
            remoteTransmitter.forwardEvent("clear_chat", mapOf("action" to "clear"))
        }
    }

    fun toggleClearChat(): Boolean {
        val newVal = !prefs.clearOldChat
        prefs.clearOldChat = newVal
        wsServer?.clearOldChat = newVal
        wsServer?.broadcastEvent("settings_update", mapOf("clearOldChat" to newVal))
        remoteTransmitter.forwardEvent("settings_update", mapOf("clearOldChat" to newVal))
        logSystem("Auto clear old chat set to: $newVal", "info")
        return newVal
    }

    fun toggleLowResourceMode(): Boolean {
        val newVal = !prefs.lowResourceMode
        prefs.lowResourceMode = newVal
        wsServer?.lowResourceMode = newVal
        wsServer?.broadcastEvent("settings_update", mapOf("lowResourceMode" to newVal))
        remoteTransmitter.forwardEvent("settings_update", mapOf("lowResourceMode" to newVal))
        logSystem("Low Resource Mode set to: $newVal", "info")
        return newVal
    }

    fun logSystem(message: String, type: String = "info") {
        android.util.Log.i("RelayController", "[$type] $message")
        val entry = LogEntry(message = message, type = type)
        scope.launch {
            val list = _recentLogs.value.toMutableList()
            list.add(entry)
            if (list.size > 100) list.removeAt(0)
            _recentLogs.value = list
        }
        wsServer?.appendLog(entry)
    }

    fun getChatDockUrl(): String = "http://${_localIp.value}:${prefs.httpPort}/chatdock.html"
    fun getAlertDockUrl(): String = "http://${_localIp.value}:${prefs.httpPort}/alertdock.html"
    fun getEventDockUrl(): String = "http://${_localIp.value}:${prefs.httpPort}/eventdock.html"
}
