package com.unlimitedcloud.tdjson

/**
 * Minimal blocking transport over TDLib's JSON API.
 *
 * TDLib exposes the same JSON protocol on every platform; only the way the
 * native client is reached differs, which keeps the whole authorization state
 * machine in `commonMain`.
 */
interface TdJsonClient {
    fun send(query: String)

    fun execute(query: String): String?

    fun receive(timeoutSeconds: Double): String?

    fun close()
}

expect fun createTdJsonClient(): TdJsonClient
