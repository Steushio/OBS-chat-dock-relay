package com.obs.chat.relay.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.obs.chat.relay.model.ChatMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

class YouTubeChatCapture(
    private val context: Context,
    private val onChatEvent: (ChatMessage) -> Unit,
    private val onLog: (String, String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private val gson = Gson()
    private var currentVideoId: String? = null
    var isCapturing: Boolean = false
        private set

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    companion object {
        fun extractVideoId(input: String): String {
            val clean = input.trim()
            if (clean.length == 11 && !clean.contains("/") && !clean.contains("?")) {
                return clean
            }
            val patterns = listOf(
                "v=([a-zA-Z0-9_-]{11})",
                "youtu\\.be/([a-zA-Z0-9_-]{11})",
                "youtube\\.com/live/([a-zA-Z0-9_-]{11})",
                "youtube\\.com/embed/([a-zA-Z0-9_-]{11})",
                "live_chat\\?.*v=([a-zA-Z0-9_-]{11})"
            )
            for (pattern in patterns) {
                val matcher = Pattern.compile(pattern).matcher(clean)
                if (matcher.find()) {
                    return matcher.group(1) ?: clean
                }
            }
            return clean
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun startCapture(videoIdOrUrl: String, lowResource: Boolean = false) {
        val videoId = extractVideoId(videoIdOrUrl)
        if (videoId.isEmpty()) {
            onLog("Cannot start YouTube chat capture: empty Video ID", "warning")
            return
        }

        currentVideoId = videoId
        isCapturing = true

        mainHandler.post {
            try {
                if (webView == null) {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = desktopUserAgent
                        settings.loadsImagesAutomatically = !lowResource
                        settings.mediaPlaybackRequiresUserGesture = false

                        addJavascriptInterface(AndroidBridge(), "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                onLog("YouTube Live Chat page loaded. Injecting observer...", "info")
                                injectCaptureScript(view)
                            }
                        }
                    }
                }

                val chatUrl = "https://www.youtube.com/live_chat?is_popout=1&v=$videoId"
                onLog("Starting YouTube chat capture for: $videoId", "info")
                webView?.loadUrl(chatUrl)
            } catch (e: Exception) {
                onLog("Failed to initialize YouTube WebView: ${e.message}", "error")
            }
        }
    }

    private fun injectCaptureScript(view: WebView?) {
        if (view == null) return
        try {
            val inputStream = context.assets.open("inject/youtubeChatInject.js")
            val script = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            view.evaluateJavascript(script) { result ->
                onLog("Chat observer script attached ($result)", "info")
            }
        } catch (e: Exception) {
            onLog("Error loading inject script: ${e.message}", "error")
        }
    }

    fun resumeTimers() {
        mainHandler.post {
            try {
                webView?.resumeTimers()
            } catch (e: Exception) {}
        }
    }

    fun stopCapture() {
        isCapturing = false
        currentVideoId = null
        mainHandler.post {
            try {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
                webView = null
                onLog("YouTube chat capture stopped", "info")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onChatEvent(jsonStr: String) {
            try {
                val message = gson.fromJson(jsonStr, ChatMessage::class.java)
                onChatEvent(message)
            } catch (e: Exception) {
                onLog("Error parsing chat event: ${e.message}", "error")
            }
        }

        @JavascriptInterface
        fun onObserverReady() {
            onLog("YouTube Live Chat DOM Observer successfully attached!", "success")
        }
    }
}
