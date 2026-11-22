package com.unlimitedcloud.tdjson

import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.config.TelegramConfig
import com.unlimitedcloud.platform.PlatformStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * TDLib authorization state machine shared by every platform.
 *
 * Updates are collected in a coroutine separate from the transport receive loop,
 * so the handlers may suspend on [TdJsonSession.call] without stalling updates.
 */
class TdLibTelegramEngine(
    private val session: TdJsonSession,
    private val config: TelegramConfig,
    private val scope: CoroutineScope,
) : TelegramEngine {

    private val state = MutableStateFlow<AuthUiState>(AuthUiState.Initializing)
    override val authState: StateFlow<AuthUiState> = state.asStateFlow()

    private var collector: Job? = null

    override fun start() {
        if (collector != null) return
        session.start()
        collector = scope.launch {
            session.updates.collect { update -> handle(update) }
        }
    }

    override fun stop() {
        collector?.cancel()
        collector = null
        session.stop()
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

            "error" -> state.value = AuthUiState.Failure(
                update["message"]?.jsonPrimitive?.contentOrNull ?: "TDLib error",
            )
        }
    }

    private suspend fun handleAuthorizationState(authorizationState: JsonObject) {
        when (authorizationState["@type"]?.jsonPrimitive?.contentOrNull) {
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

            "authorizationStateReady" -> state.value = AuthUiState.Ready(userId = currentUserId())
            "authorizationStateLoggingOut" -> state.value = AuthUiState.LoggingOut
            "authorizationStateClosing", "authorizationStateClosed" -> state.value = AuthUiState.Closed
        }
    }

    private suspend fun currentUserId(): Long {
        val me = runCatching { session.call(buildJsonObject { put("@type", "getMe") }) }.getOrNull()
            ?: return 0L
        return me["id"]?.jsonPrimitive?.longOrNull ?: 0L
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
            put("application_version", "1.0.0")
            put("enable_storage_optimizer", true)
            put("ignore_file_names", false)
        }
    }

    private suspend fun call(type: String, builder: JsonObjectBuilder.() -> Unit) {
        val request = buildJsonObject {
            put("@type", type)
            builder()
        }

        val response = runCatching { session.call(request) }.getOrElse {
            state.value = AuthUiState.Failure(it.message ?: "TDLib tidak merespons")
            return
        }

        if (response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
            state.value = AuthUiState.Failure(
                response["message"]?.jsonPrimitive?.contentOrNull ?: "TDLib error",
            )
        }
    }
}
