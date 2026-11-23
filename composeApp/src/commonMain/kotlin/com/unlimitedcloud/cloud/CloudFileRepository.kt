package com.unlimitedcloud.cloud

import com.unlimitedcloud.platform.UploadThumbnail
import com.unlimitedcloud.tdjson.TdJsonSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
    /** Virtual folder, carried by the message caption. */
    val folder: String?,
    /** TDLib's small preview image, only present when the sender provided one. */
    val thumbnailId: Int? = null,
)

/** One slice of the channel history plus the cursor for the next slice. */
data class CloudPage(
    val files: List<CloudFile>,
    val nextFromMessageId: Long?,
)

/**
 * Reads and writes files inside the root channel using plain TDLib requests.
 *
 * Every upload is a document message whose caption names a folder, so the
 * channel doubles as both the file index and the directory structure.
 */
class CloudFileRepository(private val session: TdJsonSession) {

    suspend fun listFiles(
        chatId: Long,
        fromMessageId: Long = 0L,
        limit: Int = PAGE_SIZE,
    ): CloudPage {
        val response = session.call(
            buildJsonObject {
                put("@type", "getChatHistory")
                put("chat_id", chatId)
                put("from_message_id", fromMessageId)
                put("offset", 0)
                put("limit", limit)
                put("only_local", false)
            },
        )
        requireOk(response)

        val messages = response["messages"]?.jsonArray.orEmpty()
        val files = messages.mapNotNull { toFile(it.jsonObject) }
        val lastMessageId = messages.lastOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull

        val next = if (messages.size >= limit && lastMessageId != null && lastMessageId != 0L) {
            lastMessageId
        } else {
            null
        }

        return CloudPage(files = files, nextFromMessageId = next)
    }

    /**
     * Sends the document, then polls the file object until Telegram reports the
     * remote copy as complete. `sendMessage` itself returns before the upload
     * finishes, which used to make the UI claim success too early.
     */
    suspend fun upload(
        chatId: Long,
        localPath: String,
        folder: String?,
        thumbnail: UploadThumbnail? = null,
        onProgress: (Float?) -> Unit = {},
    ) {
        val response = session.call(
            buildJsonObject {
                put("@type", "sendMessage")
                put("chat_id", chatId)
                put(
                    "input_message_content",
                    buildJsonObject {
                        put("@type", "inputMessageDocument")
                        // inputMessageDocument.document is an InputDocument, not an
                        // InputFile: passing inputFileLocal here makes TDLib fail with
                        // the misleading "InputFile is not specified".
                        put(
                            "document",
                            buildJsonObject {
                                put("@type", "inputDocument")
                                put(
                                    "document",
                                    buildJsonObject {
                                        put("@type", "inputFileLocal")
                                        put("path", localPath)
                                    },
                                )
                                // Without a thumbnail the message has no cover, which
                                // left image cards showing a generic icon.
                                put(
                                    "thumbnail",
                                    if (thumbnail == null) {
                                        JsonNull
                                    } else {
                                        buildJsonObject {
                                            put("@type", "inputThumbnail")
                                            put(
                                                "thumbnail",
                                                buildJsonObject {
                                                    put("@type", "inputFileLocal")
                                                    put("path", thumbnail.path)
                                                },
                                            )
                                            put("width", thumbnail.width)
                                            put("height", thumbnail.height)
                                        }
                                    },
                                )
                                put("disable_content_type_detection", true)
                            },
                        )
                        put(
                            "caption",
                            buildJsonObject {
                                put("text", folder.orEmpty())
                                put("entities", buildJsonArray {})
                            },
                        )
                    },
                )
            },
        )
        requireOk(response)

        val fileId = response["content"]
            ?.jsonObject
            ?.get("document")
            ?.jsonObject
            ?.get("document")
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.intOrNull

        if (fileId == null) {
            onProgress(1f)
        } else {
            awaitUploadComplete(fileId, onProgress)
        }
    }

