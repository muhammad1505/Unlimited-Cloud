package com.unlimitedcloud.auth

/** Everything the UI needs to know about the TDLib authorization state machine. */
sealed interface AuthUiState {
    data object Initializing : AuthUiState

    data object WaitingForPhoneNumber : AuthUiState

    data class WaitingForCode(
        val codeLength: Int,
        val isRegistration: Boolean,
    ) : AuthUiState

    data object WaitingForRegistration : AuthUiState

    data class WaitingForPassword(val hint: String) : AuthUiState

    data class Ready(
        val userId: Long,
        val displayName: String = "",
        val username: String? = null,
        val phoneNumber: String? = null,
    ) : AuthUiState

    data object LoggingOut : AuthUiState

    data object Closed : AuthUiState

    data class Failure(val message: String) : AuthUiState
}
