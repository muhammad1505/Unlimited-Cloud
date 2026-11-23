package com.unlimitedcloud.tdjson

import com.unlimitedcloud.AppBuildInfo
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.config.TelegramConfig
import com.unlimitedcloud.log.LOG_TAG
import com.unlimitedcloud.log.logDiagnostic
import com.unlimitedcloud.platform.PlatformStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * TDLib authorization state machine shared by every platform.
 *
 * Updates are collected in a coroutine separate from the transport receive loop,
 * so the handlers may suspend on [TdJsonSession.call] without stalling updates.
 * Recoverable request errors are exposed separately from the auth step, which
 * lets the UI keep the typed phone number/code/password on screen.
 */
class TdLibTelegramEngine(
    private val session: TdJsonSession,
    private val config: TelegramConfig,
    private val scope: CoroutineScope,
) : TelegramEngine {

    private val state = MutableStateFlow<AuthUiState>(AuthUiState.Initializing)
    override val authState: StateFlow<AuthUiState> = state.asStateFlow()

    private val errorState = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = errorState.asStateFlow()

    private val busyState = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = busyState.asStateFlow()

    private var collector: Job? = null
    private var watchdog: Job? = null

    override fun start() {
        if (collector != null) return
        session.start()
        collector = scope.launch {
            session.updates.collect { update -> handle(update) }
        }
        // Without natives or network TDLib stays silent forever. Surface a
        // recoverable failure instead of an endless splash screen; a later
        // authorization update still replaces it automatically.
        watchdog = scope.launch {
            delay(INITIALIZATION_TIMEOUT_MILLIS)
            if (state.value is AuthUiState.Initializing) {
                state.value = AuthUiState.Failure(
                    "Mesin Telegram belum merespons. Periksa koneksi internet, " +
                        "lalu tutup dan buka kembali aplikasi.",
                )
            }
        }
    }

    override fun stop() {
        collector?.cancel()
        collector = null
        watchdog?.cancel()
        watchdog = null
        session.stop()
    }

    override fun clearError() {
        errorState.value = null
    }

    override fun submitPhoneNumber(phoneNumber: String) {
        scope.launch {
            call("setAuthenticationPhoneNumber") { put("phone_number", phoneNumber.trim()) }
        }
    }

    override fun submitCode(code: String) {
        scope.launch { call("checkAuthenticationCode") { put("code", code.trim()) } }
    }

    override fun submitPassword(password: String) {
        scope.launch { call("checkAuthenticationPassword") { put("password", password) } }
    }

    override fun submitRegistration(firstName: String, lastName: String) {
        scope.launch {
            call("registerUser") {
                put("first_name", firstName.trim())
                put("last_name", lastName.trim())
            }
        }
    }

    override fun logout() {
        scope.launch { call("logOut") {} }
    }

    private suspend fun handle(update: JsonObject) {
        when (update["@type"]?.jsonPrimitive?.contentOrNull) {
            "updateAuthorizationState" ->
                update["authorization_state"]?.jsonObject?.let { handleAuthorizationState(it) }

            "error" -> errorState.value =
                update["message"]?.jsonPrimitive?.contentOrNull ?: "TDLib error"
        }
    }

    private suspend fun handleAuthorizationState(authorizationState: JsonObject) {
        val stateType = authorizationState["@type"]?.jsonPrimitive?.contentOrNull
        logDiagnostic(LOG_TAG, "auth: $stateType")
        when (stateType) {
            "authorizationStateWaitTdlibParameters" -> sendTdlibParameters()
            "authorizationStateWaitPhoneNumber" -> state.value = AuthUiState.WaitingForPhoneNumber
            "authorizationStateWaitCode" -> {
                val codeLength = authorizationState["code_info"]
                    ?.jsonObject
                    ?.get("code_length")
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: 0
                state.value = AuthUiState.WaitingForCode(codeLength = codeLength, isRegistration = false)
            }

            "authorizationStateWaitRegistration" -> state.value = AuthUiState.WaitingForRegistration
            "authorizationStateWaitPassword" -> state.value = AuthUiState.WaitingForPassword(
                hint = authorizationState["password_hint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )

            "authorizationStateReady" -> state.value = currentUser()
            "authorizationStateLoggingOut" -> state.value = AuthUiState.LoggingOut
            "authorizationStateClosing", "authorizationStateClosed" -> state.value = AuthUiState.Closed
        }
    }

    private suspend fun currentUser(): AuthUiState.Ready {
        val me = runCatching { session.call(buildJsonObject { put("@type", "getMe") }) }.getOrNull()
            ?: return AuthUiState.Ready(userId = 0L)

        val firstName = me["first_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val lastName = me["last_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val displayName = listOf(firstName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val activeUsername = me["usernames"]
            ?.jsonObject
            ?.get("active_usernames")
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.firstOrNull()
        val legacyUsername = me["username"]?.jsonPrimitive?.contentOrNull

        return AuthUiState.Ready(
            userId = me["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            displayName = displayName,
            username = activeUsername ?: legacyUsername,
            phoneNumber = me["phone_number"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun sendTdlibParameters() {
        if (!config.isConfigured) {
            state.value = AuthUiState.Failure(
                "TELEGRAM_API_ID / TELEGRAM_API_HASH belum diisi. " +
                    "Simpan di local.properties atau environment variable.",
            )
            return
        }

        PlatformStorage.ensureDirectories()

        call("setTdlibParameters") {
            put("api_id", config.apiId)
            put("api_hash", config.apiHash)
            put("database_directory", PlatformStorage.tdlibDatabaseDirectory)
            put("files_directory", PlatformStorage.tdlibFilesDirectory)
            put("database_encryption_key", "")
            put("use_message_database", true)
            put("use_secret_chats", true)
            put("use_chat_info_database", true)
            put("system_language_code", "en")
            put("device_model", "Unlimited Cloud")
            put("system_version", "1.0")
            put("application_version", AppBuildInfo.VERSION_NAME)
            put("enable_storage_optimizer", true)
            put("ignore_file_names", false)
        }
    }

    private suspend fun call(type: String, builder: JsonObjectBuilder.() -> Unit) {
        val request = buildJsonObject {
            put("@type", type)
            builder()
        }

        busyState.value = true
        try {
            val response = runCatching { session.call(request) }.getOrElse { failure ->
                errorState.value = failure.message ?: "TDLib tidak merespons"
                return
            }

            if (response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
                errorState.value = response["message"]?.jsonPrimitive?.contentOrNull
                    ?: "TDLib menolak permintaan"
            } else {
                errorState.value = null
            }
        } finally {
            busyState.value = false
        }
    }

    private companion object {
        const val INITIALIZATION_TIMEOUT_MILLIS = 20_000L
    }
}
