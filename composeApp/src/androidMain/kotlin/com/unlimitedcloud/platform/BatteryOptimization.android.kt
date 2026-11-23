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
        val appLabel = runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrNull()
        return BatteryOptimizationSnapshot(
            supported = true,
            ignoringOptimizations = ignoring,
            manufacturer = manufacturer,
            oemHint = OemSettings.forManufacturer(manufacturer)?.label,
            androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            sdkInt = Build.VERSION.SDK_INT,
            appLabel = appLabel,
            packageName = context.packageName,
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
            appInfoIntent(),
        )
        candidates.forEach { intent ->
            if (launch(intent)) return
        }
    }

    override fun openOemSettings(): BatterySettingsDestination {
        val oem = OemSettings.forManufacturer(Build.MANUFACTURER.orEmpty())
        val opened = oem?.intents()?.any { intent -> launch(intent) } == true
        if (opened) return BatterySettingsDestination.Oem

        // OEM screens are often hidden from third-party apps even on phones that
        // have them, so app info is the reliable stop: it lists the app under the
        // same name the system shows and holds the battery settings on Android 10+.
        openAppInfo()
        return BatterySettingsDestination.AppInfo
    }

    override fun openAppInfo() {
        launch(appInfoIntent())
    }

    private fun appInfoIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    private fun launch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/**
 * Autostart screens are not part of the Android SDK; these are the actions and
 * component names commonly used by OEM skins. Every candidate is probed with a
 * real `startActivity`, so an unknown or missing screen simply falls through to
 * app info instead of crashing.
 *
 * The packages are also listed in the manifest's `<queries>` block: without that
 * Android 11+ hides them from the app and every launch would fail even when the
 * screen exists.
 */
private data class OemSettings(
    val label: String,
    val components: List<ComponentName>,
    val actions: List<String> = emptyList(),
) {
    fun intents(): List<Intent> =
        actions.map { action -> Intent(action).addCategory(Intent.CATEGORY_DEFAULT) } +
            components.map { component -> Intent().setComponent(component) }

    companion object {
        fun forManufacturer(manufacturer: String?): OemSettings? {
            val name = manufacturer.orEmpty().lowercase()
            return when {
                listOf("xiaomi", "redmi", "poco").any { name.contains(it) } -> OemSettings(
                    "Autostart MIUI/HyperOS",
                    components = listOf(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                        ),
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.settings.PermCenter",
                        ),
                    ),
                    actions = listOf("miui.intent.action.OP_AUTO_START"),
                )

                listOf("oppo", "realme", "oneplus").any { name.contains(it) } -> OemSettings(
                    "Autostart ColorOS/realme UI",
                    components = listOf(
                        ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                        ),
                        ComponentName(
                            "com.oppo.safe",
                            "com.oppo.safe.permission.startup.StartupAppListActivity",
                        ),
                        ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity",
                        ),
                    ),
                )

                listOf("vivo", "iqoo").any { name.contains(it) } -> OemSettings(
                    "Autostart Funtouch/OriginOS",
                    components = listOf(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                        ),
                        ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                        ),
                    ),
                )

                listOf("huawei", "honor").any { name.contains(it) } -> OemSettings(
                    "Autostart EMUI/MagicOS",
                    components = listOf(
                        ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                        ),
                        ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.optimize.process.ProtectActivity",
                        ),
                    ),
                )

                name.contains("samsung") -> OemSettings(
                    "Perawatan baterai Samsung",
                    components = listOf(
                        ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.ui.battery.BatteryActivity",
                        ),
                    ),
                )

                name.contains("asus") -> OemSettings(
                    "Autostart ASUS",
                    components = listOf(
                        ComponentName(
                            "com.asus.mobilemanager",
                            "com.asus.mobilemanager.autostart.AutoStartActivity",
                        ),
                    ),
                )

                name.contains("meizu") -> OemSettings(
                    "Autostart Flyme",
                    components = listOf(
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
