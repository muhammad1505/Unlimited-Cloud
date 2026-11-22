package com.unlimitedcloud.tdjson

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owns the TDLib receive loop and correlates requests with their responses.
 *
 * TDLib answers asynchronously: a request carrying `@extra` is echoed back in the
 * response object, which is how [call] knows which `CompletableDeferred` to
 * complete. Updates without `@extra` are published on [updates].
 */
class TdJsonSession(
    private val client: TdJsonClient,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val updateFlow = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<JsonObject> = updateFlow

    private val lock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private var counter = 0L
    private var receiver: Job? = null

    fun start() {
        if (receiver != null) return
        receiver = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val raw = runCatching { client.receive(RECEIVE_TIMEOUT_SECONDS) }.getOrNull()
                if (raw == null) {
                    delay(50)
                    continue
                }
                val update = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                val extra = update["@extra"]?.jsonPrimitive?.contentOrNull
                if (extra != null) {
                    val deferred = lock.withLock { pending.remove(extra) }
                    if (deferred != null) {
                        deferred.complete(update)
                        continue
                    }
                }
                updateFlow.emit(update)
            }
        }
    }

    suspend fun call(request: JsonObject, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): JsonObject {
        val extra = lock.withLock { "uc-${++counter}" }
        val deferred = CompletableDeferred<JsonObject>()
        lock.withLock { pending[extra] = deferred }
        val payload = buildJsonObject {
            request.forEach { (key, value) -> put(key, value) }
            put("@extra", JsonPrimitive(extra))
        }
        client.send(payload.toString())
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            lock.withLock { pending.remove(extra) }
        }
    }

    fun stop() {
        receiver?.cancel()
        receiver = null
        client.close()
    }

    private companion object {
        const val RECEIVE_TIMEOUT_SECONDS = 1.0
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
    }
}
