package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.tdjson.TdJsonSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Maps the app's virtual folders to topics of the storage forum supergroup.
 *
 * Telegram only exposes real folders as forum topics, so every folder name is
 * backed by one topic: the Telegram app then shows them as tabs inside a single
 * chat instead of a flat feed of documents. General (no folder) stays the
 * unnamed General topic, which TDLib selects when `topic_id` is null.
 *
 * The mapping is cached for the session but not persisted: the topic name is the
 * folder name, so `getForumTopics` recovers it after a reinstall. A cached id is
 * dropped again as soon as Telegram reports the topic is gone.
 */
class ForumTopics(private val session: TdJsonSession) {

    private companion object {
        const val TOPIC_PAGE_SIZE = 100
        const val TOPIC_PAGE_LIMIT = 20

        /** The colors Telegram accepts for a topic icon. */
        val TOPIC_COLORS = listOf(0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F)

        /**
         * Resolving a topic is serialised process-wide, not per instance.
         *
         * `CloudFileRepository` and `CloudChannelManager` each own a
         * [ForumTopics], and two uploads of the same folder used to run the
         * lookup at the same time: both saw an empty topic list and both created
         * a topic, which is where the duplicate tabs in Telegram came from.
         * Holding one lock while the lookup and the create run means the second
         * caller always sees the id the first one stored.
         */
        private val resolveLock = Mutex()

        /** Ids known to the whole process, shared by every instance. */
        private val knownTopicIds = mutableMapOf<Pair<Long, String>, Int>()
    }

    private val cache = mutableMapOf<Pair<Long, String>, Int>()

    fun forget() {
        cache.clear()
        knownTopicIds.clear()
    }

    /**
     * Drops the cached id for [folder] after Telegram rejected it (the user
     * deleted the topic, or the group was edited). Without this a stale id kept
     * failing every upload until the app restarted.
     */
    fun invalidate(chatId: Long, folder: String) {
        val name = forumTopicName(folder) ?: return
        cache.remove(chatId to name)
        knownTopicIds.remove(chatId to name)
    }

    /**
     * Returns the topic id for [folder] in [chatId], creating the topic when it
     * does not exist yet. Null means the upload has to fall back to General.
     */
    suspend fun resolve(chatId: Long, folder: String): Int? {
        val name = forumTopicName(folder) ?: return null
        cache[chatId to name]?.let { return it }

        resolveLock.withLock {
            // Another upload may have resolved it while this one waited.
            cache[chatId to name]?.let { return it }
            knownTopicIds[chatId to name]?.let { known ->
                cache[chatId to name] = known
                return known
            }

            val topicId = when (val lookup = findTopic(chatId, name)) {
                is TopicLookup.Found -> lookup.id
                // Only create when Telegram confirmed the topic is absent. A
                // failed listing used to look identical to "no such topic",
                // so a timeout created a second topic with the same name.
                TopicLookup.Missing -> createTopic(chatId, name) ?: return null
                TopicLookup.Failed -> return null
            }
            cache[chatId to name] = topicId
            knownTopicIds[chatId to name] = topicId
            return topicId
        }
    }

    private sealed interface TopicLookup {
        data class Found(val id: Int) : TopicLookup
        data object Missing : TopicLookup
        data object Failed : TopicLookup
    }

    private suspend fun findTopic(chatId: Long, name: String): TopicLookup {
        var offset = 0
        repeat(TOPIC_PAGE_LIMIT) {
            val response = runCatching {
                session.call(
                    buildJsonObject {
                        put("@type", "getForumTopics")
                        put("chat_id", chatId)
                        // An empty query lists every topic, so the match below is
                        // an exact name comparison instead of server-side search.
                        put("query", "")
                        put("offset_date", 0)
                        put("offset_message_id", 0)
                        put("offset_forum_topic_id", offset)
                        put("limit", TOPIC_PAGE_SIZE)
                    },
                )
            }.getOrNull() ?: return TopicLookup.Failed
            if (isError(response)) return TopicLookup.Failed

            val topics = response["topics"]?.jsonArray.orEmpty()
            topics.forEach { topic ->
                val info = topic.jsonObject["info"]?.jsonObject ?: return@forEach
                if (info["name"]?.jsonPrimitive?.contentOrNull?.trim() == name) {
                    val found = info["forum_topic_id"]?.jsonPrimitive?.intOrNull
                    if (found != null) return TopicLookup.Found(found)
                }
            }

            val next = response["next_offset_forum_topic_id"]?.jsonPrimitive?.intOrNull ?: 0
            if (topics.isEmpty() || next == 0 || next == offset) return TopicLookup.Missing
            offset = next
        }
        // Ran out of pages without a match: the topic really is not there.
        return TopicLookup.Missing
    }

