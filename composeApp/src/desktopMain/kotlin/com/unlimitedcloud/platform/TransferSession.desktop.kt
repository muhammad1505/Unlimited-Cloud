package com.unlimitedcloud.platform

/** Desktop keeps running while a transfer is in flight, so there is nothing to hold. */
actual fun createTransferSession(): TransferSession = object : TransferSession {
    override fun begin() = Unit

    override fun end() = Unit
}
