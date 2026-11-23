package com.obs.chat.relay.valorant

import com.google.gson.JsonParser
import com.obs.chat.relay.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ValorantRankResult(
    val success: Boolean,
    val name: String,
    val tag: String,
    val currentRank: String,
    val rr: Int = 0,
    val elo: Int = 0,
    val lastChange: Int = 0,
    val formattedMessage: String,
    val rawJson: String = ""
)

class ValorantRankService(
    private val prefs: PreferencesManager,
    private val onLog: (String, String) -> Unit = { _, _ -> }
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 60-second in-memory cache to stay well within Henrik API rate limits
    private var cachedResult: ValorantRankResult? = null
    private var lastFetchTime = 0L

    companion object {
        private val RANK_QUESTION_PATTERN = Pattern.compile(
            """(?i)\b(what('s|\s+is)?\s+(your|ur|the)?\s*rank|current\s*rank|which\s*rank|valo(rant)?\s*rank|rank\s*kya\s*(hai|hey)|kya\s*rank\s*(hai|hey)|rank\s*batao|rank\s*plz|rank\s*please)\b"""
        )
    }

    fun isRankQuery(message: String): Boolean {
        val trimmed = message.trim().lowercase()
        // 1. Direct bot commands
        if (trimmed == "!rank" || trimmed.startsWith("!rank ") ||
            trimmed == "!val" || trimmed.startsWith("!val ") ||
            trimmed == "!valo" || trimmed.startsWith("!valo ") ||
            trimmed == "!valorant" || trimmed.startsWith("!valorant ") ||
            trimmed == "!mmr" || trimmed.startsWith("!mmr ") ||
            trimmed == "!tier" || trimmed.startsWith("!tier ")) {
            return true
        }

        // 2. Direct short queries (e.g. "rank?", "valo rank?", "rank")
        if (trimmed == "rank" || trimmed == "rank?" ||
            trimmed == "valo rank" || trimmed == "valo rank?" ||
            trimmed == "valorant rank" || trimmed == "valorant rank?") {
            return true
        }

        // 3. Question regex match
        return RANK_QUESTION_PATTERN.matcher(trimmed).find()
    }

    suspend fun getRankInfo(forceRefresh: Boolean = false): ValorantRankResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedResult != null && (now - lastFetchTime) < 60_000L) {
            return@withContext cachedResult!!
        }

        val name = prefs.valorantName.trim().ifEmpty { "Steushio" }
        val tag = prefs.valorantTag.trim().ifEmpty { "IND" }
        val region = prefs.valorantRegion.trim().lowercase().ifEmpty { "ap" }
        val apiKey = prefs.henrikApiKey.trim()

        onLog("[Valorant] Fetching live MMR from HenrikDev for $name#$tag ($region)...", "info")

        // Try v2 MMR first, fallback to v1
        val v2Url = "https://api.henrikdev.xyz/valorant/v2/mmr/$region/${encodeUri(name)}/${encodeUri(tag)}"
        val v1Url = "https://api.henrikdev.xyz/valorant/v1/mmr/$region/${encodeUri(name)}/${encodeUri(tag)}"

        var result = queryHenrik(v2Url, apiKey, isV2 = true, name = name, tag = tag, region = region)
        if (!result.success && !result.formattedMessage.contains("Unauthorized")) {
            // Try v1 fallback
            result = queryHenrik(v1Url, apiKey, isV2 = false, name = name, tag = tag, region = region)
        }

        if (result.success) {
            cachedResult = result
            lastFetchTime = System.currentTimeMillis()
            onLog("[Valorant] Successfully retrieved rank: ${result.currentRank} (${result.rr} RR)", "success")
        } else {
            onLog("[Valorant] Henrik API response: ${result.formattedMessage}", "warning")
        }

        result
    }

    private fun queryHenrik(
        url: String,
        apiKey: String,
        isV2: Boolean,
        name: String,
        tag: String,
        region: String
    ): ValorantRankResult {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "OBS-Chat-Dock-Relay/1.0")

            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", apiKey)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (code == 401 || code == 403) {
                return ValorantRankResult(
                    success = false,
                    name = name,
                    tag = tag,
                    currentRank = "Ascendant Goal",
                    formattedMessage = "🎮 $name#$tag: Challenging for Ascendant (one hand MMO mouse)! [Henrik API Key required in app settings for live RR]"
                )
            }

            if (code == 404) {
                return ValorantRankResult(
                    success = false,
                    name = name,
                    tag = tag,
                    currentRank = "Unknown",
                    formattedMessage = "🎮 Player $name#$tag not found on region '$region'. Please verify Riot ID in Bot Settings!"
                )
            }

            if (response.isSuccessful && body.isNotBlank()) {
                val json = JsonParser.parseString(body).asJsonObject
                val dataObj = json.getAsJsonObject("data")

                if (isV2 && dataObj != null) {
                    val currentData = dataObj.getAsJsonObject("current_data")
                    if (currentData != null) {
                        val rankPatched = currentData.get("currenttierpatched")?.asString ?: "Unrated"
                        val rr = currentData.get("ranking_in_tier")?.asInt ?: 0
                        val elo = currentData.get("elo")?.asInt ?: 0
                        val change = currentData.get("mmr_change_to_last_game")?.asInt ?: 0
                        val changeStr = if (change >= 0) "+$change" else "$change"

                        val formatted = "🎮 $name#$tag: $rankPatched ($rr RR | ELO $elo) [$changeStr last match]"
                        return ValorantRankResult(
                            success = true,
                            name = name,
                            tag = tag,
                            currentRank = rankPatched,
                            rr = rr,
                            elo = elo,
                            lastChange = change,
                            formattedMessage = formatted,
                            rawJson = body
                        )
                    }
                } else if (dataObj != null) {
                    // v1 response
                    val rankPatched = dataObj.get("currenttierpatched")?.asString ?: "Unrated"
                    val rr = dataObj.get("ranking_in_tier")?.asInt ?: 0
                    val elo = dataObj.get("elo")?.asInt ?: 0
                    val change = dataObj.get("mmr_change_to_last_game")?.asInt ?: 0
                    val changeStr = if (change >= 0) "+$change" else "$change"

                    val formatted = "🎮 $name#$tag: $rankPatched ($rr RR | ELO $elo) [$changeStr last match]"
                    return ValorantRankResult(
                        success = true,
                        name = name,
                        tag = tag,
                        currentRank = rankPatched,
                        rr = rr,
                        elo = elo,
                        lastChange = change,
                        formattedMessage = formatted,
                        rawJson = body
                    )
                }
            }

            return ValorantRankResult(
                success = false,
                name = name,
                tag = tag,
                currentRank = "Ascendant Challenge",
                formattedMessage = "🎮 $name is doing a one-handed MMO mouse challenge to reach Ascendant in Valorant!"
            )
        } catch (e: Exception) {
            return ValorantRankResult(
                success = false,
                name = name,
                tag = tag,
                currentRank = "Ascendant Challenge",
                formattedMessage = "🎮 $name is challenging for Ascendant in Valorant (1-hand MMO mouse only)!"
            )
        }
    }

    private fun encodeUri(s: String): String {
        return try {
            java.net.URLEncoder.encode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }
    }
}
