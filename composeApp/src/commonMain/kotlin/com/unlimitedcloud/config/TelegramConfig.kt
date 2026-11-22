package com.unlimitedcloud.config

/**
 * Telegram application credentials.
 *
 * Values are injected at build time from `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`
 * (Gradle property, environment variable, or `local.properties`). When none is
 * present the build falls back to dummies so CI can compile, and the engine
 * refuses to connect with a readable message instead of hanging.
 */
data class TelegramConfig(
    val apiId: Int,
    val apiHash: String,
) {
    val isConfigured: Boolean
        get() = apiId != 0 && apiHash.isNotBlank() && apiHash != DUMMY_API_HASH

    companion object {
        const val DUMMY_API_HASH = "00000000000000000000000000000000"

        fun fromBuild(): TelegramConfig = TelegramConfig(
            apiId = TelegramCredentials.API_ID,
            apiHash = TelegramCredentials.API_HASH,
        )
    }
}
