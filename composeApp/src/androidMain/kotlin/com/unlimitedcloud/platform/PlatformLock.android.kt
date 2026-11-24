package com.unlimitedcloud.platform

internal actual fun <T> synchronizedBlock(lock: Any, block: () -> T): T = synchronized(lock, block)
