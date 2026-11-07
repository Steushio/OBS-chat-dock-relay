package com.obs.chat.relay.session

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class AccountType {
    YT_BROADCASTER,
    YT_BOT,
    KICK_BROADCASTER,
    KICK_BOT
}

class AccountSessionManager(
    private val context: Context,
    private val onLog: (String, String) -> Unit
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("obs_chat_relay_accounts", Context.MODE_PRIVATE)

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    // StateFlows for UI
    private val _ytBroadcasterLoggedIn = MutableStateFlow(prefs.getBoolean("yt_broadcaster_logged_in", false))
    val ytBroadcasterLoggedIn: StateFlow<Boolean> = _ytBroadcasterLoggedIn.asStateFlow()

    private val _ytBroadcasterName = MutableStateFlow(prefs.getString("yt_broadcaster_name", "") ?: "")
    val ytBroadcasterName: StateFlow<String> = _ytBroadcasterName.asStateFlow()

    private val _ytBroadcasterEmail = MutableStateFlow(prefs.getString("yt_broadcaster_email", "") ?: "")
    val ytBroadcasterEmail: StateFlow<String> = _ytBroadcasterEmail.asStateFlow()

    private val _ytBotLoggedIn = MutableStateFlow(prefs.getBoolean("yt_bot_logged_in", false))
    val ytBotLoggedIn: StateFlow<Boolean> = _ytBotLoggedIn.asStateFlow()

    private val _ytBotName = MutableStateFlow(prefs.getString("yt_bot_name", "") ?: "")
    val ytBotName: StateFlow<String> = _ytBotName.asStateFlow()

    init {
        val currentYtName = prefs.getString("yt_broadcaster_name", "") ?: ""
        if (currentYtName.contains("Creator Studio", true) || currentYtName.contains("YouTube Studio", true) || currentYtName.equals("YouTube Channel", true)) {
            val kickName = prefs.getString("kick_broadcaster_name", "") ?: ""
            val cleaned = if (kickName.isNotBlank() && !kickName.equals("Kick Streamer", true)) kickName else ""
            prefs.edit().putString("yt_broadcaster_name", cleaned).apply()
            _ytBroadcasterName.value = cleaned
        }
    }

    private val _ytBotEmail = MutableStateFlow(prefs.getString("yt_bot_email", "") ?: "")
    val ytBotEmail: StateFlow<String> = _ytBotEmail.asStateFlow()

    private val _kickBroadcasterLoggedIn = MutableStateFlow(prefs.getBoolean("kick_broadcaster_logged_in", false))
    val kickBroadcasterLoggedIn: StateFlow<Boolean> = _kickBroadcasterLoggedIn.asStateFlow()

    private val _kickBroadcasterName = MutableStateFlow(prefs.getString("kick_broadcaster_name", "") ?: "")
    val kickBroadcasterName: StateFlow<String> = _kickBroadcasterName.asStateFlow()

    private val _kickBroadcasterEmail = MutableStateFlow(prefs.getString("kick_broadcaster_email", "") ?: "")
    val kickBroadcasterEmail: StateFlow<String> = _kickBroadcasterEmail.asStateFlow()

    private val _kickBotLoggedIn = MutableStateFlow(prefs.getBoolean("kick_bot_logged_in", false))
    val kickBotLoggedIn: StateFlow<Boolean> = _kickBotLoggedIn.asStateFlow()

    private val _kickBotName = MutableStateFlow(prefs.getString("kick_bot_name", "") ?: "")
    val kickBotName: StateFlow<String> = _kickBotName.asStateFlow()

    private val _kickBotEmail = MutableStateFlow(prefs.getString("kick_bot_email", "") ?: "")
    val kickBotEmail: StateFlow<String> = _kickBotEmail.asStateFlow()

    // Mode preferences: "bot_account" or "broadcaster"
    var ytPostingMode: String
        get() = prefs.getString("yt_posting_mode", "bot_account") ?: "bot_account"
        set(value) {
            prefs.edit().putString("yt_posting_mode", value).apply()
            _ytPostingMode.value = value
            onLog("[Bot Mode] YouTube posting identity set to: $value", "info")
        }

    private val _ytPostingMode = MutableStateFlow(prefs.getString("yt_posting_mode", "bot_account") ?: "bot_account")
    val ytPostingModeFlow: StateFlow<String> = _ytPostingMode.asStateFlow()

    var kickPostingMode: String
        get() = prefs.getString("kick_posting_mode", "bot_account") ?: "bot_account"
        set(value) {
            prefs.edit().putString("kick_posting_mode", value).apply()
            _kickPostingMode.value = value
            onLog("[Bot Mode] Kick posting identity set to: $value", "info")
        }

    private val _kickPostingMode = MutableStateFlow(prefs.getString("kick_posting_mode", "bot_account") ?: "bot_account")
    val kickPostingModeFlow: StateFlow<String> = _kickPostingMode.asStateFlow()

    fun updateYtPostingMode(mode: String) {
        ytPostingMode = mode
    }

    fun updateKickPostingMode(mode: String) {
        kickPostingMode = mode
    }

    fun saveLogin(type: AccountType, username: String, email: String = "", token: String = "") {
        when (type) {
            AccountType.YT_BROADCASTER -> {
                prefs.edit()
                    .putBoolean("yt_broadcaster_logged_in", true)
                    .putString("yt_broadcaster_name", username)
                    .putString("yt_broadcaster_email", email)
                    .putString("yt_broadcaster_token", token)
                    .apply()
                _ytBroadcasterLoggedIn.value = true
                _ytBroadcasterName.value = username
                if (email.isNotBlank()) _ytBroadcasterEmail.value = email
                onLog("[Account] YouTube Broadcaster logged in as: $username ($email)", "success")
            }
            AccountType.YT_BOT -> {
                prefs.edit()
                    .putBoolean("yt_bot_logged_in", true)
                    .putString("yt_bot_name", username)
                    .putString("yt_bot_email", email)
                    .putString("yt_bot_token", token)
                    .apply()
                _ytBotLoggedIn.value = true
                _ytBotName.value = username
                if (email.isNotBlank()) _ytBotEmail.value = email
                onLog("[Account] YouTube Dedicated Bot logged in as: $username ($email)", "success")
            }
            AccountType.KICK_BROADCASTER -> {
                prefs.edit()
                    .putBoolean("kick_broadcaster_logged_in", true)
                    .putString("kick_broadcaster_name", username)
                    .putString("kick_broadcaster_email", email)
                    .putString("kick_broadcaster_token", token)
                    .apply()
                _kickBroadcasterLoggedIn.value = true
                _kickBroadcasterName.value = username
                if (email.isNotBlank()) _kickBroadcasterEmail.value = email
                onLog("[Account] Kick Broadcaster logged in as: $username", "success")
            }
            AccountType.KICK_BOT -> {
                prefs.edit()
                    .putBoolean("kick_bot_logged_in", true)
                    .putString("kick_bot_name", username)
                    .putString("kick_bot_email", email)
                    .putString("kick_bot_token", token)
                    .apply()
                _kickBotLoggedIn.value = true
                _kickBotName.value = username
                if (email.isNotBlank()) _kickBotEmail.value = email
                onLog("[Account] Kick Dedicated Bot logged in as: $username", "success")
            }
        }
    }

    private fun clearProfileSession(type: AccountType) {
        val profileName = when (type) {
            AccountType.YT_BROADCASTER -> "persist_youtube"
            AccountType.YT_BOT -> "persist_youtube_bot"
            AccountType.KICK_BROADCASTER -> "persist_kick"
            AccountType.KICK_BOT -> "persist_kick_bot"
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
            try {
                val profile = androidx.webkit.ProfileStore.getInstance().getProfile(profileName)
                profile?.cookieManager?.removeAllCookies(null)
                profile?.webStorage?.deleteAllData()
            } catch (e: Exception) {}
        }
    }

    fun logout(type: AccountType) {
        clearProfileSession(type)
        when (type) {
            AccountType.YT_BROADCASTER -> {
                prefs.edit().remove("yt_broadcaster_logged_in").remove("yt_broadcaster_name").remove("yt_broadcaster_email").remove("yt_broadcaster_token").apply()
                _ytBroadcasterLoggedIn.value = false
                _ytBroadcasterName.value = ""
                _ytBroadcasterEmail.value = ""
                onLog("[Account] YouTube Broadcaster logged out", "info")
            }
            AccountType.YT_BOT -> {
                prefs.edit().remove("yt_bot_logged_in").remove("yt_bot_name").remove("yt_bot_email").remove("yt_bot_token").apply()
                _ytBotLoggedIn.value = false
                _ytBotName.value = ""
                _ytBotEmail.value = ""
                onLog("[Account] YouTube Bot logged out", "info")
            }
            AccountType.KICK_BROADCASTER -> {
                prefs.edit().remove("kick_broadcaster_logged_in").remove("kick_broadcaster_name").remove("kick_broadcaster_email").remove("kick_broadcaster_token").apply()
                _kickBroadcasterLoggedIn.value = false
                _kickBroadcasterName.value = ""
                _kickBroadcasterEmail.value = ""
                onLog("[Account] Kick Broadcaster logged out", "info")
            }
            AccountType.KICK_BOT -> {
                prefs.edit().remove("kick_bot_logged_in").remove("kick_bot_name").remove("kick_bot_email").remove("kick_bot_token").apply()
                _kickBotLoggedIn.value = false
                _kickBotName.value = ""
                _kickBotEmail.value = ""
                onLog("[Account] Kick Bot logged out", "info")
            }
        }
    }

    fun sendKickChatMessage(chatroomId: String, username: String, messageText: String, asBot: Boolean) {
        scope.launch {
            val tokenKey = if (asBot) "kick_bot_token" else "kick_broadcaster_token"
            val token = prefs.getString(tokenKey, "") ?: ""
            if (token.isBlank()) {
                onLog("[Kick Post] Cannot post to chat: No auth token saved for ${if (asBot) "Bot" else "Broadcaster"}.", "warning")
                return@launch
            }

            try {
                val url = "https://kick.com/api/v2/messages/send/$chatroomId"
                val json = JsonObject().apply {
                    addProperty("content", messageText)
                    addProperty("type", "message")
                }

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(body)
                    .build()

                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    onLog("[Kick Post] Message posted to chat as ${if (asBot) "Bot" else "Broadcaster"}!", "success")
                } else {
                    onLog("[Kick Post] API error HTTP ${res.code}: ${res.message}", "error")
                }
            } catch (e: Exception) {
                onLog("[Kick Post] Error sending message: ${e.message}", "error")
            }
        }
    }

    fun postBotReplyToChat(
        platform: String,
        videoId: String?,
        chatroomId: String?,
        replyText: String,
        ytSender: com.obs.chat.relay.capture.YouTubeBotChatSender? = null
    ) {
        if (platform == "youtube" && !videoId.isNullOrBlank()) {
            ytSender?.postMessage(videoId, replyText)
        } else if (platform == "kick" && !chatroomId.isNullOrBlank()) {
            val asBot = kickPostingMode == "bot_account" && _kickBotLoggedIn.value
            sendKickChatMessage(chatroomId, if (asBot) _kickBotName.value else _kickBroadcasterName.value, replyText, asBot)
        }
    }
}
