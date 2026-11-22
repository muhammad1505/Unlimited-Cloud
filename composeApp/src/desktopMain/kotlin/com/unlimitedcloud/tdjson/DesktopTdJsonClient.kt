package com.unlimitedcloud.tdjson

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/**
 * Desktop transport backed by TDLib's JSON C API (`tdjson`).
 *
 * The native library is resolved from `java.library.path`; if it is not there it
 * is extracted from a bundled `tdlight-natives-*` jar sitting on the classpath.
 */
actual fun createTdJsonClient(): TdJsonClient = DesktopTdJsonClient()

private interface TdJsonLibrary : Library {
    fun td_json_client_create(): Pointer?

    fun td_json_client_send(client: Pointer, request: String)

    fun td_json_client_receive(client: Pointer, timeout: Double): String?

    fun td_json_client_execute(client: Pointer?, request: String): String?

    fun td_json_client_destroy(client: Pointer)
}

private class DesktopTdJsonClient : TdJsonClient {

    private val library: TdJsonLibrary by lazy { loadLibrary() }

    private val client: Pointer? by lazy { library.td_json_client_create() }

    override fun send(query: String) {
        val pointer = client ?: return
        library.td_json_client_send(pointer, query)
    }

    override fun execute(query: String): String? = library.td_json_client_execute(null, query)

    override fun receive(timeoutSeconds: Double): String? {
        val pointer = client ?: return null
        return library.td_json_client_receive(pointer, timeoutSeconds)
    }

    override fun close() {
        client?.let { library.td_json_client_destroy(it) }
    }

    private fun loadLibrary(): TdJsonLibrary {
        runCatching { return Native.load("tdjson", TdJsonLibrary::class.java) }

        val extracted = extractBundledLibrary()
            ?: error(
                "libtdjson tidak ditemukan: pasang artefak tdlight-natives untuk OS ini " +
                    "atau set -Djna.library.path",
            )
        return Native.load(extracted.absolutePath, TdJsonLibrary::class.java)
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
