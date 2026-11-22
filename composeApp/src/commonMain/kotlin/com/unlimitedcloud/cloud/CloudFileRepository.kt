package com.unlimitedcloud.cloud

import com.unlimitedcloud.tdjson.TdJsonSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One uploaded file, as stored in the root channel's message history. */
data class CloudFile(
    val messageId: Long,
    val fileId: Int,
    val name: String,
    val size: Long,
)

sealed interface CloudFilesState {
    data object Loading : CloudFilesState

    data class Loaded(val files: List<CloudFile>) : CloudFilesState

    data class Failed(val message: String) : CloudFilesState
}

/**
 * Reads and writes files inside the root channel using plain TDLib requests.
 *
 * Every upload is a document message, so the channel doubles as the file index:
 * listing the history rebuilds the file tree without any external database.
 */
class CloudFileRepository(private val session: TdJsonSession) {

    suspend fun listFiles(chatId: Long, limit: Int = 100): List<CloudFile> {
        val response = session.call(
            buildJsonObject {
                put("@type", "getChatHistory")
                put("chat_id", chatId)
                put("from_message_id", 0)
                put("offset", 0)
                put("limit", limit)
                put("only_local", false)
            },
        )
        requireOk(response)

        val messages = response["messages"]?.jsonArray ?: return emptyList()
        return messages.mapNotNull { element ->
            val message = element.jsonObject
            val content = message["content"]?.jsonObject ?: return@mapNotNull null
            if (content["@type"]?.jsonPrimitive?.contentOrNull != "messageDocument") return@mapNotNull null

            val document = content["document"]?.jsonObject ?: return@mapNotNull null
            val file = document["document"]?.jsonObject ?: return@mapNotNull null

            CloudFile(
                messageId = message["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                fileId = file["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                name = document["file_name"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: "file-${message["id"]?.jsonPrimitive?.longOrNull}",
                size = file["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    suspend fun upload(chatId: Long, localPath: String) {
        val response = session.call(
            buildJsonObject {
                put("@type", "sendMessage")
                put("chat_id", chatId)
                put(
                    "input_message_content",
                    buildJsonObject {
                        put("@type", "inputMessageDocument")
                        put(
                            "document",
                            buildJsonObject {
                                put("@type", "inputFileLocal")
                                put("path", localPath)
                            },
                        )
                        put("disable_content_type_detection", true)
                    },
                )
            },
        )
        requireOk(response)
    }

    /**
     * Requests the download and waits until TDLib reports the local copy complete.
     * The returned path points inside TDLib's files directory.
     */
    suspend fun download(fileId: Int): String = withTimeout(DOWNLOAD_TIMEOUT_MILLIS) {
        session.call(
            buildJsonObject {
                put("@type", "downloadFile")
                put("file_id", fileId)
                put("priority", 16)
                put("offset", 0)
                put("limit", 0)
                put("synchronous", false)
            },
        ).let(::requireOk)

        var path = ""
        while (path.isEmpty()) {
            val file = session.call(
                buildJsonObject {
                    put("@type", "getFile")
                    put("file_id", fileId)
                },
            )
            requireOk(file)

            val local = file["local"]?.jsonObject
            val completed = local?.get("is_downloading_completed")?.jsonPrimitive?.booleanOrNull == true
            val candidate = local?.get("path")?.jsonPrimitive?.contentOrNull

            if (completed && !candidate.isNullOrBlank()) {
                path = candidate
            } else {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
        path
    }

    private fun requireOk(response: JsonObject) {
        if (response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
            error(response["message"]?.jsonPrimitive?.contentOrNull ?: "TDLib menolak permintaan")
        }
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 600_000L
        const val POLL_INTERVAL_MILLIS = 750L
    }
}
