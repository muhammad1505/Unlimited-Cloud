package com.unlimitedcloud.tdjson

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * Desktop transport backed by TDLib's JSON C API (`tdjson`).
 *
 * The native library is resolved from `java.library.path`; if it is not there it
 * is extracted from a bundled `tdjson` resource sitting on the classpath. The
 * release packages include that resource, so a normal install needs no separate
 * TDLib setup.
 */
actual fun createTdJsonClient(): TdJsonClient {
    // JNA marshals String with the platform encoding unless told otherwise, and
    // Windows with JDK 17 is not UTF-8. Non-ASCII file names reached TDLib
    // mangled, so those uploads failed with "file not found".
    System.setProperty("jna.encoding", "UTF-8")
    return DesktopTdJsonClient()
}

private interface TdJsonLibrary : Library {
    fun td_json_client_create(): Pointer?

    fun td_json_client_send(client: Pointer, request: String)

    fun td_json_client_receive(client: Pointer, timeout: Double): String?

    fun td_json_client_execute(client: Pointer?, request: String): String?

    fun td_json_client_destroy(client: Pointer)
}

private class DesktopTdJsonClient : TdJsonClient {

    private val library: TdJsonLibrary by lazy { loadLibrary() }

    private val createLock = Any()

    /**
     * A plain field instead of `by lazy`: [close] destroys the native handle, and
     * a session that starts again afterwards needs a new one. Keeping the lazy
     * value meant sending through a freed pointer (use-after-free).
     */
    private var clientPointer: Pointer? = null

    private fun obtainClient(): Pointer = synchronized(createLock) {
        clientPointer?.let { return@synchronized it }
        val created = library.td_json_client_create()
            ?: error("TDLib berhasil dimuat tetapi gagal membuat klien.")
        clientPointer = created
        created
    }

    override fun send(query: String) {
        library.td_json_client_send(obtainClient(), query)
    }

    override fun execute(query: String): String? = library.td_json_client_execute(null, query)

    override fun receive(timeoutSeconds: Double): String? =
        library.td_json_client_receive(obtainClient(), timeoutSeconds)

    override fun close() {
        // Do not touch the library lazily: a session that failed to load the
        // native must still be closable without trying to load it again.
        val pointer = synchronized(createLock) {
            val current = clientPointer
            clientPointer = null
            current
        } ?: return
        runCatching { library.td_json_client_destroy(pointer) }
    }

    private fun loadLibrary(): TdJsonLibrary {
        runCatching { return Native.load("tdjson", TdJsonLibrary::class.java) }

        val extracted = extractBundledLibrary()
            ?: error(
                "Pustaka TDLib (libtdjson) tidak ditemukan. Paket desktop resmi sudah " +
                    "menyertakannya; untuk build lokal jalankan " +
                    "scripts/fetch-tdlib-desktop.sh lalu build ulang.",
            )
        return runCatching { Native.load(extracted.absolutePath, TdJsonLibrary::class.java) }
            .getOrElse { failure ->
                error(
                    "Pustaka TDLib ditemukan tetapi gagal dimuat: " +
                        (failure.message ?: failure::class.simpleName),
                )
            }
    }

    private fun extractBundledLibrary(): File? {
        val candidates = listOf(
            "libtdjson.so",
            "/libtdjson.so",
            "libtdjson.dylib",
            "/libtdjson.dylib",
            "tdjson.dll",
            "/tdjson.dll",
        )

        for (name in candidates) {
            val stream = DesktopTdJsonClient::class.java.getResourceAsStream(name) ?: continue
            val suffix = "." + name.substringAfterLast('.')
            val target = File.createTempFile("tdjson", suffix)
            stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            target.deleteOnExit()
            return target
        }
        return null
    }
}
