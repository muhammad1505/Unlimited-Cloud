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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * A terminal transport failure, such as a missing `libtdjson` native.
     *
     * Kept separate from recoverable TDLib `error` updates: the authorization
     * state machine cannot continue when the native never loaded, so the UI
     * needs to explain that instead of waiting for the 20 second watchdog.
     */
    private val fatalErrorState = MutableStateFlow<String?>(null)
    val fatalError: StateFlow<String?> = fatalErrorState.asStateFlow()

    private val lock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private var counter = 0L
    private var receiver: Job? = null

    /** Set by [stop]; a stopped session owns a destroyed native client. */
    private var closed = false

    /**
     * Loads the native and starts the receive loop.
     *
     * @return `false` when the transport could not be initialized; the reason is
     * available through [fatalError].
     */
    fun start(): Boolean {
        if (closed) {
            // stop() destroyed the native client; starting again would call into
            // a freed handle (the old pointer was still cached).
            fatalErrorState.value = "Sesi TDLib sudah ditutup. Buka ulang aplikasi."
            return false
        }
        if (receiver != null) return true

        // `execute` forces the platform client to load its native library before
        // a receive loop exists. A missing libtdjson otherwise looks exactly like
        // a silent network problem because every receive call was swallowed.
        val preflight = runCatching {
            client.execute(
                """{"@type":"setLogVerboseLevel","new_verbose_level":$tdlibVerboseLevel}""",
            )
        }
        val preflightFailure = preflight.exceptionOrNull()
        if (preflightFailure != null) {
            fatalErrorState.value = preflightFailure.message
                ?: preflightFailure::class.simpleName
                ?: "Pustaka TDLib tidak dapat dimuat."
            return false
        }

        fatalErrorState.value = null
        receiver = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val received = runCatching { client.receive(RECEIVE_TIMEOUT_SECONDS) }
                val raw = received.getOrNull()
                val failure = received.exceptionOrNull()
                if (failure != null) {
                    // After stop() the receiver is cancelled and the native is
                    // closed while this call may still unwind; that is normal.
                    if (isActive) {
                        fatalErrorState.value = failure.message
                            ?: failure::class.simpleName
                            ?: "Pustaka TDLib berhenti merespons."
                    }
                    break
                }
                if (raw == null) {
                    delay(IDLE_RETRY_MILLIS)
                    continue
                }
                val update = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                if (update == null) {
                    if (logTraffic) {
                        // Never echo the payload: TDLib updates may contain chat
                        // text, names, phone numbers or file metadata.
                        logDiagnostic(LOG_TAG, "update TDLib tidak bisa di-parse (${raw.length} byte)")
                    }
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
        return true
    }

    suspend fun call(request: JsonObject, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): JsonObject {
        val extra = lock.withLock { "uc-${++counter}" }
        val deferred = CompletableDeferred<JsonObject>()
        lock.withLock { pending[extra] = deferred }
        val payload = buildJsonObject {
            request.forEach { (key, value) -> put(key, value) }
            put("@extra", JsonPrimitive(extra))
        }
        return try {
            // Inside the try: a native send failure used to leave the deferred
            // registered forever, so the entry leaked and the id was never freed.
            client.send(payload.toString())
            if (logTraffic) logDiagnostic(LOG_TAG, "-> ${request["@type"]?.jsonPrimitive?.contentOrNull}")
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
        closed = true

        // Callers used to hang for the full request timeout when the session
        // stopped mid-request; fail them right away instead.
        val abandoned = if (lock.tryLock()) {
            try {
                pending.values.toList().also { pending.clear() }
            } finally {
                lock.unlock()
            }
        } else {
            emptyList()
        }
        abandoned.forEach { deferred ->
            deferred.completeExceptionally(IllegalStateException("Sesi TDLib ditutup"))
        }

        // A session that never loaded its native must not make close() try to
        // create a client; both platform clients guard this too.
        runCatching { client.close() }
    }

    private companion object {
        const val RECEIVE_TIMEOUT_SECONDS = 1.0
        const val IDLE_RETRY_MILLIS = 250L
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /** High-frequency progress updates would drown out everything else. */
        val QUIET_UPDATES = setOf("updateFile")
    }
}
