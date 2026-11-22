package com.unlimitedcloud.cloud

import com.unlimitedcloud.tdjson.TdJsonSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface CloudChannelState {
    data object Idle : CloudChannelState
    data object Resolving : CloudChannelState
    data class Ready(val chatId: Long) : CloudChannelState
    data class Failed(val message: String) : CloudChannelState
}

/**
 * Resolves (or creates once) the private Telegram channel that backs the file
 * tree. Its title is the only anchor the app relies on, so it must never change.
 */
class CloudChannelManager(private val session: TdJsonSession) {

    companion object {
        const val ROOT_CHANNEL_TITLE = "UNLIMITED_CLOUD_ROOT_V1"

        private const val ROOT_CHANNEL_DESCRIPTION =
            "Unlimited Cloud storage root (v1). Renaming or deleting this channel orphans uploaded files."
    }

    private val state = MutableStateFlow<CloudChannelState>(CloudChannelState.Idle)
    val channelState: StateFlow<CloudChannelState> = state.asStateFlow()

    private var cachedChatId: Long? = null

    suspend fun ensureRootChannel(): Long? {
        cachedChatId?.let { return it }

        state.value = CloudChannelState.Resolving
        val chatId = runCatching { findExistingChannel() ?: createChannel() }.getOrElse {
            state.value = CloudChannelState.Failed(it.message ?: "gagal menyiapkan channel")
            return null
        }

        if (chatId == null) {
            state.value = CloudChannelState.Failed("channel $ROOT_CHANNEL_TITLE tidak dapat dibuat")
            return null
        }

        cachedChatId = chatId
        state.value = CloudChannelState.Ready(chatId)
        return chatId
    }

    private suspend fun findExistingChannel(): Long? {
        val response = session.call(
            buildJsonObject {
                put("@type", "searchChats")
                put("query", ROOT_CHANNEL_TITLE)
                put("limit", 20)
            },
        )
        val chatIds = response["chat_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()

        for (chatId in chatIds) {
            val chat = session.call(
                buildJsonObject {
                    put("@type", "getChat")
                    put("chat_id", chatId)
                },
            )
            if (chat["title"]?.jsonPrimitive?.contentOrNull == ROOT_CHANNEL_TITLE) return chatId
        }
        return null
    }

    private suspend fun createChannel(): Long? {
        val response = session.call(
            buildJsonObject {
                put("@type", "createNewSupergroupChat")
                put("title", ROOT_CHANNEL_TITLE)
                put("is_forum", false)
                put("is_channel", true)
                put("description", ROOT_CHANNEL_DESCRIPTION)
            },
        )
        return response["id"]?.jsonPrimitive?.longOrNull
    }
}
