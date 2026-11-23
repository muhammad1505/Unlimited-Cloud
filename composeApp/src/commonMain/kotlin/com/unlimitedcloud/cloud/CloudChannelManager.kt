package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.settings.AppPreferences
import com.unlimitedcloud.tdjson.TdJsonSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * Resolves (or creates once) the private Telegram forum supergroup that backs
 * the file tree.
 *
 * Telegram has no folders outside a forum, so the storage chat is a supergroup
 * with topics and every app folder becomes one topic. Builds before 1.3.0 used
 * a plain broadcast channel, which cannot host topics; such a channel is deleted
 * and replaced by a private forum group, so its files have to be uploaded again.
 * A supergroup only counts as storage once `getSupergroup` reports it as a
 * forum and not a channel, so an old channel can never be reused as storage.
 *
 * The chat id is persisted as soon as it is known and validated with `getChat`
 * on every launch. Discovery only falls back to searching when that id is gone
 * (reinstall, chat deleted, account switch), because `searchChats` does not see
 * a chat that TDLib has not loaded on this device yet.
 */
class CloudChannelManager(
    private val session: TdJsonSession,
    private val preferences: AppPreferences,
) {
    companion object {
        const val ROOT_STORAGE_TITLE = "UNLIMITED_CLOUD_ROOT_V1"

        private const val ROOT_STORAGE_DESCRIPTION =
            "Unlimited Cloud storage root (v1). Every topic is a folder; renaming or " +
                "deleting this group orphans uploaded files."

        private const val CHAT_SCAN_LIMIT = 100

        /** `getChat` probes for the stored id before it is declared missing. */
        private const val CHECK_ATTEMPTS = 2
        private const val CHECK_DELAY_MILLIS = 500L
    }

    private val state = MutableStateFlow<CloudChannelState>(CloudChannelState.Idle)
    val channelState: StateFlow<CloudChannelState> = state.asStateFlow()

    private val topics = ForumTopics(session)
    private var cachedChatId: Long? = null

    /** Why the last `createNewSupergroupChat` was refused, for the settings card. */
    private var lastCreateError: String? = null

    /** Forgets the storage chat of the previous account after a logout. */
    fun forget() {
        cachedChatId = null
        topics.forget()
        state.value = CloudChannelState.Idle
    }

    suspend fun ensureRootChannel(): Long? {
        cachedChatId?.let { return it }

        state.value = CloudChannelState.Resolving

        // While the session is still opening or its chat list is loading, TDLib
        // answers requests with a temporary error. Treating that as "the chat is
        // gone" cleared the stored id, and the explorer stayed empty until the
        // chat happened to be found again. A definitive answer is retried once
        // and the stored id only goes away when the chat really is missing.
        var savedState = Check.Missing
        val saved = preferences.cloudRootChatId()
        if (saved != null) {
            savedState = checkWithRetries(saved)
            if (savedState == Check.Valid) return ready(saved)
            // A channel from a build before 1.3.0 cannot host topics, so it is
            // removed before the forum group is created rather than kept next to
            // it with the same title.
            if (savedState == Check.Legacy) deleteStorageChat(saved)
        }

        val found = runCatching { findExistingChat() }.getOrElse { failure ->
            state.value = CloudChannelState.Failed(failure.message ?: "gagal menyiapkan penyimpanan")
            return null
        }
        if (found != null) {
            if (found.isForum) {
                preferences.setCloudRootChatId(found.chatId)
                deleteLeftoverStorageChats(except = found.chatId)
                return ready(found.chatId)
            }
            deleteStorageChat(found.chatId)
        }

        if (saved != null && savedState == Check.Unknown) {
            // Neither the stored id nor the search answered; creating a group
            // here would orphan every file already uploaded, so keep the id and
            // let the next refresh try again.
            state.value = CloudChannelState.Failed("Penyimpanan belum siap")
            return null
        }

        if (saved != null) {
            // The chat is gone or was renamed; forget it so a new one is created
            // instead of retrying a dead id forever.
            preferences.setCloudRootChatId(null)
        }

        val created = runCatching { createForumGroup() }.getOrElse { failure ->
            state.value = CloudChannelState.Failed(failure.message ?: "gagal membuat grup topik")
            return null
        }
        if (created == null) {
            val reason = lastCreateError
            state.value = CloudChannelState.Failed(
                if (reason.isNullOrBlank()) {
                    "grup $ROOT_STORAGE_TITLE tidak dapat dibuat"
                } else {
                    "grup $ROOT_STORAGE_TITLE tidak dapat dibuat: $reason"
                },
            )
            return null
        }

        preferences.setCloudRootChatId(created)
        deleteLeftoverStorageChats(except = created)
        return ready(created)
    }

    private enum class Check { Valid, Legacy, Missing, Unknown }

    private data class StorageChat(val chatId: Long, val isForum: Boolean)

    private fun ready(chatId: Long): Long {
        cachedChatId = chatId
        if (state.value != CloudChannelState.Ready(chatId)) {
            logDiagnostic(LOG_TAG, "penyimpanan siap: $chatId")
        }
        state.value = CloudChannelState.Ready(chatId)
        return chatId
    }

    private suspend fun checkWithRetries(chatId: Long): Check {
        var result = Check.Missing
        for (attempt in 0 until CHECK_ATTEMPTS) {
            result = checkChat(chatId)
            if (result != Check.Unknown) return result
            delay(CHECK_DELAY_MILLIS * (attempt + 1))
        }
        return result
    }

    /**
     * Whether [chatId] is the storage chat this app owns. A chat with the right
     * title that cannot host topics is reported as [Check.Legacy] so it can be
     * deleted before a forum group is created; errors that do not say the
     * chat is missing stay [Check.Unknown].
     */
    private suspend fun checkChat(chatId: Long): Check {
        val chat = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "getChat")
                    put("chat_id", chatId)
                },
            )
        }.getOrElse { return Check.Unknown }

        if (chat.isError()) {
            val message = chat["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return if (message.contains("not found", ignoreCase = true)) {
                Check.Missing
            } else {
                Check.Unknown
            }
        }

        val type = chat["type"]?.jsonObject
        if (type?.get("@type")?.jsonPrimitive?.contentOrNull != "chatTypeSupergroup") {
            return Check.Missing
        }
        val supergroupId = type["supergroup_id"]?.jsonPrimitive?.longOrNull
            ?: return Check.Missing

        val supergroup = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "getSupergroup")
                    put("supergroup_id", supergroupId)
                },
            )
        }.getOrElse { return Check.Unknown }

        if (supergroup.isError()) return Check.Unknown

        // A broadcast channel is a supergroup too, so `is_forum` alone proved
        // nothing: builds up to 1.2.x created the storage as a channel and an
        // upgraded app happily reused it. Both flags are checked now.
        val isForum = supergroup["is_forum"]?.jsonPrimitive?.booleanOrNull == true
        val isChannel = supergroup["is_channel"]?.jsonPrimitive?.booleanOrNull == true
        val title = chat["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val isStorageTitle = title.startsWith(ROOT_STORAGE_TITLE)

        return when {
            isForum && !isChannel && isStorageTitle -> Check.Valid
            // Our own chat, but not a private forum group yet.
            isStorageTitle -> Check.Legacy
            // Someone else's chat with a similar title: leave it alone.
            else -> Check.Missing
        }
    }

    private suspend fun findExistingChat(): StorageChat? {
        // Server-side search covers chats that are not loaded locally yet.
        val serverIds = searchChatIds("searchChatsOnServer")
            .ifEmpty { searchChatIds("searchChats") }

        val knownIds = knownChatIds()

        var legacy: Long? = null
        (serverIds + knownIds).distinct().forEach { chatId ->
            when (checkChat(chatId)) {
                Check.Valid -> return StorageChat(chatId, isForum = true)
                Check.Legacy -> if (legacy == null) legacy = chatId
                else -> Unit
            }
        }
        return legacy?.let { StorageChat(it, isForum = false) }
    }

    private suspend fun searchChatIds(type: String): List<Long> = runCatching {
        session.call(
            buildJsonObject {
                put("@type", type)
                put("query", ROOT_STORAGE_TITLE)
                put("limit", 20)
            },
        )["chat_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Creates the storage supergroup and proves it really is a private forum.
     *
     * `is_forum` in the creation call is not honoured by every server version,
     * so the chat is read back and topics are enabled with
     * `toggleSupergroupIsForum` when they are missing. A chat that is still a
     * channel or a plain group is deleted instead of being used as storage.
     */
    private suspend fun createForumGroup(): Long? {
        val chatId = requestSupergroup(isForum = true)
            ?: requestSupergroup(isForum = false)
            ?: return null

        if (checkWithRetries(chatId) == Check.Valid) {
            logDiagnostic(LOG_TAG, "grup topik siap: $chatId")
            return chatId
        }

        logDiagnostic(LOG_TAG, "topik belum aktif di chat $chatId, mengaktifkan")
        if (enableTopics(chatId)) {
            logDiagnostic(LOG_TAG, "topik aktif di chat $chatId")
            return chatId
        }

        logDiagnostic(LOG_TAG, "topik tidak bisa diaktifkan; chat $chatId dihapus lagi")
        lastCreateError = "topik forum tidak bisa diaktifkan"
        deleteStorageChat(chatId)
        return null
    }

    private suspend fun requestSupergroup(isForum: Boolean): Long? {
        val response = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "createNewSupergroupChat")
                    put("title", ROOT_STORAGE_TITLE)
                    put("is_forum", isForum)
                    put("is_channel", false)
                    put("description", ROOT_STORAGE_DESCRIPTION)
                    // Required by TDLib even for a private group: without it the
                    // whole request is rejected and no chat is created at all.
                    put("location", chatLocationJson())
                },
            )
        }.getOrElse { failure ->
            lastCreateError = failure.message
            logDiagnostic(LOG_TAG, "grup topik gagal dibuat: ${failure.message}")
            return null
        }
        if (response.isError()) {
            lastCreateError = response["message"]?.jsonPrimitive?.contentOrNull
            logDiagnostic(LOG_TAG, "grup topik ditolak TDLib: ${lastCreateError.orEmpty()}")
            return null
        }
        val chatId = response["id"]?.jsonPrimitive?.longOrNull
        if (chatId != null) {
            lastCreateError = null
            logDiagnostic(LOG_TAG, "chat penyimpanan dibuat: $chatId (forum=$isForum)")
        }
        return chatId
    }

    private fun chatLocationJson(): JsonObject = buildJsonObject {
        put("@type", "chatLocation")
        put("address", "")
        put(
            "location",
            buildJsonObject {
                put("@type", "location")
                put("latitude", 0.0)
                put("longitude", 0.0)
                put("horizontal_accuracy", 0.0)
            },
        )
    }

    /** Turns topics on for a chat that was created as a plain supergroup. */
    private suspend fun enableTopics(chatId: Long): Boolean {
        val supergroupId = supergroupIdOf(chatId) ?: return false
        val response = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "toggleSupergroupIsForum")
                    put("supergroup_id", supergroupId)
                    put("is_forum", true)
                    put("has_forum_tabs", true)
                },
            )
        }.getOrElse { failure ->
            logDiagnostic(LOG_TAG, "aktifkan topik gagal: ${failure.message}")
            return false
        }
        if (response.isError()) {
            logDiagnostic(
                LOG_TAG,
                "aktifkan topik ditolak TDLib: " +
                    response["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            return false
        }
        repeat(3) {
            if (checkChat(chatId) == Check.Valid) return true
            delay(CHECK_DELAY_MILLIS)
        }
        return checkChat(chatId) == Check.Valid
    }

    private suspend fun supergroupIdOf(chatId: Long): Long? {
        val chat = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "getChat")
                    put("chat_id", chatId)
                },
            )
        }.getOrNull() ?: return null
        if (chat.isError()) return null
        val type = chat["type"]?.jsonObject ?: return null
        if (type["@type"]?.jsonPrimitive?.contentOrNull != "chatTypeSupergroup") return null
        return type["supergroup_id"]?.jsonPrimitive?.longOrNull
    }

    /**
     * Removes every other chat this app created for storage. Repeated installs
     * left more than one behind, and an old channel sitting in the chat list is
     * what makes the storage look like it is still a channel.
     */
    private suspend fun deleteLeftoverStorageChats(except: Long) {
        val ids = runCatching {
            (searchChatIds("searchChatsOnServer") + searchChatIds("searchChats") + knownChatIds())
                .distinct()
        }.getOrDefault(emptyList())
        ids.forEach { chatId ->
            if (chatId == except) return@forEach
            val check = runCatching { checkChat(chatId) }.getOrDefault(Check.Missing)
            if (check == Check.Legacy) deleteStorageChat(chatId)
        }
    }

    private suspend fun knownChatIds(): List<Long> {
        // `loadChats` only fills the local cache and returns 404 once everything
        // is already there, which is fine.
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
        return runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "getChats")
                    put(
                        "chat_list",
                        buildJsonObject { put("@type", "chatListMain") },
                    )
                    put("limit", CHAT_SCAN_LIMIT)
                },
            )["chat_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Deletes the storage chat of a build before 1.3.0, when files lived in a
     * broadcast channel. A channel cannot host topics and its messages cannot be
     * reused, so the app starts a fresh forum group instead of keeping it.
     *
     * `deleteChat` needs owner rights, so a refusal falls back to revoking the
     * history: the chat may stay in the chat list, but the app no longer uses it.
     */
    private suspend fun deleteStorageChat(chatId: Long) {
        val response = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "deleteChat")
                    put("chat_id", chatId)
                },
            )
        }.getOrElse { failure ->
            logDiagnostic(LOG_TAG, "hapus channel lama gagal: ${failure.message}")
            return
        }

        if (!response.isError()) {
            logDiagnostic(LOG_TAG, "channel lama dihapus: $chatId")
            return
        }

        val message = response["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (message.contains("not found", ignoreCase = true)) {
            logDiagnostic(LOG_TAG, "channel lama tidak ditemukan: $chatId")
            return
        }
        logDiagnostic(LOG_TAG, "hapus channel lama ditolak TDLib: $message")

        val fallback = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "deleteChatHistory")
                    put("chat_id", chatId)
                    put("remove_from_chat_list", true)
                    put("revoke", true)
                },
            )
        }.getOrElse { failure ->
            logDiagnostic(LOG_TAG, "hapus riwayat channel lama gagal: ${failure.message}")
            return
        }
        if (fallback.isError()) {
            logDiagnostic(
                LOG_TAG,
                "hapus riwayat channel lama ditolak TDLib: " +
                    fallback["message"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private fun JsonObject.isError(): Boolean =
        this["@type"]?.jsonPrimitive?.contentOrNull == "error"
}
