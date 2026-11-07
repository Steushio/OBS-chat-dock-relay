package com.obs.chat.relay.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class SettingsBackupManager(private val prefs: PreferencesManager) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun exportToJsonString(): String {
        val root = JsonObject().apply {
            addProperty("exportedAt", System.currentTimeMillis())
            addProperty("version", "1.0.0")

            // Network & Ports
            addProperty("httpPort", prefs.httpPort)
            addProperty("wsPort", prefs.wsPort)
            addProperty("remoteForwardingEnabled", prefs.remoteForwardingEnabled)
            addProperty("remoteTargetHost", prefs.remoteTargetHost)
            addProperty("cloudRoomCode", prefs.cloudRoomCode)

            // Streaming Platforms
            addProperty("youtubeVideoId", prefs.youtubeVideoId)
            addProperty("kickUsername", prefs.kickUsername)
            addProperty("youtubeEnabled", prefs.youtubeEnabled)
            addProperty("kickEnabled", prefs.kickEnabled)

            // Behavior & Audio
            addProperty("autoStartMonitoring", prefs.autoStartMonitoring)
            addProperty("lowResourceMode", prefs.lowResourceMode)
            addProperty("clearOldChat", prefs.clearOldChat)
            addProperty("clearOldChatLimit", prefs.clearOldChatLimit)
            addProperty("clearOldChatTimeout", prefs.clearOldChatTimeout)
            addProperty("chatSoundEnabled", prefs.chatSoundEnabled)
            addProperty("alertSoundEnabled", prefs.alertSoundEnabled)
            addProperty("keepScreenAwake", prefs.keepScreenAwake)
            addProperty("floatingOverlayEnabled", prefs.floatingOverlayEnabled)

            // AI Bot Settings
            addProperty("aiProvider", prefs.aiProvider)
            addProperty("nvidiaApiKey", prefs.nvidiaApiKey)
            addProperty("nvidiaModel", prefs.nvidiaModel)
            addProperty("geminiApiKey", prefs.geminiApiKey)
            addProperty("geminiModel", prefs.geminiModel)
            addProperty("openaiApiKey", prefs.openaiApiKey)
            addProperty("openaiModel", prefs.openaiModel)
            addProperty("openaiBaseUrl", prefs.openaiBaseUrl)
            addProperty("ollamaEnabled", prefs.ollamaEnabled)
            addProperty("ollamaUrl", prefs.ollamaUrl)
            addProperty("ollamaModel", prefs.ollamaModel)
            addProperty("botName", prefs.botName)
            addProperty("ignoredUsers", prefs.ignoredUsers)
            addProperty("streamerKnowledge", prefs.streamerKnowledge)
            addProperty("streamDescription", prefs.streamDescription)
            addProperty("systemPrompt", prefs.systemPrompt)
            addProperty("autoRespond", prefs.autoRespond)
            addProperty("cooldownSeconds", prefs.cooldownSeconds)
            addProperty("firstWordsEnabled", prefs.firstWordsEnabled)
            addProperty("firstWordsMode", prefs.firstWordsMode)
            addProperty("firstWordsTemplate", prefs.firstWordsTemplate)
            addProperty("enableWebSearch", prefs.enableWebSearch)
            addProperty("cpuThreads", prefs.cpuThreads)
            addProperty("customCommandsJson", prefs.customCommandsJson)
            addProperty("timersJson", prefs.timersJson)
        }

        return gson.toJson(root)
    }

    fun importFromJsonString(jsonString: String): Boolean {
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject

            // Network & Ports
            if (root.has("httpPort")) prefs.httpPort = root.get("httpPort").asInt
            if (root.has("wsPort")) prefs.wsPort = root.get("wsPort").asInt
            if (root.has("remoteForwardingEnabled")) prefs.remoteForwardingEnabled = root.get("remoteForwardingEnabled").asBoolean
            if (root.has("remoteTargetHost")) prefs.remoteTargetHost = root.get("remoteTargetHost").asString
            if (root.has("cloudRoomCode")) prefs.cloudRoomCode = root.get("cloudRoomCode").asString

            // Streaming Platforms
            if (root.has("youtubeVideoId")) prefs.youtubeVideoId = root.get("youtubeVideoId").asString
            if (root.has("kickUsername")) prefs.kickUsername = root.get("kickUsername").asString
            if (root.has("youtubeEnabled")) prefs.youtubeEnabled = root.get("youtubeEnabled").asBoolean
            if (root.has("kickEnabled")) prefs.kickEnabled = root.get("kickEnabled").asBoolean

            // Behavior & Audio
            if (root.has("autoStartMonitoring")) prefs.autoStartMonitoring = root.get("autoStartMonitoring").asBoolean
            if (root.has("lowResourceMode")) prefs.lowResourceMode = root.get("lowResourceMode").asBoolean
            if (root.has("clearOldChat")) prefs.clearOldChat = root.get("clearOldChat").asBoolean
            if (root.has("clearOldChatLimit")) prefs.clearOldChatLimit = root.get("clearOldChatLimit").asInt
            if (root.has("clearOldChatTimeout")) prefs.clearOldChatTimeout = root.get("clearOldChatTimeout").asInt
            if (root.has("chatSoundEnabled")) prefs.chatSoundEnabled = root.get("chatSoundEnabled").asBoolean
            if (root.has("alertSoundEnabled")) prefs.alertSoundEnabled = root.get("alertSoundEnabled").asBoolean
            if (root.has("keepScreenAwake")) prefs.keepScreenAwake = root.get("keepScreenAwake").asBoolean
            if (root.has("floatingOverlayEnabled")) prefs.floatingOverlayEnabled = root.get("floatingOverlayEnabled").asBoolean

            // AI Bot Settings
            if (root.has("aiProvider")) prefs.aiProvider = root.get("aiProvider").asString
            if (root.has("nvidiaApiKey")) prefs.nvidiaApiKey = root.get("nvidiaApiKey").asString
            if (root.has("nvidiaModel")) prefs.nvidiaModel = root.get("nvidiaModel").asString
            if (root.has("geminiApiKey")) prefs.geminiApiKey = root.get("geminiApiKey").asString
            if (root.has("geminiModel")) prefs.geminiModel = root.get("geminiModel").asString
            if (root.has("openaiApiKey")) prefs.openaiApiKey = root.get("openaiApiKey").asString
            if (root.has("openaiModel")) prefs.openaiModel = root.get("openaiModel").asString
            if (root.has("openaiBaseUrl")) prefs.openaiBaseUrl = root.get("openaiBaseUrl").asString
            if (root.has("ollamaEnabled")) prefs.ollamaEnabled = root.get("ollamaEnabled").asBoolean
            if (root.has("ollamaUrl")) prefs.ollamaUrl = root.get("ollamaUrl").asString
            if (root.has("ollamaModel")) prefs.ollamaModel = root.get("ollamaModel").asString
            if (root.has("botName")) prefs.botName = root.get("botName").asString
            if (root.has("ignoredUsers")) prefs.ignoredUsers = root.get("ignoredUsers").asString
            if (root.has("streamerKnowledge")) prefs.streamerKnowledge = root.get("streamerKnowledge").asString
            if (root.has("streamDescription")) prefs.streamDescription = root.get("streamDescription").asString
            if (root.has("systemPrompt")) prefs.systemPrompt = root.get("systemPrompt").asString
            if (root.has("autoRespond")) prefs.autoRespond = root.get("autoRespond").asString
            if (root.has("cooldownSeconds")) prefs.cooldownSeconds = root.get("cooldownSeconds").asInt
            if (root.has("firstWordsEnabled")) prefs.firstWordsEnabled = root.get("firstWordsEnabled").asBoolean
            if (root.has("firstWordsMode")) prefs.firstWordsMode = root.get("firstWordsMode").asString
            if (root.has("firstWordsTemplate")) prefs.firstWordsTemplate = root.get("firstWordsTemplate").asString
            if (root.has("enableWebSearch")) prefs.enableWebSearch = root.get("enableWebSearch").asBoolean
            if (root.has("cpuThreads")) prefs.cpuThreads = root.get("cpuThreads").asInt
            if (root.has("customCommandsJson")) prefs.customCommandsJson = root.get("customCommandsJson").asString
            if (root.has("timersJson")) prefs.timersJson = root.get("timersJson").asString

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun shareBackupFile(context: Context) {
        try {
            val json = exportToJsonString()
            val file = File(context.cacheDir, "obs-chat-relay-settings.json")
            file.writeText(json)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export OBS Chat Relay Settings"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun importFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val json = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            importFromJsonString(json)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
