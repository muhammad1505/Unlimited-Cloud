package com.unlimitedcloud.cloud

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.platform.UploadThumbnail
import com.unlimitedcloud.tdjson.TdJsonSession
import kotlinx.coroutines.delay
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
import okio.FileSystem
import okio.Path.Companion.toPath
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
        requestDownload(fileId, priority)

        // A fixed ten minute budget failed large files on slow connections while
        // TDLib was still making progress. Watch the progress itself instead: a
        // transfer that keeps growing may run as long as it needs, a stalled one
        // is reported right away.
        var lastDownloaded = -1L
        var lastProgressAt = TimeSource.Monotonic.markNow()
        var path = ""
        // TDLib can list a completed copy whose bytes the app already removed
        // after uploading. Such a record is dropped once and the bytes are asked
        // for again; a second dead record is reported instead of looping.
        var staleRecords = 0
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
                if (FileSystem.SYSTEM.exists(candidate.toPath())) {
                    path = candidate
                } else if (staleRecords < MAX_STALE_LOCAL_RETRIES) {
                    staleRecords++
                    logDiagnostic(LOG_TAG, "salinan lokal hilang, unduh ulang file $fileId")
                    forgetLocalFile(fileId)
                    requestDownload(fileId, priority)
                    delay(POLL_INTERVAL_MILLIS)
                } else {
                    error("Salinan lokal berkas ini sudah dibersihkan dan tidak bisa diunduh ulang.")
                }
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
        if (!completed || path.isNullOrBlank()) return null
        if (FileSystem.SYSTEM.exists(path.toPath())) return path
        // The app removes its upload copy once Telegram has the file, but TDLib
        // keeps pointing at that path. Handing it to the preview showed a file
        // that was no longer there, so the record is dropped instead.
        logDiagnostic(LOG_TAG, "catatan lokal basi untuk file $fileId, dibersihkan")
        forgetLocalFile(fileId)
        return null
    }

    /** Asks TDLib to start downloading [fileId] into its own cache. */
    private suspend fun requestDownload(fileId: Int, priority: Int) {
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
    }

    /**
     * Drops TDLib's local record of a file whose bytes are gone (the upload copy
     * the app deleted). Without this, TDLib still answered `is_downloading_completed`
     * with the old path, so previews of freshly uploaded files never loaded.
     */
    suspend fun forgetLocalFile(fileId: Int): Boolean = runCatching {
        val response = session.call(
            buildJsonObject {
                put("@type", "deleteFile")
                put("file_id", fileId)
            },
        )
        requireOk(response)
        true
    }.getOrDefault(false)

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
        val copy = forwarded["messages"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
        val copyId = copy?.get("id")?.jsonPrimitive?.longOrNull ?: return false

        // The copy gets the folder caption, so it stays grouped after automatic
        // topics are switched off. A caption that cannot be written does not
        // stop the move: the file already sits in the right topic.
        val captionError = editCaption(chatId, copyId, folder)
        if (captionError != null) {
            logDiagnostic(
                LOG_TAG,
                "caption '$folder' gagal ditulis pada pesan $copyId: $captionError",
            )
        }

        val deleted = runCatching { deleteMessages(chatId, listOf(messageId)) }.isSuccess
        if (!deleted) {
            logDiagnostic(LOG_TAG, "pesan asli $messageId gagal dihapus setelah disalin ke '$folder'")
        }
        return true
    }

    /**
     * Writes the folder caption onto the forwarded copy.
     *
     * The copy is still being sent when `forwardMessages` returns, and TDLib
     * refuses to edit a message in that state with "Message can't be edited",
     * which is why every caption of a tidy pass used to be lost. The message is
     * waited for and the edit retried before the caption is given up on.
     *
     * Returns the TDLib error when it still fails, so the log says why instead of
     * only that it failed. A caption Telegram permanently rejects (a content type
     * it cannot caption, for example) is not retried.
     */
    private suspend fun editCaption(chatId: Long, messageId: Long, folder: String): String? {
        var lastError: String? = null
        repeat(CAPTION_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                awaitSent(chatId, messageId)
                delay(CAPTION_RETRY_MILLIS)
            }
            val response = runCatching {
                session.call(
                    buildJsonObject {
                        put("@type", "editMessageCaption")
                        put("chat_id", chatId)
                        put("message_id", messageId)
                        put(
                            "caption",
                            buildJsonObject {
                                put("text", folder)
                                put("entities", buildJsonArray {})
                            },
                        )
                    },
                )
            }.getOrElse { failure ->
                lastError = failure.message ?: "tanpa keterangan"
                return@repeat
            }
            if (response["@type"]?.jsonPrimitive?.contentOrNull != "error") return null
            lastError = response["message"]?.jsonPrimitive?.contentOrNull ?: "tanpa keterangan"
            if (lastError?.contains("can't be edited", ignoreCase = true) != true) {
                return lastError
            }
        }
        return lastError
    }

    /** Waits until TDLib finished sending [messageId]; a pending copy cannot be edited. */
    private suspend fun awaitSent(chatId: Long, messageId: Long) {
        repeat(SEND_WAIT_ATTEMPTS) {
            val message = runCatching {
                session.call(
                    buildJsonObject {
                        put("@type", "getMessage")
                        put("chat_id", chatId)
                        put("message_id", messageId)
                    },
                )
            }.getOrNull() ?: return
            if (message["@type"]?.jsonPrimitive?.contentOrNull == "error") return
            val state = message["sending_state"]
            if (state == null || state is JsonNull) return
            delay(SEND_WAIT_MILLIS)
        }
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
        // A fixed fifteen minute budget failed large files on slow connections
        // while TDLib was still sending. Watch the progress itself instead, the
        // same way downloads do: a transfer that keeps growing may run as long
        // as it needs, and one that stops moving is reported. The window is wide
        // because uploads queue behind each other, and a queued file reports no
        // progress at all until its turn comes.
        var lastUploaded = -1L
        var lastProgressAt = TimeSource.Monotonic.markNow()
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
                return
            }

            if (uploaded != lastUploaded) {
                lastUploaded = uploaded
                lastProgressAt = TimeSource.Monotonic.markNow()
            } else if (lastProgressAt.elapsedNow() > UPLOAD_STALL_TIMEOUT) {
                error("Unggahan berhenti merespons. Periksa koneksi lalu coba lagi.")
            }

            delay(PROGRESS_POLL_MILLIS)
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
        const val POLL_INTERVAL_MILLIS = 750L
        const val PROGRESS_POLL_MILLIS = 500L

        /** How often a dead local record is forgiven before the download fails. */
        const val MAX_STALE_LOCAL_RETRIES = 1

        /** A download that stops growing for this long is treated as broken. */
        val DOWNLOAD_STALL_TIMEOUT = 5.minutes

        /**
         * The same for uploads, wider because a queued file reports nothing
         * until the transfer in front of it has finished.
         */
        val UPLOAD_STALL_TIMEOUT = 30.minutes

        /** Caption writes tried per forwarded copy before the caption is given up. */
        const val CAPTION_ATTEMPTS = 4
        const val CAPTION_RETRY_MILLIS = 700L

        /** How long a just-forwarded copy may take to be sent (10 seconds). */
        const val SEND_WAIT_ATTEMPTS = 20
        const val SEND_WAIT_MILLIS = 500L
    }
}

/** TDLib rejects a deleteMessages call carrying more ids than this. */
internal const val MESSAGES_PER_DELETE = 100

/** Splits ids into the batches one deleteMessages request may carry. */
internal fun deleteBatches(messageIds: List<Long>): List<List<Long>> =
    messageIds.chunked(MESSAGES_PER_DELETE)
