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
            addProperty("autoStart", prefs.autoStartMonitoring)
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

            // Cross-Platform interoperability with PC Desktop App (nested ollamaBotSettings)
            val botObj = JsonObject().apply {
                addProperty("enabled", prefs.ollamaEnabled)
                addProperty("ollamaUrl", prefs.ollamaUrl)
                addProperty("model", prefs.ollamaModel)
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
                addProperty("enableYouTube", prefs.youtubeEnabled)
                addProperty("enableKick", prefs.kickEnabled)

                try {
                    val cmdArr = JsonParser.parseString(prefs.customCommandsJson)
                    if (cmdArr.isJsonArray) add("commands", cmdArr)
                } catch (e: Exception) {}

                try {
                    val tmrArr = JsonParser.parseString(prefs.timersJson)
                    if (tmrArr.isJsonArray) add("timers", tmrArr)
                } catch (e: Exception) {}
            }
            add("ollamaBotSettings", botObj)
        }

        return gson.toJson(root)
    }

    fun importFromJsonString(jsonString: String): Boolean {
        try {
            val root = JsonParser.parseString(jsonString).asJsonObject

            // Support both PC app nested "ollamaBotSettings" and Android root properties
            val botObj = if (root.has("ollamaBotSettings") && root.get("ollamaBotSettings").isJsonObject) {
                root.getAsJsonObject("ollamaBotSettings")
            } else {
                root
            }

            // 1. Custom Commands (PC: ollamaBotSettings.commands or commands; Android: customCommandsJson)
            val commandsElem = when {
                botObj.has("commands") -> botObj.get("commands")
                root.has("commands") -> root.get("commands")
                root.has("customCommandsJson") -> root.get("customCommandsJson")
                else -> null
            }
            if (commandsElem != null) {
                prefs.customCommandsJson = if (commandsElem.isJsonArray) {
                    commandsElem.asJsonArray.toString()
                } else {
                    commandsElem.asString
                }
            }

            // 2. Timers (PC: ollamaBotSettings.timers or timers; Android: timersJson)
            val timersElem = when {
                botObj.has("timers") -> botObj.get("timers")
                root.has("timers") -> root.get("timers")
                root.has("timersJson") -> root.get("timersJson")
                else -> null
            }
            if (timersElem != null) {
                prefs.timersJson = if (timersElem.isJsonArray) {
                    timersElem.asJsonArray.toString()
                } else {
                    timersElem.asString
                }
            }

            // 3. Network & Ports
            if (root.has("httpPort")) prefs.httpPort = root.get("httpPort").asInt
            if (root.has("wsPort")) prefs.wsPort = root.get("wsPort").asInt
            if (root.has("remoteForwardingEnabled")) prefs.remoteForwardingEnabled = root.get("remoteForwardingEnabled").asBoolean
            if (root.has("remoteTargetHost")) prefs.remoteTargetHost = root.get("remoteTargetHost").asString
            if (root.has("cloudRoomCode")) prefs.cloudRoomCode = root.get("cloudRoomCode").asString

            // 4. Streaming Platforms
            if (root.has("youtubeVideoId")) prefs.youtubeVideoId = root.get("youtubeVideoId").asString
            else if (root.has("channelId")) prefs.youtubeVideoId = root.get("channelId").asString

            if (root.has("kickUsername")) prefs.kickUsername = root.get("kickUsername").asString
            if (root.has("youtubeEnabled")) prefs.youtubeEnabled = root.get("youtubeEnabled").asBoolean
            else if (botObj.has("enableYouTube")) prefs.youtubeEnabled = botObj.get("enableYouTube").asBoolean

            if (root.has("kickEnabled")) prefs.kickEnabled = root.get("kickEnabled").asBoolean
            else if (botObj.has("enableKick")) prefs.kickEnabled = botObj.get("enableKick").asBoolean

            // 5. Behavior & Audio
            if (root.has("autoStartMonitoring")) prefs.autoStartMonitoring = root.get("autoStartMonitoring").asBoolean
            else if (root.has("autoStart")) prefs.autoStartMonitoring = root.get("autoStart").asBoolean

            if (root.has("lowResourceMode")) prefs.lowResourceMode = root.get("lowResourceMode").asBoolean
            if (root.has("clearOldChat")) prefs.clearOldChat = root.get("clearOldChat").asBoolean
            if (root.has("clearOldChatLimit")) prefs.clearOldChatLimit = root.get("clearOldChatLimit").asInt
            if (root.has("clearOldChatTimeout")) prefs.clearOldChatTimeout = root.get("clearOldChatTimeout").asInt
            if (root.has("chatSoundEnabled")) prefs.chatSoundEnabled = root.get("chatSoundEnabled").asBoolean
            if (root.has("alertSoundEnabled")) prefs.alertSoundEnabled = root.get("alertSoundEnabled").asBoolean
            if (root.has("keepScreenAwake")) prefs.keepScreenAwake = root.get("keepScreenAwake").asBoolean
            if (root.has("floatingOverlayEnabled")) prefs.floatingOverlayEnabled = root.get("floatingOverlayEnabled").asBoolean

            // 6. AI Bot Core & Identity Settings (Check botObj first, fallback to root)
            val botNameVal = botObj.get("botName") ?: root.get("botName")
            if (botNameVal != null && !botNameVal.isJsonNull) prefs.botName = botNameVal.asString

            val ignoredUsersVal = botObj.get("ignoredUsers") ?: root.get("ignoredUsers")
            if (ignoredUsersVal != null && !ignoredUsersVal.isJsonNull) prefs.ignoredUsers = ignoredUsersVal.asString

            val streamerKnowledgeVal = botObj.get("streamerKnowledge") ?: root.get("streamerKnowledge")
            if (streamerKnowledgeVal != null && !streamerKnowledgeVal.isJsonNull) prefs.streamerKnowledge = streamerKnowledgeVal.asString

            val streamDescVal = botObj.get("streamDescription") ?: root.get("streamDescription")
            if (streamDescVal != null && !streamDescVal.isJsonNull) prefs.streamDescription = streamDescVal.asString

            val systemPromptVal = botObj.get("systemPrompt") ?: root.get("systemPrompt")
            if (systemPromptVal != null && !systemPromptVal.isJsonNull) prefs.systemPrompt = systemPromptVal.asString

            val autoRespondVal = botObj.get("autoRespond") ?: root.get("autoRespond")
            if (autoRespondVal != null && !autoRespondVal.isJsonNull) prefs.autoRespond = autoRespondVal.asString

            val cooldownVal = botObj.get("cooldownSeconds") ?: root.get("cooldownSeconds")
            if (cooldownVal != null && !cooldownVal.isJsonNull) prefs.cooldownSeconds = cooldownVal.asInt

            val firstWordsEnabledVal = botObj.get("firstWordsEnabled") ?: root.get("firstWordsEnabled")
            if (firstWordsEnabledVal != null && !firstWordsEnabledVal.isJsonNull) prefs.firstWordsEnabled = firstWordsEnabledVal.asBoolean

            val firstWordsModeVal = botObj.get("firstWordsMode") ?: root.get("firstWordsMode")
            if (firstWordsModeVal != null && !firstWordsModeVal.isJsonNull) prefs.firstWordsMode = firstWordsModeVal.asString

            val firstWordsTmplVal = botObj.get("firstWordsTemplate") ?: root.get("firstWordsTemplate")
            if (firstWordsTmplVal != null && !firstWordsTmplVal.isJsonNull) prefs.firstWordsTemplate = firstWordsTmplVal.asString

            val webSearchVal = botObj.get("enableWebSearch") ?: root.get("enableWebSearch")
            if (webSearchVal != null && !webSearchVal.isJsonNull) prefs.enableWebSearch = webSearchVal.asBoolean

            val ollamaEnabledVal = botObj.get("enabled") ?: botObj.get("ollamaEnabled") ?: root.get("ollamaEnabled")
            if (ollamaEnabledVal != null && !ollamaEnabledVal.isJsonNull) prefs.ollamaEnabled = ollamaEnabledVal.asBoolean

            val ollamaUrlVal = botObj.get("ollamaUrl") ?: root.get("ollamaUrl")
            if (ollamaUrlVal != null && !ollamaUrlVal.isJsonNull) prefs.ollamaUrl = ollamaUrlVal.asString

            val ollamaModelVal = botObj.get("model") ?: botObj.get("ollamaModel") ?: root.get("ollamaModel")
            if (ollamaModelVal != null && !ollamaModelVal.isJsonNull) prefs.ollamaModel = ollamaModelVal.asString

            val threadsVal = botObj.get("cpuThreads") ?: root.get("cpuThreads")
            if (threadsVal != null && !threadsVal.isJsonNull) prefs.cpuThreads = threadsVal.asInt

            // Cloud AI Provider Keys (if provided in root)
            if (root.has("aiProvider")) prefs.aiProvider = root.get("aiProvider").asString
            if (root.has("nvidiaApiKey")) prefs.nvidiaApiKey = root.get("nvidiaApiKey").asString
            if (root.has("nvidiaModel")) prefs.nvidiaModel = root.get("nvidiaModel").asString
            if (root.has("geminiApiKey")) prefs.geminiApiKey = root.get("geminiApiKey").asString
            if (root.has("geminiModel")) prefs.geminiModel = root.get("geminiModel").asString
            if (root.has("openaiApiKey")) prefs.openaiApiKey = root.get("openaiApiKey").asString
            if (root.has("openaiModel")) prefs.openaiModel = root.get("openaiModel").asString
            if (root.has("openaiBaseUrl")) prefs.openaiBaseUrl = root.get("openaiBaseUrl").asString

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
