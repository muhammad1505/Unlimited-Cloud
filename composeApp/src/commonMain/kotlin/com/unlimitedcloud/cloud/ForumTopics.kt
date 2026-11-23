package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.tdjson.TdJsonSession
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
 * The mapping is not persisted: the topic name is the folder name, so
 * `getForumTopics` recovers it after a reinstall and a renamed topic simply
 * gets a fresh one on the next upload.
 */
class ForumTopics(private val session: TdJsonSession) {

    private companion object {
        const val TOPIC_PAGE_SIZE = 100
        const val TOPIC_PAGE_LIMIT = 20

        /** The colors Telegram accepts for a topic icon. */
        val TOPIC_COLORS = listOf(0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F)
    }

    private val cache = mutableMapOf<Pair<Long, String>, Int>()

    fun forget() = cache.clear()

    /**
     * Returns the topic id for [folder] in [chatId], creating the topic when it
     * does not exist yet. Null means the upload has to fall back to General.
     */
    suspend fun resolve(chatId: Long, folder: String): Int? {
        val name = forumTopicName(folder) ?: return null
        cache[chatId to name]?.let { return it }
        val topicId = findTopic(chatId, name) ?: createTopic(chatId, name) ?: return null
        cache[chatId to name] = topicId
        return topicId
    }

    private suspend fun findTopic(chatId: Long, name: String): Int? {
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
            }.getOrNull() ?: return null
            if (isError(response)) return null

            val topics = response["topics"]?.jsonArray.orEmpty()
            topics.forEach { topic ->
                val info = topic.jsonObject["info"]?.jsonObject ?: return@forEach
                if (info["name"]?.jsonPrimitive?.contentOrNull?.trim() == name) {
                    val found = info["forum_topic_id"]?.jsonPrimitive?.intOrNull
                    if (found != null) return found
                }
            }

            val next = response["next_offset_forum_topic_id"]?.jsonPrimitive?.intOrNull ?: 0
            if (topics.isEmpty() || next == 0 || next == offset) return null
            offset = next
        }
        return null
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

    private fun isError(response: JsonObject): Boolean =
        response["@type"]?.jsonPrimitive?.contentOrNull == "error"

    private fun errorMessage(response: JsonObject): String =
        response["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
}

/**
 * Folder names are free text, topic names are not: TDLib wants 1-128
 * characters on a single line. Null means "no topic", i.e. General.
 */
internal fun forumTopicName(folder: String): String? =
    folder
        .replace('\n', ' ')
        .trim()
        .take(128)
        .trim()
        .takeIf { it.isNotEmpty() }

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
