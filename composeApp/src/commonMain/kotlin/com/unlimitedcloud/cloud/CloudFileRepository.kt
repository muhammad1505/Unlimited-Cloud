package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** One uploaded file, as stored in the root group's message history. */
data class CloudFile(
    val messageId: Long,
    val fileId: Int,
    val name: String,
    val size: Long,
    /** Virtual folder, carried by the message caption. */
    val folder: String?,
    /** True when [folder] comes from the caption instead of the file's type. */
    val folderFromCaption: Boolean = false,
    /** TDLib's small preview image, only present when the sender provided one. */
    val thumbnailId: Int? = null,
)

/** One slice of the group history plus the cursor for the next slice. */
data class CloudPage(
    val files: List<CloudFile>,
    val nextFromMessageId: Long?,
)

/**
 * Reads and writes files inside the root forum supergroup using plain TDLib
 * requests.
 *
 * Every upload is a document message sent into the topic of its folder, so
 * Telegram shows the same structure the app does; the caption still carries the
 * folder name so a message moved to General keeps its folder.
 */
class CloudFileRepository(private val session: TdJsonSession) {

    private val topics = ForumTopics(session)


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

        // A short page does not mean the history ended: TDLib also returns
        // short pages while it fetches from the server and when the slice holds
        // service messages instead of files. Paging continues until a request
        // comes back empty, so the last page can no longer be dropped silently.
        val next = if (lastMessageId != null && lastMessageId != 0L) lastMessageId else null

