package com.unlimitedcloud.tdjson

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Android transport backed by TDLib's JSON C API (`libtdjson.so`).
 *
 * JNA is used on Android for the same protocol as desktop. The native library is
 * expected at `androidApp/src/main/jniLibs/<abi>/libtdjson.so`, which
 * `scripts/fetch-tdlib-android.sh` populates during the release build; without it
 * the client fails with a readable message instead of crashing the process.
 */
actual fun createTdJsonClient(): TdJsonClient {
    // JNA marshals String with the platform encoding unless told otherwise. Set
    // it before the library loads so non-ASCII file names reach TDLib unmangled.
    System.setProperty("jna.encoding", "UTF-8")
    return AndroidTdJsonClient()
}

private interface TdJsonLibrary : Library {
    fun td_json_client_create(): Pointer?

    fun td_json_client_send(client: Pointer, request: String)

    fun td_json_client_receive(client: Pointer, timeout: Double): String?

    fun td_json_client_execute(client: Pointer?, request: String): String?

    fun td_json_client_destroy(client: Pointer)
}

private object AndroidTdJsonLibrary {
    val instance: TdJsonLibrary by lazy {
        runCatching { Native.load("tdjson", TdJsonLibrary::class.java) }.getOrElse {
            error(
                "libtdjson.so tidak ditemukan. Jalankan scripts/fetch-tdlib-android.sh " +
                    "untuk mengisi androidApp/src/main/jniLibs/<abi>/libtdjson.so lalu build ulang.",
            )
        }
    }
}

private class AndroidTdJsonClient : TdJsonClient {

    private val createLock = Any()

    /**
     * A plain field instead of `by lazy`: [close] destroys the native handle, and
     * a session that starts again afterwards has to create a fresh one. The lazy
     * value used to keep pointing at the freed handle, so sending after a restart
     * was a use-after-free.
     */
    private var clientPointer: Pointer? = null

    private fun obtainClient(): Pointer = synchronized(createLock) {
        clientPointer?.let { return@synchronized it }
        val created = AndroidTdJsonLibrary.instance.td_json_client_create()
            ?: error("TDLib berhasil dimuat tetapi gagal membuat klien.")
        clientPointer = created
        created
    }

    override fun send(query: String) {
        AndroidTdJsonLibrary.instance.td_json_client_send(obtainClient(), query)
    }

    override fun execute(query: String): String? =
        AndroidTdJsonLibrary.instance.td_json_client_execute(null, query)

    override fun receive(timeoutSeconds: Double): String? =
        AndroidTdJsonLibrary.instance.td_json_client_receive(obtainClient(), timeoutSeconds)

    override fun close() {
        // Never create a client just to close it: a session that failed to load
        // its native must stay closable without throwing a second link error.
        val pointer = synchronized(createLock) {
            val current = clientPointer
            clientPointer = null
            current
        } ?: return
        runCatching { AndroidTdJsonLibrary.instance.td_json_client_destroy(pointer) }
    }
}
