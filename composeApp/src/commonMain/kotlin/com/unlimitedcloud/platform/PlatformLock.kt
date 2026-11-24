package com.unlimitedcloud.platform

/**
 * Runs [block] while holding the monitor of [lock].
 *
 * `kotlin.synchronized` is JVM-only, but both targets here (Android and desktop)
 * are JVM, so a tiny expect/actual keeps `commonMain` free of platform imports
 * while still letting shared code serialise the settings file.
 */
internal expect fun <T> synchronizedBlock(lock: Any, block: () -> T): T