        return CloudPage(files = files, nextFromMessageId = next)
    }

    /**
     * Marks the chat as opened so TDLib downloads its remote history.
     *
     * Paging a chat that was never opened only returned the messages already
     * cached on the device, which is why the explorer could start with a single
     * file until the list was refreshed by hand.
     */
    suspend fun prepareHistory(chatId: Long) {
        // Opening the chat is what makes TDLib fetch remote history instead of
        // only serving the local cache. It is deliberately not closed again:
        // closing right after opening cancelled the fetch it had just started.
        runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "openChat")
                    put("chat_id", chatId)
                },
            )
        }
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
        // General (no folder) needs no topic. A folder whose topic cannot be
        // created still gets uploaded, it just lands in General.
        val resolvedTopicId = folder?.let { topics.resolve(chatId, it) }

        suspend fun send(topicId: Int?): JsonObject = session.call(
            buildJsonObject {
                put("@type", "sendMessage")
                put("chat_id", chatId)
                put("topic_id", forumTopicJson(topicId))
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

        var response = runCatching { send(resolvedTopicId) }
        // A topic deleted or renamed from the Telegram app invalidates the cached
        // id: retry once with a fresh topic, then fall back to General so a
        // disappearing tab never fails an upload.
        if (folder != null && topicFailure(response)) {
            logDiagnostic(LOG_TAG, "topik '$folder' ditolak, mencoba topik baru")
            topics.invalidate(chatId, folder)
            response = runCatching { send(topics.resolve(chatId, folder)) }
            if (topicFailure(response)) {
                response = runCatching { send(null) }
            }
        }
        val sent = response.getOrElse { throw it }
        requireOk(sent)

        val fileId = sent["content"]
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
    ): String {
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

        // A fixed ten minute budget failed large files on slow connections while
        // TDLib was still making progress. Watch the progress itself instead: a
        // transfer that keeps growing may run as long as it needs, a stalled one
        // is reported right away.
        var lastDownloaded = -1L
        var lastProgressAt = TimeSource.Monotonic.markNow()
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

            if (downloaded != lastDownloaded) {
                lastDownloaded = downloaded
                lastProgressAt = TimeSource.Monotonic.markNow()
            } else if (lastProgressAt.elapsedNow() > DOWNLOAD_STALL_TIMEOUT) {
                error("Unduhan berhenti merespons. Periksa koneksi lalu coba lagi.")
            }

            if (completed && !candidate.isNullOrBlank()) {
                path = candidate
            } else {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
        onProgress(1f)
        return path
    }

    /**
     * Returns the local path of a document's small preview image, fetching it
     * when it is not cached yet. Preview images are a few KB, so the short
     * timeout only guards against TDLib still generating one.
     */
    suspend fun localThumbnail(fileId: Int): String? = withTimeoutOrNull(THUMBNAIL_TIMEOUT_MILLIS) {
        download(fileId, priority = 32)
    }

    /**
     * Returns the local path of a file only when TDLib already finished
     * downloading it. Used for covers of older files, which must not trigger a
     * full download just to draw a thumbnail.
     */
    suspend fun cachedFilePath(fileId: Int): String? {
        val file = session.call(
            buildJsonObject {
                put("@type", "getFile")
                put("file_id", fileId)
            },
        )
        requireOk(file)
        val local = file["local"]?.jsonObject ?: return null
        val completed = local["is_downloading_completed"]?.jsonPrimitive?.booleanOrNull == true
        val path = local["path"]?.jsonPrimitive?.contentOrNull
        return if (completed && !path.isNullOrBlank()) path else null
    }

    /**
     * Moves a message that carries no folder caption into the topic of [folder].
     *
     * Telegram cannot move a message between topics, so the file is forwarded
     * into the target topic first and the copy receives the folder caption; only
     * then is the original deleted. A forward that fails therefore never loses
     * the file. Returns true once the file lives in [folder]; a deletion that
     * fails is not reported as a failure, because retrying would copy the file a
     * second time.
     */
    suspend fun moveToTypeTopic(chatId: Long, messageId: Long, folder: String): Boolean {
        val topicId = topics.resolve(chatId, folder) ?: return false
        val forwarded = session.call(
            buildJsonObject {
                put("@type", "forwardMessages")
                put("chat_id", chatId)
                put("topic_id", forumTopicJson(topicId))
                put("from_chat_id", chatId)
                put("message_ids", buildJsonArray { add(JsonPrimitive(messageId)) })
                put("options", JsonNull)
                put("send_copy", false)
                put("remove_caption", false)
            },
        )
        val error = forwarded["message"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { forwarded["@type"]?.jsonPrimitive?.contentOrNull == "error" }
        if (error != null) {
            logDiagnostic(LOG_TAG, "pindah topik '$folder' ditolak TDLib: $error")
            return false
        }
        val copyId = forwarded["messages"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.longOrNull
            ?: return false

        // The copy gets the folder caption, so it stays grouped after automatic
        // topics are switched off. A caption that cannot be written does not
        // stop the move: the file already sits in the right topic.
        val captioned = runCatching {
            session.call(
                buildJsonObject {
                    put("@type", "editMessageCaption")
                    put("chat_id", chatId)
                    put("message_id", copyId)
                    put(
                        "caption",
                        buildJsonObject {
                            put("text", folder)
                            put("entities", buildJsonArray {})
                        },
                    )
                },
            )
        }.getOrNull()
        if (captioned == null || captioned["@type"]?.jsonPrimitive?.contentOrNull == "error") {
            logDiagnostic(LOG_TAG, "caption '$folder' gagal ditulis pada pesan $copyId")
        }

        val deleted = runCatching { deleteMessages(chatId, listOf(messageId)) }.isSuccess
        if (!deleted) {
            logDiagnostic(LOG_TAG, "pesan asli $messageId gagal dihapus setelah disalin ke '$folder'")
        }
        return true
    }

    /**
     * Deletes the forum topic of [folder] after its last file was removed, so an
     * empty folder cannot remain as a tab in Telegram.
     */
    suspend fun deleteTopic(chatId: Long, folder: String): Boolean =
        topics.remove(chatId, folder)

    /**
     * Deletes a selection in batches: TDLib accepts at most 100 ids per request,
     * so a larger multi-select used to be rejected as a whole.
     */
    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        deleteBatches(messageIds).forEach { batch ->
            val response = session.call(
                buildJsonObject {
                    put("@type", "deleteMessages")
                    put("chat_id", chatId)
                    put(
                        "message_ids",
                        buildJsonArray {
                            batch.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                    put("revoke", true)
                },
            )
            requireOk(response)
        }
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
        val name = document["file_name"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "file-$messageId"
        // Files uploaded before automatic topics (and anything that reached the
        // group outside the app) carry no folder caption. The caller files them
        // by type when reading, so the explorer shows them under Foto, Video, ...
        // right away instead of leaving them loose in "Tanpa folder".
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
            name = name,
            size = file["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            folder = folder,
            folderFromCaption = folder != null,
            thumbnailId = thumbnailId,
        )
    }

    private fun requireOk(response: JsonObject) {
        if (response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
            error(response["message"]?.jsonPrimitive?.contentOrNull ?: "TDLib menolak permintaan")
        }
    }

    /** True when a send failed because the forum topic it targeted is gone. */
    private fun topicFailure(result: Result<JsonObject>): Boolean {
        val message = result.exceptionOrNull()?.message
            ?: result.getOrNull()?.let { response ->
                if (response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
                    response["message"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }
        return message?.contains("topic", ignoreCase = true) == true
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val THUMBNAIL_TIMEOUT_MILLIS = 20_000L
        const val UPLOAD_TIMEOUT_MILLIS = 900_000L
        const val POLL_INTERVAL_MILLIS = 750L
        const val PROGRESS_POLL_MILLIS = 500L

        /** A download that stops growing for this long is treated as broken. */
        val DOWNLOAD_STALL_TIMEOUT = 5.minutes
    }
}

/** TDLib rejects a deleteMessages call carrying more ids than this. */
internal const val MESSAGES_PER_DELETE = 100

/** Splits ids into the batches one deleteMessages request may carry. */
internal fun deleteBatches(messageIds: List<Long>): List<List<Long>> =
    messageIds.chunked(MESSAGES_PER_DELETE)