    private suspend fun createTopic(chatId: Long, name: String): Int? {
        val response = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "createForumTopic")
                    put("chat_id", chatId)
                    put("name", name)
                    put("is_name_implicit", false)
                    put(
                        "icon",
                        buildJsonObject {
                            put("color", TOPIC_COLORS[name.hashCode().mod(TOPIC_COLORS.size)])
                            put("custom_emoji_id", 0L)
                        },
                    )
                },
            )
        }.getOrElse { failure ->
            logDiagnostic(LOG_TAG, "topik '$name' gagal dibuat: ${failure.message}")
            return null
        }
        if (isError(response)) {
            logDiagnostic(LOG_TAG, "topik '$name' ditolak TDLib: ${errorMessage(response)}")
            return null
        }
        val topicId = response["forum_topic_id"]?.jsonPrimitive?.intOrNull ?: return null
        logDiagnostic(LOG_TAG, "topik '$name' dibuat: $topicId")
        return topicId
    }

    /**
     * Deletes the topic backing [folder] once the folder no longer has files, so
     * deleted folders do not linger as empty tabs inside the Telegram group.
     * Returns true when Telegram removed the topic.
     */
    suspend fun remove(chatId: Long, folder: String): Boolean {
        val name = forumTopicName(folder) ?: return false
        val topicId = cache[chatId to name]
            ?: knownTopicIds[chatId to name]
            ?: (findTopic(chatId, name) as? TopicLookup.Found)?.id
            ?: return false
        val response = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "deleteForumTopic")
                    put("chat_id", chatId)
                    put("forum_topic_id", topicId)
                },
            )
        }.getOrElse { failure ->
            logDiagnostic(LOG_TAG, "topik '$name' gagal dihapus: ${failure.message}")
            return false
        }
        if (isError(response)) {
            logDiagnostic(LOG_TAG, "hapus topik '$name' ditolak TDLib: ${errorMessage(response)}")
            return false
        }
        cache.remove(chatId to name)
        knownTopicIds.remove(chatId to name)
        logDiagnostic(LOG_TAG, "topik '$name' kosong lalu dihapus")
        return true
    }

    private fun isError(response: JsonObject): Boolean =
        response["@type"]?.jsonPrimitive?.contentOrNull == "error"

    private fun errorMessage(response: JsonObject): String =
        response["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
}

private const val TOPIC_NAME_LIMIT = 128

/**
 * Folder names are free text, topic names are not: TDLib wants 1-128
 * characters on a single line. Null means "no topic", i.e. General.
 *
 * Names longer than the limit keep a short hash suffix, so two folders that
 * share their first 128 characters do not end up sharing one topic.
 */
internal fun forumTopicName(folder: String): String? {
    val flat = folder.replace('\n', ' ').trim()
    if (flat.isEmpty()) return null
    if (flat.length <= TOPIC_NAME_LIMIT) return flat

    val suffix = (flat.hashCode().toLong() and 0xffffffffL).toString(16).take(8)
    return flat.take(TOPIC_NAME_LIMIT - suffix.length - 1).trimEnd() + "-" + suffix
}

/**
 * `topic_id` value for TDLib: a forum topic, or null for the General topic and
 * for chats that are not forums.
 */
internal fun forumTopicJson(topicId: Int?): JsonElement =
    if (topicId == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("@type", "messageTopicForum")
            put("forum_topic_id", topicId)
        }
    }
