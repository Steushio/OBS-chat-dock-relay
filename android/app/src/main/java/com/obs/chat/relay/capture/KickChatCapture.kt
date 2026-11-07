package com.obs.chat.relay.capture

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.obs.chat.relay.model.ChatMessage
import com.obs.chat.relay.model.KickState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class KickChatCapture(
    private val onChatEvent: (ChatMessage) -> Unit,
    private val onKickStateChanged: (KickState) -> Unit,
    private val onLog: (String, String) -> Unit
) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null

    private var username: String = "steushio"
    private var chatroomId: String? = null
    private var channelId: String? = null
    var isEnabled: Boolean = false
        private set

    private val pusherKey = "34bf7a0ff419a2a775b9"
    private val pusherCluster = "us2"

    fun start(channelUsername: String) {
        stop()
        username = channelUsername.trim()
        isEnabled = true
        onLog("[Kick] Starting chat capture for channel: $username", "info")

        scope.launch {
            resolveAndConnect()
        }
    }

    private suspend fun resolveAndConnect() {
        onLog("[Kick] Resolving Chatroom ID for $username...", "info")
        try {
            val req = Request.Builder()
                .url("https://kick.com/api/v2/channels/$username/chatroom")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(req).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val json = JsonParser.parseString(body).asJsonObject
                    chatroomId = json.get("id")?.asString
                    channelId = json.get("channel_id")?.asString
                    onLog("[Kick] Resolved Chatroom ID: $chatroomId", "success")
                }
            }
        } catch (e: Exception) {
            onLog("[Kick] Could not resolve chatroom via API (${e.message}), trying fallback", "warning")
        }

        if (chatroomId.isNullOrEmpty()) {
            // Fallback for demo / user channel
            chatroomId = if (username.equals("steushio", ignoreCase = true)) "33438685" else "1"
        }

        connectWebSocket()
    }

    private fun connectWebSocket() {
        val cid = chatroomId ?: return
        val url = "wss://ws-$pusherCluster.pusher.com/app/$pusherKey?protocol=7&client=js&version=8.4.0-rc2&flash=false"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog("[Kick] Pusher WebSocket connected", "success")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handlePusherMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("[Kick] WebSocket error: ${t.message}", "error")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLog("[Kick] WebSocket closed", "info")
                if (isEnabled) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun handlePusherMessage(rawMessage: String) {
        try {
            val root = JsonParser.parseString(rawMessage).asJsonObject
            val event = root.get("event")?.asString ?: return

            when (event) {
                "pusher:connection_established" -> {
                    onLog("[Kick] Connection established. Subscribing to chatroom $chatroomId...", "info")
                    val subscribeChat = mapOf(
                        "event" to "pusher:subscribe",
                        "data" to mapOf("auth" to "", "channel" to "chatrooms.$chatroomId.v2")
                    )
                    webSocket?.send(gson.toJson(subscribeChat))

                    if (!channelId.isNullOrEmpty()) {
                        val subscribeChannel = mapOf(
                            "event" to "pusher:subscribe",
                            "data" to mapOf("auth" to "", "channel" to "channel.$channelId")
                        )
                        webSocket?.send(gson.toJson(subscribeChannel))
                    }

                    onKickStateChanged(KickState(username = username, chatroomId = chatroomId, isLive = true, viewers = 0))
                }

                "App\\Events\\ChatMessageEvent" -> {
                    val dataStr = root.get("data")?.asString ?: return
                    val dataObj = JsonParser.parseString(dataStr).asJsonObject
                    parseChatMessage(dataObj)
                }

                "App\\Events\\GiftedSubscriptionsEvent" -> {
                    val dataStr = root.get("data")?.asString ?: return
                    val dataObj = JsonParser.parseString(dataStr).asJsonObject
                    parseGiftEvent(dataObj)
                }

                "App\\Events\\SubscriptionEvent" -> {
                    val dataStr = root.get("data")?.asString ?: return
                    val dataObj = JsonParser.parseString(dataStr).asJsonObject
                    parseSubscriptionEvent(dataObj)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseChatMessage(obj: JsonObject) {
        val id = obj.get("id")?.asString ?: "kick_${System.currentTimeMillis()}"
        val content = obj.get("content")?.asString ?: ""
        val sender = obj.getAsJsonObject("sender")
        val senderName = sender?.get("username")?.asString ?: "Anonymous"
        val identity = sender?.getAsJsonObject("identity")

        val badges = mutableListOf<String>()
        val badgesArr = identity?.getAsJsonArray("badges")
        badgesArr?.forEach { badgeEl ->
            val bType = badgeEl.asJsonObject.get("type")?.asString
            if (bType != null) badges.add(bType)
        }

        val color = identity?.get("color")?.asString

        val chatMessage = ChatMessage(
            type = "chat",
            platform = "kick",
            id = id,
            username = senderName,
            message = content,
            badges = badges,
            color = color,
            timestamp = System.currentTimeMillis()
        )
        onChatEvent(chatMessage)
    }

    private fun parseGiftEvent(obj: JsonObject) {
        val gifter = obj.get("gifter_username")?.asString ?: "Supporter"
        val count = obj.get("gifted_usernames")?.asJsonArray?.size() ?: 1
        val alert = ChatMessage(
            type = "membership_gift",
            platform = "kick",
            id = "gift_${System.currentTimeMillis()}",
            username = gifter,
            message = "Gifted $count sub(s) to the community!",
            timestamp = System.currentTimeMillis()
        )
        onChatEvent(alert)
    }

    private fun parseSubscriptionEvent(obj: JsonObject) {
        val subscriber = obj.get("username")?.asString ?: "Supporter"
        val months = obj.get("months")?.asInt ?: 1
        val alert = ChatMessage(
            type = "membership",
            platform = "kick",
            id = "sub_${System.currentTimeMillis()}",
            username = subscriber,
            message = "Subscribed for $months month(s)!",
            timestamp = System.currentTimeMillis()
        )
        onChatEvent(alert)
    }

    private fun scheduleReconnect() {
        if (!isEnabled) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            if (isEnabled) {
                onLog("[Kick] Attempting reconnect...", "info")
                connectWebSocket()
            }
        }
    }

    fun stop() {
        isEnabled = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "User stopped capture")
        webSocket = null
        onLog("[Kick] Chat capture stopped", "info")
    }
}
