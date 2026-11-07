package com.obs.chat.relay.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.obs.chat.relay.model.ChatMessage
import com.obs.chat.relay.util.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URI
import java.util.concurrent.TimeUnit

class RemoteRelayTransmitter(
    private val prefs: PreferencesManager,
    private val onLog: (String, String) -> Unit,
    private val onRemoteCommandReceived: (String, JsonObject) -> Unit
) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Dedicated HTTP client for Cloud Room and REST push with proper timeouts
    private val cloudHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var mqttClient: MqttAsyncClient? = null
    private var isMqttConnecting = false
    var onMqttConnected: (() -> Unit)? = null
    var isEnabled: Boolean = false
        private set

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _isRemoteConnected = MutableStateFlow(false)
    val isRemoteConnected: StateFlow<Boolean> = _isRemoteConnected.asStateFlow()

    fun start() {
        if (!prefs.remoteForwardingEnabled) {
            _connectionStatus.value = "Disabled"
            return
        }
        val target = prefs.remoteTargetHost.trim()
        if (target.isBlank()) {
            _connectionStatus.value = "No Target IP Configured"
            onLog("[Remote Relay] No Target PC IP/Host configured in settings", "warning")
            return
        }

        stop()
        isEnabled = true
        _connectionStatus.value = "Connecting..."
        onLog("[Remote Relay] Connecting to remote PC overlay at: $target...", "info")

        connectWebSocket(target)
    }

    private fun normalizeWsUrl(target: String): String {
        var clean = target.trim()
        val isSecureTunnel = clean.contains(".loca.lt", ignoreCase = true) ||
                             clean.contains("trycloudflare.com", ignoreCase = true) ||
                             clean.startsWith("https://", ignoreCase = true) ||
                             clean.startsWith("wss://", ignoreCase = true)

        if (!clean.startsWith("ws://") && !clean.startsWith("wss://")) {
            if (clean.startsWith("http://")) {
                clean = "ws://" + clean.removePrefix("http://")
            } else if (clean.startsWith("https://")) {
                clean = "wss://" + clean.removePrefix("https://")
            } else {
                clean = if (isSecureTunnel) "wss://$clean" else "ws://$clean"
            }
        }

        // If port is missing and not a standard SSL cloud tunnel, append default port
        try {
            val uri = URI(clean)
            if (uri.port == -1) {
                if (!isSecureTunnel && !clean.startsWith("wss://")) {
                    clean = "$clean:3000"
                }
            }
        } catch (e: Exception) {}
        return clean
    }

    private fun connectWebSocket(target: String) {
        val url = normalizeWsUrl(target)
        val request = Request.Builder()
            .url(url)
            .header("Bypass-Tunnel-Reminder", "true")
            .header("User-Agent", "OBSChatRelay-Android")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isRemoteConnected.value = true
                reconnectAttempts = 0
                _connectionStatus.value = "Connected to OBS PC"
                onLog("[Remote Relay] Successfully connected to PC overlay at $url! Streaming chat online...", "success")

                // Send identification handshake with phone model
                val deviceName = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
                val idData = mapOf(
                    "event" to "remote_transmitter_connected",
                    "data" to mapOf(
                        "device" to deviceName,
                        "cloudRoom" to prefs.cloudRoomCode,
                        "timestamp" to System.currentTimeMillis()
                    )
                )
                webSocket.send(gson.toJson(idData))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = com.google.gson.JsonParser.parseString(text).asJsonObject
                    val event = root.get("event")?.asString
                    val data = root.getAsJsonObject("data") ?: JsonObject()
                    if (event != null) {
                        onRemoteCommandReceived(event, data)
                    }
                } catch (e: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isRemoteConnected.value = false
                _connectionStatus.value = "Connection Failed (${t.message})"
                onLog("[Remote Relay] Connection error to PC ($url): ${t.message}", "error")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isRemoteConnected.value = false
                _connectionStatus.value = "Disconnected"
                onLog("[Remote Relay] Connection to PC closed", "info")
                if (isEnabled) {
                    scheduleReconnect()
                }
            }
        })
    }

    fun forwardEvent(event: String, data: Any) {
        val room = prefs.cloudRoomCode.ifEmpty { "steushio_live" }

        // Always publish to Cloud Room so OBS receives chat directly with 0 PC apps running
        scope.launch {
            publishToCloudRoom(room, event, data)
        }

        if (!isEnabled) return

        val payload = gson.toJson(mapOf(
            "event" to event,
            "data" to data,
            "source" to "mobile_relay",
            "room" to room
        ))

        // 1. Send via WebSocket if connected
        val ws = webSocket
        if (ws != null && _isRemoteConnected.value) {
            val sent = ws.send(payload)
            if (sent) return
        }

        // 2. HTTP Push Fallback if WebSocket is not open
        scope.launch {
            httpPushFallback(event, data)
        }
    }

    @Synchronized
    fun ensureMqttConnected() {
        val room = prefs.cloudRoomCode.ifEmpty { "steushio" }.trim()
        if (room.isBlank()) return

        if (mqttClient?.isConnected == true || isMqttConnecting) return

        try {
            isMqttConnecting = true
            val clientId = "obs_relay_app_" + (1000..9999).random()
            val client = MqttAsyncClient("tcp://broker.emqx.io:1883", clientId, MemoryPersistence())
            mqttClient = client

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 5
                keepAliveInterval = 30
                setServerURIs(arrayOf("tcp://broker.emqx.io:1883", "tcp://broker.hivemq.com:1883"))
            }

            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    isMqttConnecting = false
                    onLog("[Cloud Network] Realtime MQTT connected (Room: $room)", "success")
                    try {
                        onMqttConnected?.invoke()
                    } catch (e: Exception) {}
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    isMqttConnecting = false
                    onLog("[Cloud Network] Realtime broker reconnecting: ${exception?.message}", "info")
                }
            })
        } catch (e: Exception) {
            isMqttConnecting = false
            onLog("[Cloud Network] Realtime broker error: ${e.message}", "info")
        }
    }

    private fun publishToCloudMqtt(room: String, jsonPayload: String, retained: Boolean = false) {
        val cleanRoom = room.trim().ifEmpty { "steushio" }
        val topic = "obs_relay_$cleanRoom"
        try {
            if (mqttClient == null || mqttClient?.isConnected != true) {
                ensureMqttConnected()
            }
            val msg = MqttMessage(jsonPayload.toByteArray(Charsets.UTF_8)).apply {
                qos = 0
                isRetained = retained
            }
            if (mqttClient?.isConnected == true) {
                mqttClient?.publish(topic, msg)
                if (retained) {
                    // Also publish to /stream subtopic for clients subscribing specifically
                    mqttClient?.publish("$topic/stream", msg)
                }
            }
        } catch (e: Exception) {
            // MQTT publish error
        }
    }

    private fun publishToCloudRoom(room: String, event: String, data: Any) {
        val cleanRoom = room.trim().ifEmpty { "steushio" }
        if (cleanRoom.isBlank()) return

        val jsonPayload = gson.toJson(mapOf(
            "event" to event,
            "data" to data,
            "timestamp" to System.currentTimeMillis()
        ))

        // Retain stream state so docks connecting at any time get the viewer count immediately!
        val isStreamStateEvent = event == "stream_update" || event == "kick_stream_update" ||
                event == "stream-state-changed" || event == "kick_update"

        // 1. Instant unlimited broadcast over Realtime MQTT Broker (0 rate limit)
        publishToCloudMqtt(cleanRoom, jsonPayload, retained = isStreamStateEvent)

        // 2. Also publish to ntfy.sh as secondary fallback for history
        try {
            val body = jsonPayload.toRequestBody("text/plain; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("https://ntfy.sh/$cleanRoom")
                .header("User-Agent", "OBSChatRelay-Android")
                .post(body)
                .build()

            cloudHttpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    onLog("[Cloud Room] Broadcast $event to room '$cleanRoom'", "info")
                } else if (response.code != 429) {
                    onLog("[Cloud Room] Warning: HTTP ${response.code} from room '$cleanRoom'", "warning")
                }
            }
        } catch (e: Exception) {
            // Ignore secondary fallback errors
        }
    }

    private fun httpPushFallback(event: String, data: Any) {
        val target = prefs.remoteTargetHost.trim()
        if (target.isBlank() || !isEnabled) return

        var httpUrl = target
        if (!httpUrl.startsWith("http://") && !httpUrl.startsWith("https://")) {
            httpUrl = "http://" + httpUrl.removePrefix("ws://").removePrefix("wss://")
        }
        val cleanUrl = httpUrl.trimEnd('/') + "/api/relay-event"

        try {
            val jsonPayload = gson.toJson(mapOf(
                "event" to event,
                "data" to data,
                "room" to prefs.cloudRoomCode
            ))
            val body = jsonPayload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(cleanUrl)
                .header("Bypass-Tunnel-Reminder", "true")
                .header("User-Agent", "OBSChatRelay-Android")
                .post(body)
                .build()

            cloudHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    _connectionStatus.value = "HTTP Push Active ($target)"
                } else {
                    _connectionStatus.value = "Push Failed (${response.code})"
                }
            }
        } catch (e: Exception) {
            _connectionStatus.value = "Push Failed (${e.message})"
        }
    }

    private fun scheduleReconnect() {
        if (!isEnabled) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffSeconds = (5L * (1 shl reconnectAttempts.coerceAtMost(3))).coerceAtMost(30L)
            delay(backoffSeconds * 1000L)
            if (isEnabled && !_isRemoteConnected.value) {
                val target = prefs.remoteTargetHost.trim()
                if (target.isNotBlank()) {
                    reconnectAttempts++
                    if (reconnectAttempts <= 3 || reconnectAttempts % 5 == 0) {
                        onLog("[Remote Relay] Attempting to reconnect to PC overlay ($target)...", "info")
                    }
                    connectWebSocket(target)
                }
            }
        }
    }

    suspend fun testConnection(target: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val url = normalizeWsUrl(target)
        try {
            var httpUrl = target
            if (!httpUrl.startsWith("http://") && !httpUrl.startsWith("https://")) {
                httpUrl = "http://" + httpUrl.removePrefix("ws://").removePrefix("wss://")
            }
            // Strip port 3001 if testing HTTP on 3000 or vice versa
            val testHttpUrl = httpUrl.trimEnd('/') + "/status"
            val req = Request.Builder()
                .url(testHttpUrl)
                .get()
                .build()

            cloudHttpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    return@withContext Pair(true, "Successfully reached PC Overlay HTTP Server! (${res.code})")
                } else {
                    return@withContext Pair(false, "Server reached but returned HTTP ${res.code}")
                }
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Cannot connect: ${e.message}")
        }
    }

    fun stop() {
        isEnabled = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        webSocket?.close(1000, "Stopped by user")
        webSocket = null
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect()
            }
            mqttClient?.close()
        } catch (e: Exception) {}
        mqttClient = null
        _isRemoteConnected.value = false
        _connectionStatus.value = "Disconnected"
    }
}
