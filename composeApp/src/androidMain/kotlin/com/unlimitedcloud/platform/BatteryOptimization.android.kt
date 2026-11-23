package com.unlimitedcloud.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberBatteryOptimizationController(): BatteryOptimizationController {
    val context = AndroidPlatform.requireContext()
    return remember(context) { AndroidBatteryOptimizationController(context) }
}

private class AndroidBatteryOptimizationController(
    private val context: Context,
) : BatteryOptimizationController {

    override fun snapshot(): BatteryOptimizationSnapshot {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val manufacturer = Build.MANUFACTURER.takeIf { it.isNotBlank() }
        return BatteryOptimizationSnapshot(
            supported = true,
            ignoringOptimizations = ignoring,
            manufacturer = manufacturer,
            oemHint = OemSettings.forManufacturer(manufacturer)?.label,
        )
    }

    /**
     * Opens the AOSP battery optimization list. The action that asks the system
     * to exempt the app directly is deliberately not used: it needs a restricted
     * permission Unlimited Cloud does not declare, and Play expects the user to
     * make that choice in system settings.
     */
    override fun openSystemSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
        candidates.forEach { intent ->
            if (launch(intent)) return
        }
    }

    override fun openOemSettings(): Boolean {
        val oem = OemSettings.forManufacturer(Build.MANUFACTURER.orEmpty()) ?: return false
        return oem.components.any { component ->
            launch(Intent().setComponent(component))
        }
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/**
 * Autostart screens are not part of the Android SDK; these are the component
 * names commonly used by OEM skins. Every entry is probed with a real
 * `startActivity`, so an unknown or missing screen simply falls through to the
 * AOSP power settings instead of crashing.
 */
private data class OemSettings(
    val label: String,
    val components: List<ComponentName>,
) {
    companion object {
        fun forManufacturer(manufacturer: String?): OemSettings? {
            val name = manufacturer.orEmpty().lowercase()
            return when {
                listOf("xiaomi", "redmi", "poco").any { name.contains(it) } -> OemSettings(
                    "Autostart MIUI/HyperOS",
                    listOf(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                        ),
                    ),
                )

                listOf("oppo", "realme", "oneplus").any { name.contains(it) } -> OemSettings(
                    "Autostart ColorOS/realme UI",
                    listOf(
                        ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                        ),
                        ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity",
                        ),
                    ),
                )

                listOf("vivo", "iqoo").any { name.contains(it) } -> OemSettings(
                    "Autostart Funtouch/OriginOS",
                    listOf(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                        ),
                    ),
                )

                listOf("huawei", "honor").any { name.contains(it) } -> OemSettings(
                    "Autostart EMUI/MagicOS",
                    listOf(
                        ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                        ),
                    ),
                )

                name.contains("samsung") -> OemSettings(
                    "Perawatan baterai Samsung",
                    listOf(
                        ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity",
                        ),
                    ),
                )

                name.contains("asus") -> OemSettings(
                    "Autostart ASUS",
                    listOf(
                        ComponentName(
                            "com.asus.mobilemanager",
                            "com.asus.mobilemanager.autostart.AutoStartActivity",
                        ),
                    ),
                )

                name.contains("meizu") -> OemSettings(
                    "Autostart Flyme",
                    listOf(
                        ComponentName(
                            "com.meizu.safe",
                            "com.meizu.safe.permission.SmartBGActivity",
                        ),
                    ),
                )

                else -> null
            }
        }
    }
}
