package com.unlimitedcloud.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform independent view of the Telegram session.
 *
 * The implementation lives in `commonMain` and talks to TDLib through its JSON
 * interface, so Android and Desktop share the whole state machine; only the
 * transport underneath differs.
 */
interface TelegramEngine {
    val authState: StateFlow<AuthUiState>

    /** Starts receiving TDLib updates. Safe to call more than once. */
    fun start()

    fun submitPhoneNumber(phoneNumber: String)

    fun submitCode(code: String)

    fun submitPassword(password: String)

    fun submitRegistration(firstName: String, lastName: String)

    fun logout()

    fun stop()
}
