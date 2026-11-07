package com.obs.chat.relay.server

import android.content.Context
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class OverlayHttpServer(
    private val context: Context,
    port: Int,
    private val wsPort: Int,
    private val onClearChatRequested: () -> Unit,
    private val onToggleClearChatRequested: () -> Boolean,
    private val onToggleLowResourceRequested: () -> Boolean,
    private val getSettings: () -> Map<String, Any>,
    private val onLog: (String, String) -> Unit
) : NanoHTTPD("0.0.0.0", port) {

    private val gson = Gson()
    var getRecentChats: (() -> List<Any>)? = null
    var getRecentAlerts: (() -> List<Any>)? = null
    var getStreamState: (() -> Any?)? = null
    var getKickState: (() -> Any?)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(response)
            return response
        }

        val response = when (uri) {
            "/api/recent-messages" -> {
                val data = mapOf(
                    "recentChats" to (getRecentChats?.invoke() ?: emptyList<Any>()),
                    "recentAlerts" to (getRecentAlerts?.invoke() ?: emptyList<Any>()),
                    "stream" to getStreamState?.invoke(),
                    "kickStream" to getKickState?.invoke()
                )
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(data))
            }

            "/", "/status" -> {
                val settings = getSettings()
                val statusMap = mapOf(
                    "status" to "running",
                    "service" to "OBS Chat Relay Android HTTP Server",
                    "wsPort" to wsPort,
                    "lowResourceMode" to (settings["lowResourceMode"] ?: false),
                    "clearOldChat" to (settings["clearOldChat"] ?: false),
                    "clearOldChatLimit" to (settings["clearOldChatLimit"] ?: 30),
                    "clearOldChatTimeout" to (settings["clearOldChatTimeout"] ?: 120)
                )
                newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(statusMap))
            }

            "/clear-chat" -> {
                onClearChatRequested()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    gson.toJson(mapOf("success" to true, "message" to "OBS chat docks cleared"))
                )
            }

            "/toggle-clear-chat" -> {
                val newState = onToggleClearChatRequested()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    gson.toJson(mapOf("success" to true, "clearOldChat" to newState))
                )
            }

            "/toggle-low-resource" -> {
                val newState = onToggleLowResourceRequested()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    gson.toJson(mapOf("success" to true, "lowResourceMode" to newState))
                )
            }

            "/chatdock", "/chatdock.html" -> serveAsset("overlays/chatdock.html", "text/html")
            "/alertdock", "/alertdock.html" -> serveAsset("overlays/alertdock.html", "text/html")
            "/eventdock", "/eventdock.html" -> serveAsset("overlays/eventdock.html", "text/html")
            "/tunnel-dock", "/tunnel-dock.html" -> serveAsset("overlays/tunnel-dock.html", "text/html")
            "/favicon.png" -> serveAsset("overlays/favicon.png", "image/png")

            else -> {
                // Check if file exists under overlays/
                val assetPath = "overlays" + (if (uri.startsWith("/")) uri else "/$uri")
                val mimeType = getMimeType(assetPath)
                serveAsset(assetPath, mimeType)
            }
        }

        addCorsHeaders(response)
        return response
    }

    private fun serveAsset(path: String, mimeType: String): Response {
        return try {
            val stream: InputStream = context.assets.open(path)
            newChunkedResponse(Response.Status.OK, mimeType, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found: $path")
        }
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".wav") -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept")
    }

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        onLog("HTTP Overlay Server started on http://0.0.0.0:$listeningPort", "success")
    }
}
