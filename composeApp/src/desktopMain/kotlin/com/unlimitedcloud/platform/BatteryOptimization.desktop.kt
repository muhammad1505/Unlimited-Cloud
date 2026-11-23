package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberBatteryOptimizationController(): BatteryOptimizationController =
    remember { DesktopBatteryOptimizationController }

private object DesktopBatteryOptimizationController : BatteryOptimizationController {
    override fun snapshot(): BatteryOptimizationSnapshot = BatteryOptimizationSnapshot(
        supported = false,
        ignoringOptimizations = true,
    )

    override fun openSystemSettings() = Unit

    override fun openOemSettings(): BatterySettingsDestination = BatterySettingsDestination.Unavailable

    override fun openAppInfo() = Unit
}
