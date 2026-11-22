package com.unlimitedcloud.log

/**
 * Writes a diagnostic line to the platform log: logcat on Android, stdout on desktop.
 *
 * Only message types and errors are logged by the TDLib session, never request
 * payloads, so phone numbers, codes and passwords stay out of the log.
 */
expect fun platformLog(tag: String, message: String)

/** Tag used by every diagnostic line of the app. */
const val LOG_TAG = "UnlimitedCloud"
