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
    val puuid: String = "",
    val currentRank: String,
    val rr: Int = 0,
    val elo: Int = 0,
    val lastChange: Int = 0,
    val peakRank: String = "",
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
        val puuid = prefs.valorantPuuid.trim()
        val region = prefs.valorantRegion.trim().lowercase().ifEmpty { "ap" }
        val apiKey = prefs.henrikApiKey.trim()

        val logTarget = if (puuid.isNotEmpty()) "PUUID ${puuid.take(8)}... ($region)" else "$name#$tag ($region)"
        onLog("[Valorant] Fetching live MMR from HenrikDev for $logTarget...", "info")

        var result: ValorantRankResult? = null

        // 1. If PUUID is available, try PUUID endpoints first (permanent, unaffected by Riot ID changes)
        if (puuid.isNotEmpty()) {
            val v3PuuidUrl = "https://api.henrikdev.xyz/valorant/v3/by-puuid/mmr/$region/pc/$puuid"
            val v2PuuidUrl = "https://api.henrikdev.xyz/valorant/v2/by-puuid/mmr/$region/$puuid"

            result = queryHenrik(v3PuuidUrl, apiKey, name = name, tag = tag, region = region, puuid = puuid)
            if (!result.success && !result.formattedMessage.contains("Unauthorized")) {
                result = queryHenrik(v2PuuidUrl, apiKey, name = name, tag = tag, region = region, puuid = puuid)
            }
        }

        // 2. If PUUID was empty or PUUID queries failed, query by Name and Tag
        if (result == null || (!result.success && !result.formattedMessage.contains("Unauthorized"))) {
            val v3Url = "https://api.henrikdev.xyz/valorant/v3/mmr/$region/pc/${encodeUri(name)}/${encodeUri(tag)}"
            val v2Url = "https://api.henrikdev.xyz/valorant/v2/mmr/$region/${encodeUri(name)}/${encodeUri(tag)}"
            val v1Url = "https://api.henrikdev.xyz/valorant/v1/mmr/$region/${encodeUri(name)}/${encodeUri(tag)}"

            var nameResult = queryHenrik(v3Url, apiKey, name = name, tag = tag, region = region, puuid = puuid)
            if (!nameResult.success && !nameResult.formattedMessage.contains("Unauthorized")) {
                nameResult = queryHenrik(v2Url, apiKey, name = name, tag = tag, region = region, puuid = puuid)
            }
            if (!nameResult.success && !nameResult.formattedMessage.contains("Unauthorized")) {
                nameResult = queryHenrik(v1Url, apiKey, name = name, tag = tag, region = region, puuid = puuid)
            }
            result = nameResult
        }

        // 3. If PUUID was unknown but we succeeded, auto-save the resolved PUUID
        if (result.success) {
            if (result.puuid.isNotEmpty() && prefs.valorantPuuid.isBlank()) {
                prefs.valorantPuuid = result.puuid
                onLog("[Valorant] Saved permanent PUUID: ${result.puuid}", "info")
            }
            if (result.name.isNotEmpty() && result.name != name) {
                prefs.valorantName = result.name
            }
            if (result.tag.isNotEmpty() && result.tag != tag) {
                prefs.valorantTag = result.tag
            }

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
        name: String,
        tag: String,
        region: String,
        puuid: String
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
                    puuid = puuid,
                    currentRank = "Ascendant Goal",
                    formattedMessage = "🎮 $name#$tag: Challenging for Ascendant (one hand MMO mouse)! [Henrik API Key required in app settings for live RR]"
                )
            }

            if (code == 404) {
                val targetDesc = if (puuid.isNotEmpty()) "PUUID $puuid" else "$name#$tag"
                return ValorantRankResult(
                    success = false,
                    name = name,
                    tag = tag,
                    puuid = puuid,
                    currentRank = "Unknown",
                    formattedMessage = "🎮 Player $targetDesc not found on region '$region'. Please verify details in Bot Settings!"
                )
            }

            if (response.isSuccessful && body.isNotBlank()) {
                val json = JsonParser.parseString(body).asJsonObject
                val dataObj = json.getAsJsonObject("data")

                if (dataObj != null) {
                    var parsedPuuid = puuid
                    var parsedName = name
                    var parsedTag = tag
                    var rankPatched = "Unrated"
                    var rr = 0
                    var elo = 0
                    var change = 0
                    var peakRank = ""

                    // Check v3 format: data.current and data.account
                    if (dataObj.has("current") && dataObj.getAsJsonObject("current") != null) {
                        val currentObj = dataObj.getAsJsonObject("current")
                        val tierObj = currentObj.getAsJsonObject("tier")
                        rankPatched = tierObj?.get("name")?.asString ?: "Unrated"
                        rr = currentObj.get("rr")?.asInt ?: 0
                        elo = currentObj.get("elo")?.asInt ?: 0
                        change = currentObj.get("last_change")?.asInt ?: 0

                        if (dataObj.has("account") && dataObj.getAsJsonObject("account") != null) {
                            val accObj = dataObj.getAsJsonObject("account")
                            val retPuuid = accObj.get("puuid")?.asString.orEmpty()
                            if (retPuuid.isNotBlank()) parsedPuuid = retPuuid
                            parsedName = accObj.get("name")?.asString ?: parsedName
                            parsedTag = accObj.get("tag")?.asString ?: parsedTag
                        }

                        if (dataObj.has("peak") && dataObj.getAsJsonObject("peak") != null) {
                            val peakTier = dataObj.getAsJsonObject("peak").getAsJsonObject("tier")
                            peakRank = peakTier?.get("name")?.asString.orEmpty()
                        }
                    }
                    // Check v2 format: data.current_data
                    else if (dataObj.has("current_data") && dataObj.getAsJsonObject("current_data") != null) {
                        val curObj = dataObj.getAsJsonObject("current_data")
                        rankPatched = curObj.get("currenttierpatched")?.asString ?: "Unrated"
                        rr = curObj.get("ranking_in_tier")?.asInt ?: 0
                        elo = curObj.get("elo")?.asInt ?: 0
                        change = curObj.get("mmr_change_to_last_game")?.asInt ?: 0

                        val retPuuid = dataObj.get("puuid")?.asString.orEmpty()
                        if (retPuuid.isNotBlank()) parsedPuuid = retPuuid
                        parsedName = dataObj.get("name")?.asString ?: parsedName
                        parsedTag = dataObj.get("tag")?.asString ?: parsedTag

                        if (dataObj.has("highest_rank") && dataObj.getAsJsonObject("highest_rank") != null) {
                            peakRank = dataObj.getAsJsonObject("highest_rank").get("patched_tier")?.asString.orEmpty()
                        }
                    }
                    // Check v1 format
                    else if (dataObj.has("currenttierpatched")) {
                        rankPatched = dataObj.get("currenttierpatched")?.asString ?: "Unrated"
                        rr = dataObj.get("ranking_in_tier")?.asInt ?: 0
                        elo = dataObj.get("elo")?.asInt ?: 0
                        change = dataObj.get("mmr_change_to_last_game")?.asInt ?: 0

                        val retPuuid = dataObj.get("puuid")?.asString.orEmpty()
                        if (retPuuid.isNotBlank()) parsedPuuid = retPuuid
                        parsedName = dataObj.get("name")?.asString ?: parsedName
                        parsedTag = dataObj.get("tag")?.asString ?: parsedTag
                    }

                    val changeStr = if (change >= 0) "+$change" else "$change"
                    val peakStr = if (peakRank.isNotBlank() && peakRank != "null" && peakRank != rankPatched) " (Peak: $peakRank)" else ""
                    val formatted = "🎮 $parsedName#$parsedTag: $rankPatched ($rr RR | ELO $elo) [$changeStr last match]$peakStr"

                    return ValorantRankResult(
                        success = true,
                        name = parsedName,
                        tag = parsedTag,
                        puuid = parsedPuuid,
                        currentRank = rankPatched,
                        rr = rr,
                        elo = elo,
                        lastChange = change,
                        peakRank = peakRank,
                        formattedMessage = formatted,
                        rawJson = body
                    )
                }
            }

            return ValorantRankResult(
                success = false,
                name = name,
                tag = tag,
                puuid = puuid,
                currentRank = "Ascendant Challenge",
                formattedMessage = "🎮 $name is doing a one-handed MMO mouse challenge to reach Ascendant in Valorant!"
            )
        } catch (e: Exception) {
            return ValorantRankResult(
                success = false,
                name = name,
                tag = tag,
                puuid = puuid,
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
