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
actual fun createTdJsonClient(): TdJsonClient = AndroidTdJsonClient()

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

    private val client: Pointer? by lazy { AndroidTdJsonLibrary.instance.td_json_client_create() }

    override fun send(query: String) {
        val pointer = client ?: return
        AndroidTdJsonLibrary.instance.td_json_client_send(pointer, query)
    }

    override fun execute(query: String): String? =
        AndroidTdJsonLibrary.instance.td_json_client_execute(null, query)

    override fun receive(timeoutSeconds: Double): String? {
        val pointer = client ?: return null
        return AndroidTdJsonLibrary.instance.td_json_client_receive(pointer, timeoutSeconds)
    }

    override fun close() {
        client?.let { AndroidTdJsonLibrary.instance.td_json_client_destroy(it) }
    }
}
