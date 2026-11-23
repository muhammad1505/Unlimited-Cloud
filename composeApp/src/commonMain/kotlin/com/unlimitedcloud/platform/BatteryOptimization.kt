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
)

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

    /** OEM autostart screen, when the manufacturer has one. False if unavailable. */
    fun openOemSettings(): Boolean
}

@Composable
expect fun rememberBatteryOptimizationController(): BatteryOptimizationController
