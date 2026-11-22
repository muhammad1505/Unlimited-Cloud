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

    /** Recoverable TDLib errors, shown as a dismissible banner above the active form. */
    val errorMessage: StateFlow<String?>

    /** True while an authorization request is in flight. */
    val busy: StateFlow<Boolean>

    /** Starts receiving TDLib updates. Safe to call more than once. */
    fun start()

    /** Clears the current recoverable error without touching the auth step. */
    fun clearError()

    fun submitPhoneNumber(phoneNumber: String)

    fun submitCode(code: String)

    fun submitPassword(password: String)

    fun submitRegistration(firstName: String, lastName: String)

    fun logout()

    fun stop()
}