    /**
     * Requests the download and waits until TDLib reports the local copy complete.
     * The returned path points inside TDLib's files directory.
     */
    suspend fun download(
        fileId: Int,
        priority: Int = 16,
        onProgress: (Float?) -> Unit = {},
    ): String = withTimeout(DOWNLOAD_TIMEOUT_MILLIS) {
        session.call(
            buildJsonObject {
                put("@type", "downloadFile")
                put("file_id", fileId)
                put("priority", priority)
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
            val size = file["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val downloaded = local?.get("downloaded_size")?.jsonPrimitive?.longOrNull ?: 0L
            onProgress(
                if (size > 0L) {
                    (downloaded.toFloat() / size.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                },
            )

            if (completed && !candidate.isNullOrBlank()) {
                path = candidate
            } else {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
        onProgress(1f)
        path
    }

    /**
     * Returns the local path of a document's small preview image, fetching it
     * when it is not cached yet. Preview images are a few KB, so the short
     * timeout only guards against TDLib still generating one.
     */
    suspend fun localThumbnail(fileId: Int): String? = withTimeoutOrNull(THUMBNAIL_TIMEOUT_MILLIS) {
        download(fileId, priority = 32)
    }

    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        if (messageIds.isEmpty()) return

        val response = session.call(
            buildJsonObject {
                put("@type", "deleteMessages")
                put("chat_id", chatId)
                put(
                    "message_ids",
                    buildJsonArray {
                        messageIds.forEach { add(JsonPrimitive(it)) }
                    },
                )
                put("revoke", true)
            },
        )
        requireOk(response)
    }

    private suspend fun awaitUploadComplete(fileId: Int, onProgress: (Float?) -> Unit) {
        withTimeout(UPLOAD_TIMEOUT_MILLIS) {
            while (true) {
                val file = session.call(
                    buildJsonObject {
                        put("@type", "getFile")
                        put("file_id", fileId)
                    },
                )
                requireOk(file)

                val size = file["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val remote = file["remote"]?.jsonObject
                val uploaded = remote?.get("uploaded_size")?.jsonPrimitive?.longOrNull ?: 0L
                onProgress(
                    if (size > 0L) {
                        (uploaded.toFloat() / size.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    },
                )

                if (remote?.get("is_uploading_completed")?.jsonPrimitive?.booleanOrNull == true) {
                    onProgress(1f)
                    return@withTimeout
                }
                delay(PROGRESS_POLL_MILLIS)
            }
        }
    }

    private fun toFile(message: JsonObject): CloudFile? {
        val content = message["content"]?.jsonObject ?: return null
        if (content["@type"]?.jsonPrimitive?.contentOrNull != "messageDocument") return null

        val document = content["document"]?.jsonObject ?: return null
        val file = document["document"]?.jsonObject ?: return null
        val thumbnailId = document["thumbnail"]
            ?.jsonObject
            ?.get("file")
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.intOrNull
        val messageId = message["id"]?.jsonPrimitive?.longOrNull ?: return null
        val folder = content["caption"]
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return CloudFile(
            messageId = messageId,
            fileId = file["id"]?.jsonPrimitive?.intOrNull ?: return null,
            name = document["file_name"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: "file-$messageId",
            size = file["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            folder = folder,
            thumbnailId = thumbnailId,
        )
    }

    private fun requireOk(response: JsonObject) {
        if (response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
            error(response["message"]?.jsonPrimitive?.contentOrNull ?: "TDLib menolak permintaan")
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val DOWNLOAD_TIMEOUT_MILLIS = 600_000L
        const val THUMBNAIL_TIMEOUT_MILLIS = 20_000L
        const val UPLOAD_TIMEOUT_MILLIS = 900_000L
        const val POLL_INTERVAL_MILLIS = 750L
        const val PROGRESS_POLL_MILLIS = 500L
    }
}
