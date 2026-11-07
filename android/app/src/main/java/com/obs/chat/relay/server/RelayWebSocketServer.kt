package com.obs.chat.relay.server

import com.google.gson.Gson
import com.obs.chat.relay.model.ChatMessage
import com.obs.chat.relay.model.KickState
import com.obs.chat.relay.model.LogEntry
import com.obs.chat.relay.model.StreamState
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class RelayWebSocketServer(
    port: Int,
    private val onLog: (String, String) -> Unit,
    private val onClientCountChanged: (Int) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    init {
        isReuseAddr = true
        isTcpNoDelay = true
    }

    private val gson = Gson()
    private val connectedClients = Collections.newSetFromMap(ConcurrentHashMap<WebSocket, Boolean>())

    var currentStreamState: StreamState? = null
    var currentKickState: KickState? = null
    var lowResourceMode: Boolean = false
    var clearOldChat: Boolean = false
    var clearOldChatLimit: Int = 30
    var clearOldChatTimeout: Int = 120
    var chatSoundEnabled: Boolean = true
    var alertSoundEnabled: Boolean = true
    var getRecentChats: (() -> List<ChatMessage>)? = null
    var getRecentAlerts: (() -> List<ChatMessage>)? = null

    private val recentLogs = Collections.synchronizedList(mutableListOf<LogEntry>())

    override fun onStart() {
        onLog("WebSocket Server listening on ws://0.0.0.0:$port", "success")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        connectedClients.add(conn)
        onClientCountChanged(connectedClients.size)
        onLog("OBS Dock connected from ${conn.remoteSocketAddress}", "info")

        // Send initial handshake state
        val handshakeData = mapOf(
            "status" to "connected",
            "stream" to currentStreamState,
            "kickStream" to currentKickState,
            "recentChats" to (getRecentChats?.invoke() ?: emptyList()),
            "recentAlerts" to (getRecentAlerts?.invoke() ?: emptyList()),
            "recentLogs" to synchronized(recentLogs) { recentLogs.takeLast(50) },
            "lowResourceMode" to lowResourceMode,
            "clearOldChat" to clearOldChat,
            "clearOldChatLimit" to clearOldChatLimit,
            "clearOldChatTimeout" to clearOldChatTimeout,
            "audioSettings" to mapOf(
                "chatSoundEnabled" to chatSoundEnabled,
                "chatSoundType" to "default",
                "chatSoundVolume" to 100,
                "alertSoundEnabled" to alertSoundEnabled,
                "alertSoundType" to "default",
                "alertSoundVolume" to 100
            )
        )

        val payload = gson.toJson(mapOf(
            "event" to "handshake",
            "data" to handshakeData
        ))
        conn.send(payload)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        connectedClients.remove(conn)
        onClientCountChanged(connectedClients.size)
        onLog("OBS Dock disconnected", "info")
    }

    override fun onMessage(conn: WebSocket, message: String?) {
        // Handle incoming dock messages if any
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        val errorMsg = ex?.message ?: "Unknown error"
        onLog("WebSocket error: $errorMsg", "error")
        if (conn != null) {
            connectedClients.remove(conn)
            onClientCountChanged(connectedClients.size)
        }
    }

    fun broadcastEvent(event: String, data: Any) {
        val payload = gson.toJson(mapOf(
            "event" to event,
            "data" to data
        ))
        broadcastRaw(payload)
    }

    fun broadcastRaw(payload: String) {
        for (client in connectedClients) {
            if (client.isOpen) {
                try {
                    client.send(payload)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun appendLog(log: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(log)
            if (recentLogs.size > 200) {
                recentLogs.removeAt(0)
            }
        }
        broadcastEvent("system_log", log)
    }

    fun clearChat() {
        broadcastEvent("clear_chat", mapOf("action" to "clear"))
        onLog("Cleared OBS chat docks", "info")
    }

    fun getClientCount(): Int = connectedClients.size
}
