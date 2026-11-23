package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.settings.AppPreferences
import com.unlimitedcloud.tdjson.TdJsonSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * tree.
 *
 * The chat id is persisted as soon as it is known and validated with `getChat`
 * on every launch. Discovery only falls back to searching when that id is gone
 * (reinstall, channel deleted, account switch), because `searchChats` does not
 * see a channel that TDLib has not loaded on this device yet. Relying on the
 * search alone is what used to create a new channel on every app start.
 */
class CloudChannelManager(
    private val session: TdJsonSession,
    private val preferences: AppPreferences,
) {
    companion object {
        const val ROOT_CHANNEL_TITLE = "UNLIMITED_CLOUD_ROOT_V1"

        private const val ROOT_CHANNEL_DESCRIPTION =
            "Unlimited Cloud storage root (v1). Renaming or deleting this channel orphans uploaded files."

        private const val CHAT_SCAN_LIMIT = 100
    }

    private val state = MutableStateFlow<CloudChannelState>(CloudChannelState.Idle)
    val channelState: StateFlow<CloudChannelState> = state.asStateFlow()

    private var cachedChatId: Long? = null

    suspend fun ensureRootChannel(): Long? {
        cachedChatId?.let { return it }

        state.value = CloudChannelState.Resolving

        val saved = preferences.cloudRootChatId()
        if (saved != null && isRootChannel(saved)) {
            return ready(saved)
        }
        if (saved != null) {
            // The stored chat no longer exists or was renamed; forget it so a new
            // one is created instead of retrying a dead id forever.
            preferences.setCloudRootChatId(null)
        }

        val chatId = runCatching { findExistingChannel() ?: createChannel() }.getOrElse {
            state.value = CloudChannelState.Failed(it.message ?: "gagal menyiapkan channel")
            return null
        }

        if (chatId == null) {
            state.value = CloudChannelState.Failed("channel $ROOT_CHANNEL_TITLE tidak dapat dibuat")
            return null
        }

        preferences.setCloudRootChatId(chatId)
        return ready(chatId)
    }

    private fun ready(chatId: Long): Long {
        cachedChatId = chatId
        if (state.value != CloudChannelState.Ready(chatId)) {
            logDiagnostic(LOG_TAG, "channel penyimpanan siap: $chatId")
        }
        state.value = CloudChannelState.Ready(chatId)
        return chatId
    }

    /** True when [chatId] is still the channel this app owns. */
    private suspend fun isRootChannel(chatId: Long): Boolean = runCatching {
        val chat = session.call(
            buildJsonObject {
                put("@type", "getChat")
                put("chat_id", chatId)
            },
        )
        chat["@type"]?.jsonPrimitive?.contentOrNull != "error" &&
            chat["title"]?.jsonPrimitive?.contentOrNull == ROOT_CHANNEL_TITLE
    }.getOrDefault(false)

    private suspend fun findExistingChannel(): Long? {
        // Server-side search covers channels that are not loaded locally yet.
        val serverIds = searchChatIds("searchChatsOnServer")
            .ifEmpty { searchChatIds("searchChats") }
        serverIds.forEach { chatId ->
            if (isRootChannel(chatId)) return chatId
        }

        // Last resort: walk the loaded chat list. `loadChats` only fills the local
        // cache and returns 404 once everything is already there, which is fine.
        runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "loadChats")
                    put(
                        "chat_list",
                        buildJsonObject { put("@type", "chatListMain") },
                    )
                    put("limit", CHAT_SCAN_LIMIT)
                },
            )
        }
        val knownIds = session.call(
            buildJsonObject {
                put("@type", "getChats")
                put(
                    "chat_list",
                    buildJsonObject { put("@type", "chatListMain") },
                )
                put("limit", CHAT_SCAN_LIMIT)
            },
        )["chat_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()

        knownIds.forEach { chatId ->
            if (isRootChannel(chatId)) return chatId
        }
        return null
    }

    private suspend fun searchChatIds(type: String): List<Long> = runCatching {
        session.call(
            buildJsonObject {
                put("@type", type)
                put("query", ROOT_CHANNEL_TITLE)
                put("limit", 20)
            },
        )["chat_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
    }.getOrDefault(emptyList())

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
        val chatId = response.jsonObject["id"]?.jsonPrimitive?.longOrNull
        if (chatId != null) logDiagnostic(LOG_TAG, "channel penyimpanan dibuat: $chatId")
        return chatId
    }
}
