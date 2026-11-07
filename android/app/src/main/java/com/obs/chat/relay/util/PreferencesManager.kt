package com.obs.chat.relay.util

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("obs_chat_relay_prefs", Context.MODE_PRIVATE)

    // Network & Ports
    var httpPort: Int
        get() = prefs.getInt(KEY_HTTP_PORT, 3000)
        set(value) = prefs.edit().putInt(KEY_HTTP_PORT, value).apply()

    var wsPort: Int
        get() = prefs.getInt(KEY_WS_PORT, 3001)
        set(value) = prefs.edit().putInt(KEY_WS_PORT, value).apply()

    // Remote Network Sharing (Over Mobile Data / Internet to PC)
    var remoteForwardingEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_ENABLED, value).apply()

    var remoteTargetHost: String
        get() {
            val h = prefs.getString(KEY_REMOTE_TARGET, "steushio-relay.loca.lt") ?: "steushio-relay.loca.lt"
            if (h.isBlank() || h.contains("trycloudflare.com")) {
                return "steushio-relay.loca.lt"
            }
            return h
        }
        set(value) = prefs.edit().putString(KEY_REMOTE_TARGET, value).apply()

    var cloudRoomCode: String
        get() {
            var r = prefs.getString(KEY_CLOUD_ROOM, "") ?: ""
            if (r.isBlank() || r == "default") {
                r = "live_" + (100000..999999).random()
                prefs.edit().putString(KEY_CLOUD_ROOM, r).apply()
            }
            return r
        }
        set(value) = prefs.edit().putString(KEY_CLOUD_ROOM, value).apply()

    // Streaming Platforms
    var youtubeVideoId: String
        get() = prefs.getString(KEY_YT_VIDEO_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YT_VIDEO_ID, value).apply()

    var kickUsername: String
        get() = prefs.getString(KEY_KICK_USERNAME, "steushio") ?: "steushio"
        set(value) = prefs.edit().putString(KEY_KICK_USERNAME, value).apply()

    var youtubeEnabled: Boolean
        get() = prefs.getBoolean(KEY_YT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_YT_ENABLED, value).apply()

    var kickEnabled: Boolean
        get() = prefs.getBoolean(KEY_KICK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_KICK_ENABLED, value).apply()

    // General & Performance
    var autoStartMonitoring: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var lowResourceMode: Boolean
        get() = prefs.getBoolean(KEY_LOW_RESOURCE, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_RESOURCE, value).apply()

    var clearOldChat: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_OLD_CHAT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLEAR_OLD_CHAT, value).apply()

    var clearOldChatLimit: Int
        get() = prefs.getInt(KEY_CLEAR_OLD_CHAT_LIMIT, 30)
        set(value) = prefs.edit().putInt(KEY_CLEAR_OLD_CHAT_LIMIT, value).apply()

    var clearOldChatTimeout: Int
        get() = prefs.getInt(KEY_CLEAR_OLD_CHAT_TIMEOUT, 120)
        set(value) = prefs.edit().putInt(KEY_CLEAR_OLD_CHAT_TIMEOUT, value).apply()

    // Audio & Alerts
    var chatSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHAT_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_SOUND, value).apply()

    var alertSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERT_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_SOUND, value).apply()

    var floatingOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_OVERLAY, value).apply()

    var keepScreenAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, value).apply()

    // AI Provider Selection: "nvidia" | "opencode_zen" | "gemini" | "openai" | "ollama"
    var aiProvider: String
        get() = prefs.getString(KEY_AI_PROVIDER, "nvidia") ?: "nvidia"
        set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value).apply()

    var ytPostingMode: String
        get() = prefs.getString("yt_posting_mode", "bot_account") ?: "bot_account"
        set(value) = prefs.edit().putString("yt_posting_mode", value).apply()

    // NVIDIA NIM (Free AI) Settings
    var nvidiaApiKey: String
        get() = prefs.getString(KEY_NVIDIA_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NVIDIA_API_KEY, value).apply()

    var nvidiaModel: String
        get() {
            val m = prefs.getString(KEY_NVIDIA_MODEL, "nvidia/nemotron-3.5-lightning-30b-a3b") ?: "nvidia/nemotron-3.5-lightning-30b-a3b"
            if (m.contains("llama-3.3-70b") || m.contains("llama-3.1-8b")) {
                return "nvidia/nemotron-3.5-lightning-30b-a3b"
            }
            return m
        }
        set(value) = prefs.edit().putString(KEY_NVIDIA_MODEL, value).apply()

    // OpenCode Zen Settings
    var opencodeZenApiKey: String
        get() = prefs.getString(KEY_OPENCODE_ZEN_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENCODE_ZEN_API_KEY, value).apply()

    var opencodeZenModel: String
        get() = prefs.getString(KEY_OPENCODE_ZEN_MODEL, "big-pickle") ?: "big-pickle"
        set(value) = prefs.edit().putString(KEY_OPENCODE_ZEN_MODEL, value).apply()

    var opencodeZenFreeOnly: Boolean
        get() = prefs.getBoolean(KEY_OPENCODE_ZEN_FREE_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_OPENCODE_ZEN_FREE_ONLY, value).apply()

    var opencodeZenAutoSwitch: Boolean
        get() = prefs.getBoolean(KEY_OPENCODE_ZEN_AUTO_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_OPENCODE_ZEN_AUTO_SWITCH, value).apply()

    // Google Gemini Settings
    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, "gemini-1.5-flash") ?: "gemini-1.5-flash"
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

    // OpenAI / ChatGPT Settings
    var openaiApiKey: String
        get() = prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()

    var openaiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value).apply()

    var openaiBaseUrl: String
        get() = prefs.getString(KEY_OPENAI_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString(KEY_OPENAI_BASE_URL, value).apply()

    // Local Ollama Settings
    var ollamaEnabled: Boolean
        get() = prefs.getBoolean(KEY_OLLAMA_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OLLAMA_ENABLED, value).apply()

    var ollamaUrl: String
        get() = prefs.getString(KEY_OLLAMA_URL, "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434"
        set(value) = prefs.edit().putString(KEY_OLLAMA_URL, value).apply()

    var ollamaModel: String
        get() = prefs.getString(KEY_OLLAMA_MODEL, "qwen2.5:1.5b") ?: "qwen2.5:1.5b"
        set(value) = prefs.edit().putString(KEY_OLLAMA_MODEL, value).apply()

    // Bot Identity & Behavior
    var botName: String
        get() = prefs.getString(KEY_BOT_NAME, "Cutie Bot") ?: "Cutie Bot"
        set(value) = prefs.edit().putString(KEY_BOT_NAME, value).apply()

    var ignoredUsers: String
        get() = prefs.getString(KEY_IGNORED_USERS, "cutie bot, cutiebot, nightbot, streamelements, moobot, streamlabs") ?: ""
        set(value) = prefs.edit().putString(KEY_IGNORED_USERS, value).apply()

    var streamerKnowledge: String
        get() = prefs.getString(KEY_STREAMER_KNOWLEDGE, DEFAULT_KNOWLEDGE) ?: DEFAULT_KNOWLEDGE
        set(value) = prefs.edit().putString(KEY_STREAMER_KNOWLEDGE, value).apply()

    var streamDescription: String
        get() = prefs.getString(KEY_STREAM_DESCRIPTION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STREAM_DESCRIPTION, value).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var autoRespond: String
        get() = prefs.getString(KEY_AUTO_RESPOND, "questions_and_mentions") ?: "questions_and_mentions"
        set(value) = prefs.edit().putString(KEY_AUTO_RESPOND, value).apply()

    var cooldownSeconds: Int
        get() = prefs.getInt(KEY_COOLDOWN, 8)
        set(value) = prefs.edit().putInt(KEY_COOLDOWN, value).apply()

    var firstWordsEnabled: Boolean
        get() = prefs.getBoolean(KEY_FIRST_WORDS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_WORDS_ENABLED, value).apply()

    var firstWordsMode: String
        get() = prefs.getString(KEY_FIRST_WORDS_MODE, "ai") ?: "ai" // "ai" | "template"
        set(value) = prefs.edit().putString(KEY_FIRST_WORDS_MODE, value).apply()

    var firstWordsTemplate: String
        get() = prefs.getString(KEY_FIRST_WORDS_TEMPLATE, "Welcome to the stream {username}! Thanks for chatting!") ?: ""
        set(value) = prefs.edit().putString(KEY_FIRST_WORDS_TEMPLATE, value).apply()

    var enableWebSearch: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_WEB_SEARCH, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_WEB_SEARCH, value).apply()

    var cpuThreads: Int
        get() = prefs.getInt(KEY_CPU_THREADS, 2)
        set(value) = prefs.edit().putInt(KEY_CPU_THREADS, value).apply()

    var customCommandsJson: String
        get() = prefs.getString(KEY_CUSTOM_COMMANDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_COMMANDS, value).apply()

    var timersJson: String
        get() = prefs.getString(KEY_TIMERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TIMERS, value).apply()

    companion object {
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_WS_PORT = "ws_port"
        private const val KEY_REMOTE_ENABLED = "remote_enabled"
        private const val KEY_REMOTE_TARGET = "remote_target"
        private const val KEY_CLOUD_ROOM = "cloud_room"
        private const val KEY_YT_VIDEO_ID = "yt_video_id"
        private const val KEY_KICK_USERNAME = "kick_username"
        private const val KEY_YT_ENABLED = "yt_enabled"
        private const val KEY_KICK_ENABLED = "kick_enabled"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_LOW_RESOURCE = "low_resource"
        private const val KEY_CLEAR_OLD_CHAT = "clear_old_chat"
        private const val KEY_CLEAR_OLD_CHAT_LIMIT = "clear_old_chat_limit"
        private const val KEY_CLEAR_OLD_CHAT_TIMEOUT = "clear_old_chat_timeout"
        private const val KEY_CHAT_SOUND = "chat_sound"
        private const val KEY_ALERT_SOUND = "alert_sound"
        private const val KEY_FLOATING_OVERLAY = "floating_overlay"
        private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"

        // Multi-Provider AI
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_NVIDIA_API_KEY = "nvidia_api_key"
        private const val KEY_NVIDIA_MODEL = "nvidia_model"
        private const val KEY_OPENCODE_ZEN_API_KEY = "opencode_zen_api_key"
        private const val KEY_OPENCODE_ZEN_MODEL = "opencode_zen_model"
        private const val KEY_OPENCODE_ZEN_FREE_ONLY = "opencode_zen_free_only"
        private const val KEY_OPENCODE_ZEN_AUTO_SWITCH = "opencode_zen_auto_switch"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"

        // Local Ollama
        private const val KEY_OLLAMA_ENABLED = "ollama_enabled"
        private const val KEY_OLLAMA_URL = "ollama_url"
        private const val KEY_OLLAMA_MODEL = "ollama_model"
        private const val KEY_BOT_NAME = "bot_name"
        private const val KEY_IGNORED_USERS = "ignored_users"
        private const val KEY_STREAMER_KNOWLEDGE = "streamer_knowledge"
        private const val KEY_STREAM_DESCRIPTION = "stream_description"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_AUTO_RESPOND = "auto_respond"
        private const val KEY_COOLDOWN = "cooldown"
        private const val KEY_FIRST_WORDS_ENABLED = "first_words_enabled"
        private const val KEY_FIRST_WORDS_MODE = "first_words_mode"
        private const val KEY_FIRST_WORDS_TEMPLATE = "first_words_template"
        private const val KEY_ENABLE_WEB_SEARCH = "enable_web_search"
        private const val KEY_CPU_THREADS = "cpu_threads"
        private const val KEY_CUSTOM_COMMANDS = "custom_commands"
        private const val KEY_TIMERS = "timers"

        const val DEFAULT_KNOWLEDGE = "Streamer Information, Gear & FAQ:\n- Broadcaster: Steushio (Cutie Bot)\n- Challenge: Plays with one hand / MMO mouse only!\n- Mouse: VXE R1se+\n- Discord: https://discord.gg/UBsenpp"
        const val DEFAULT_SYSTEM_PROMPT = "You are an intelligent, friendly live stream chat bot for a live gaming stream.\nRules:\n- Answer viewer questions accurately using stream knowledge and facts.\n- Welcome new viewers warmly.\n- Keep answers short, direct, and under 25 words.\n- NEVER sound like corporate customer support."
    }
}
