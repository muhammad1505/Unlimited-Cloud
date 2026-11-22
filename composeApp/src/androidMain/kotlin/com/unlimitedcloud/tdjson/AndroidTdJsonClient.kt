package com.unlimitedcloud.tdjson

/**
 * Android transport for the TDLib JSON API.
 *
 * Telegram publishes no JVM artifact on Maven, so the app expects TDLib's JSON
 * wrapper (`org.drinkless.tdlib.Client`, the wrapper from TDLib's Java example)
 * plus `libtdjson.so` to be bundled with the APK. It is reached reflectively so
 * `androidMain` compiles before those binaries exist; until they do, calls fail
 * soft and the UI reports that TDLib is unavailable.
 */
actual fun createTdJsonClient(): TdJsonClient = AndroidTdJsonClient()

private class AndroidTdJsonClient : TdJsonClient {

    private val clientClass: Class<*>? =
        runCatching { Class.forName("org.drinkless.tdlib.Client") }.getOrNull()

    private val instance: Any? by lazy { invokeStatic("create") }

    override fun send(query: String) {
        val target = instance ?: return
        invokeStatic("send", target, query) ?: invokeInstance(target, "send", query)
    }

    override fun execute(query: String): String? = invokeStatic("execute", query) as? String

    override fun receive(timeoutSeconds: Double): String? {
        val target = instance ?: return null
        return invokeInstance(target, "receive", timeoutSeconds) as? String
    }

    override fun close() {
        instance?.let { invokeInstance(it, "close") }
    }

    private fun invokeStatic(name: String, vararg args: Any?): Any? {
        val cls = clientClass ?: return null
        val method = cls.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: return null
        return runCatching { method.invoke(null, *args) }.getOrNull()
    }

    private fun invokeInstance(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }
}
