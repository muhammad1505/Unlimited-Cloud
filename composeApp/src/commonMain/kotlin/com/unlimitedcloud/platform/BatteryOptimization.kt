package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/** Read-only view of the system power settings relevant to background uploads. */
data class BatteryOptimizationSnapshot(
    /** False on desktop, where Android's Doze rules do not apply. */
    val supported: Boolean,
    /** True when the app is already exempt from Doze / App Standby. */
    val ignoringOptimizations: Boolean,
    val manufacturer: String? = null,
    /** Human-readable name of the OEM autostart screen, when one is known. */
    val oemHint: String? = null,
    /** e.g. "Android 14 (SDK 34)"; null on desktop. */
    val androidVersion: String? = null,
    /** API level, so the UI can explain the rules of that Android release. */
    val sdkInt: Int? = null,
    /** App name as the system shows it, so the settings paths can be named. */
    val appLabel: String? = null,
    /** Application id, the same one listed in the system app info screen. */
    val packageName: String? = null,
)

/** Where an OEM battery shortcut ended up, so the UI can say what opened. */
enum class BatterySettingsDestination {
    /** The manufacturer's own autostart/battery screen. */
    Oem,

    /** Fallback: the system app info screen, which holds the battery settings. */
    AppInfo,

    /** Nothing could be opened (desktop). */
    Unavailable,
}

/**
 * Opens the *system* power screens; it never requests an exemption itself.
 *
 * Google Play restricts `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to a narrow set
 * of app categories, so Unlimited Cloud ships no such permission and instead
 * explains the trade-off and sends the user to the settings screen, where the
 * decision stays with them. The OEM shortcut covers manufacturers whose
 * autostart switch lives outside the AOSP power screen.
 */
interface BatteryOptimizationController {
    fun snapshot(): BatteryOptimizationSnapshot

    /** Battery optimization list / app power settings. */
    fun openSystemSettings()

    /**
     * OEM autostart screen, falling back to app info when the manufacturer's
     * screen is missing or hidden from third-party apps.
     */
    fun openOemSettings(): BatterySettingsDestination

    /** The system app info screen, where per-app battery settings live. */
    fun openAppInfo()
}

@Composable
expect fun rememberBatteryOptimizationController(): BatteryOptimizationController
