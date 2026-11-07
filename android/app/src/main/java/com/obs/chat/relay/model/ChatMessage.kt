package com.obs.chat.relay.model

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    @SerializedName("type") val type: String = "chat",
    @SerializedName("platform") val platform: String = "youtube",
    @SerializedName("id") val id: String = "msg_${System.currentTimeMillis()}",
    @SerializedName("username") val username: String = "Anonymous",
    @SerializedName("message") val message: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("badges") val badges: List<String> = emptyList(),
    @SerializedName("amount") val amount: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("stickerUrl") val stickerUrl: String? = null,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("details") val details: Map<String, String>? = null,
    // Poll specific
    @SerializedName("question") val question: String? = null,
    @SerializedName("totalVotes") val totalVotes: String? = null,
    @SerializedName("choices") val choices: List<PollChoice>? = null
)

data class PollChoice(
    @SerializedName("text") val text: String,
    @SerializedName("percentage") val percentage: String,
    @SerializedName("isSelected") val isSelected: Boolean = false,
    @SerializedName("isWinner") val isWinner: Boolean = false
)

data class StreamState(
    @SerializedName("videoId") val videoId: String? = null,
    @SerializedName("liveState") val liveState: String = "offline",
    @SerializedName("title") val title: String? = null,
    @SerializedName("viewers") val viewers: Int = 0,
    @SerializedName("concurrentViewers") val concurrentViewers: Int = viewers
)

data class KickState(
    @SerializedName("username") val username: String? = null,
    @SerializedName("chatroomId") val chatroomId: String? = null,
    @SerializedName("isLive") val isLive: Boolean = false,
    @SerializedName("viewers") val viewers: Int = 0
)

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val type: String = "info" // info, success, warning, error
)
