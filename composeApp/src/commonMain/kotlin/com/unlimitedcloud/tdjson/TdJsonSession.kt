package com.unlimitedcloud.tdjson

import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
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
    private val logTraffic: Boolean = true,
    private val tdlibVerboseLevel: Int = 1,
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
        if (logTraffic) {
            // TDLib's own log stays quiet by default; level 1 surfaces errors and
            // warnings in logcat without flooding it. Raise to 5 for a full trace.
            runCatching {
                client.execute("""{"@type":"setLogVerboseLevel","new_verbose_level":$tdlibVerboseLevel}""")
            }
        }
        receiver = scope.launch(Dispatchers.Default) {
            while (isActive) {
                // A missing or broken native library must not spin: back off before
                // retrying so a device without libtdjson stays idle instead of
                // flooding the log with exceptions.
                val raw = runCatching { client.receive(RECEIVE_TIMEOUT_SECONDS) }.getOrNull()
                if (raw == null) {
                    delay(IDLE_RETRY_MILLIS)
                    continue
                }
                val update = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                if (update == null) {
                    if (logTraffic) logDiagnostic(LOG_TAG, "update tidak bisa di-parse: ${raw.take(120)}")
                    continue
                }
                val extra = update["@extra"]?.jsonPrimitive?.contentOrNull
                if (logTraffic) {
                    val type = update["@type"]?.jsonPrimitive?.contentOrNull
                    if (type != null && type !in QUIET_UPDATES) logDiagnostic(LOG_TAG, "<- $type")
                }
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
        if (logTraffic) logDiagnostic(LOG_TAG, "-> ${request["@type"]?.jsonPrimitive?.contentOrNull}")
        return try {
            val response = withTimeout(timeoutMillis) { deferred.await() }
            if (logTraffic && response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
                logDiagnostic(LOG_TAG, "<- error: ${response["message"]?.jsonPrimitive?.contentOrNull}")
            }
            response
        } finally {
            lock.withLock { pending.remove(extra) }
        }
    }

    fun stop() {
        if (logTraffic) logDiagnostic(LOG_TAG, "sesi TDLib ditutup")
        receiver?.cancel()
        receiver = null
        client.close()
    }

    private companion object {
        const val RECEIVE_TIMEOUT_SECONDS = 1.0
        const val IDLE_RETRY_MILLIS = 250L
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /** High-frequency progress updates would drown out everything else. */
        val QUIET_UPDATES = setOf("updateFile")
    }
}
