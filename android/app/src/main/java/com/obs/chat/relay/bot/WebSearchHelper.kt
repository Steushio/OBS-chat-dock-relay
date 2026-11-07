package com.obs.chat.relay.bot

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class WebSearchHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val searchPatterns = listOf(
        Pattern.compile("[?]"),
        Pattern.compile("^(who|who's|whos|what|what's|whats|when|when's|where|where's|why|how|how's|which|whose)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(is|are|was|were|did|does|do|will|can|could|should|would)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(release date|news|score|weather|price|definition|meaning of|worth|cost|ceo|net worth|founder|owner|height|age|population)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(who is|who was|what is|what was|where is|how to|how do|tell me|explain)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(search|google)\\s+for\\b", Pattern.CASE_INSENSITIVE)
    )

    fun isSearchableQuery(message: String?): Boolean {
        if (message == null || message.trim().length < 3) return false
        val clean = message.trim()
        return searchPatterns.any { it.matcher(clean).find() }
    }

    private fun cleanSnippet(html: String?): String {
        if (html == null) return ""
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    suspend fun search(query: String?): String? = withContext(Dispatchers.IO) {
        if (query.isNullOrBlank()) return@withContext null
        val cleanQuery = query
            .replace(Regex("^[!?]"), "")
            .replace(Regex("^(!ai|ai|search|google)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleanQuery.isBlank()) return@withContext null

        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val answer = json.get("Answer")?.asString
                    if (!answer.isNullOrBlank()) {
                        return@withContext cleanSnippet(answer)
                    }
                    val abstractText = json.get("AbstractText")?.asString
                    if (!abstractText.isNullOrBlank() && abstractText.length > 20) {
                        return@withContext cleanSnippet(abstractText)
                    }
                    val related = json.getAsJsonArray("RelatedTopics")
                    if (related != null && related.size() > 0) {
                        val first = related[0].asJsonObject
                        val text = first.get("Text")?.asString
                        if (!text.isNullOrBlank()) {
                            return@withContext cleanSnippet(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through
        }
        null
    }
}
