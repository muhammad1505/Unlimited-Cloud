package com.unlimitedcloud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.AppBuildInfo
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.cloud.CloudChannelState
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.cloud.ExplorerUiState
import com.unlimitedcloud.log.LogStore
import com.unlimitedcloud.platform.BatteryOptimizationSnapshot
import com.unlimitedcloud.platform.BatterySettingsDestination
import com.unlimitedcloud.platform.rememberBatteryOptimizationController
import com.unlimitedcloud.platform.rememberGalleryFolderPicker
import com.unlimitedcloud.ui.components.ActionRow
import com.unlimitedcloud.ui.components.AppAvatar
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.ConfirmDialog
import com.unlimitedcloud.ui.components.InfoBanner
import com.unlimitedcloud.ui.components.InfoRow
import com.unlimitedcloud.ui.components.SectionHeader
import com.unlimitedcloud.ui.components.StatusPill
import com.unlimitedcloud.ui.components.SurfaceCard
import com.unlimitedcloud.ui.theme.BrandGradientDeep
import com.unlimitedcloud.ui.theme.ThemeMode
import com.unlimitedcloud.ui.util.formatBytes

/**
 * Account, appearance and storage settings.
 *
 * One destination is shown once: rows that open a page carry a chevron instead
 * of a matching button below, the version appears in a single card, and the
 * gallery upload/download switches live together. Logout is requested from the
 * signed-in shell so the confirmation dialog exists in one place only.
 */
@Composable
fun SettingsScreen(
    user: AuthUiState.Ready,
    channelState: CloudChannelState,
    filesState: ExplorerUiState,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    viewModel: CloudFilesViewModel,
    onOpenLog: () -> Unit,
    onOpenChangelog: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetGallery by remember { mutableStateOf(false) }
    var batteryFeedback by remember { mutableStateOf<String?>(null) }
    val logEntries by LogStore.entries.collectAsState()

    val batteryController = rememberBatteryOptimizationController()
    var battery by remember(batteryController) { mutableStateOf(batteryController.snapshot()) }

    val pickGalleryFolder = rememberGalleryFolderPicker { folder ->
        if (folder != null) {
            viewModel.addGalleryFolder(folder)
        } else {
            viewModel.reportStatus("Pemilihan folder dibatalkan")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProfileCard(user = user)

        if (filesState.status != null) {
            InfoBanner(
                message = filesState.status.orEmpty(),
                onDismiss = viewModel::dismissStatus,
            )
        }
        if (filesState.error != null) {
            InfoBanner(
                message = filesState.error.orEmpty(),
                isError = true,
                onDismiss = viewModel::clearError,
            )
        }

        AppearanceCard(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )

        StorageCard(
            channelState = channelState,
            state = filesState,
            onRefresh = viewModel::refresh,
        )

        GalleryCard(
            state = filesState,
            onAddFolder = pickGalleryFolder,
            onRemoveFolder = viewModel::removeGalleryFolder,
            onGalleryAutoSyncChange = viewModel::setGalleryAutoSync,
            onBackgroundSyncChange = viewModel::setGalleryBackgroundSync,
            onAutoSaveChange = viewModel::setAutoSavePhotos,
            onSyncNow = { viewModel.syncGallery() },
            onResetSync = { showResetGallery = true },
        )

        if (battery.supported) {
            BatteryCard(
                battery = battery,
                feedback = batteryFeedback,
                onRefresh = {
                    batteryFeedback = null
                    battery = batteryController.snapshot()
                },
                onOpenAppInfo = {
                    batteryController.openAppInfo()
                    batteryFeedback = "Info aplikasi dibuka"
                    battery = batteryController.snapshot()
                },
                onOpenBatterySettings = {
                    batteryController.openSystemSettings()
                    batteryFeedback = "Pengaturan baterai dibuka"
                    battery = batteryController.snapshot()
                },
                onOpenOemSettings = {
                    batteryFeedback = when (batteryController.openOemSettings()) {
                        BatterySettingsDestination.Oem -> "Pengaturan autostart dibuka"
                        BatterySettingsDestination.AppInfo -> "Pintasan autostart tidak bisa " +
                            "dibuka di perangkat ini; Info aplikasi dibuka, pilih Baterai di sana"
                        BatterySettingsDestination.Unavailable -> "Perangkat ini tidak " +
                            "menyediakan pengaturan autostart"
                    }
                    battery = batteryController.snapshot()
                },
            )
        }

        AboutCard(
            logCount = logEntries.size,
            onOpenChangelog = onOpenChangelog,
            onOpenLog = onOpenLog,
        )

        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(
                imageVector = AppIcons.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Keluar dari akun")
        }

        Text(
            text = "Dibuat dengan Compose Multiplatform & TDLib",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        )
    }

    if (showResetGallery) {
        ConfirmDialog(
            title = "Atur ulang riwayat sinkron?",
            message = "Riwayat foto yang sudah diunggah akan dikosongkan. Foto lama " +
                "dianggap baru pada sinkron berikutnya, jadi bisa terunggah ulang. " +
                "Tindakan ini tidak menghapus berkas di Telegram.",
            confirmLabel = "Atur ulang",
            onConfirm = {
                showResetGallery = false
                viewModel.resetGallerySync()
            },
            onDismiss = { showResetGallery = false },
            destructive = true,
        )
    }
}

@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "Tampilan")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tema aplikasi",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Pilihan disimpan otomatis di perangkat ini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeOption(
                label = "Sistem",
                icon = AppIcons.Tune,
                selected = themeMode == ThemeMode.System,
                onClick = { onThemeModeChange(ThemeMode.System) },
            )
            ThemeOption(
                label = "Terang",
                icon = AppIcons.Sun,
                selected = themeMode == ThemeMode.Light,
                onClick = { onThemeModeChange(ThemeMode.Light) },
            )
            ThemeOption(
                label = "Gelap",
                icon = AppIcons.Moon,
                selected = themeMode == ThemeMode.Dark,
                onClick = { onThemeModeChange(ThemeMode.Dark) },
            )
        }
    }
}

@Composable
private fun StorageCard(
    channelState: CloudChannelState,
    state: ExplorerUiState,
    onRefresh: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "Penyimpanan")
        Spacer(Modifier.height(4.dp))
        InfoRow(
            icon = AppIcons.Cloud,
            title = "Penyimpanan",
            value = channelLabel(channelState),
            tint = Color(0xFF10B981),
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        InfoRow(
            icon = AppIcons.Folder,
            title = "Berkas terindeks",
            value = "${state.files.size} berkas · ${state.folders.size} folder",
        )
        InfoRow(
            icon = AppIcons.Storage,
            title = "Total ukuran",
            value = formatBytes(state.totalBytes),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (state.loading) "Memuat…" else "Muat ulang daftar")
            }
        }
    }
}

@Composable
private fun GalleryCard(
    state: ExplorerUiState,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onGalleryAutoSyncChange: (Boolean) -> Unit,
    onBackgroundSyncChange: (Boolean) -> Unit,
    onAutoSaveChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onResetSync: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Galeri",
            action = {
                if (state.gallerySupported) {
                    val statusColor = when {
                        state.gallerySyncing -> MaterialTheme.colorScheme.primary
                        state.galleryAutoSync -> Color(0xFF10B981)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    StatusPill(
                        text = when {
                            state.gallerySyncing -> "Menyinkronkan"
                            state.galleryAutoSync -> "Aktif"
                            else -> "Nonaktif"
                        },
                        color = statusColor,
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Foto dari folder yang Anda pilih diunggah ke topik Galeri di grup " +
                "penyimpanan Telegram. Pemilih folder sistem dipakai, jadi tidak perlu " +
                "izin penyimpanan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.gallerySupported) {
            Spacer(Modifier.height(8.dp))
            InfoRow(
                icon = AppIcons.Info,
                title = "Khusus Android",
                value = "Di desktop, unggah berkas lewat dialog berkas biasa.",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SurfaceCard
        }

        Spacer(Modifier.height(6.dp))
        InfoRow(
            icon = AppIcons.Gallery,
            title = "Folder galeri",
            value = when (state.galleryFolders.size) {
                0 -> "Belum dipilih"
                1 -> state.galleryFolders.first().name
                else -> "${state.galleryFolders.size} folder dipilih"
            },
        )
        if (state.galleryFolders.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.galleryFolders.forEach { folder ->
                    FilterChip(
                        selected = true,
                        onClick = { onRemoveFolder(folder.id) },
                        label = { Text(folder.name) },
                        leadingIcon = {
                            Icon(
                                imageVector = AppIcons.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = "Lepas ${folder.name}",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                            selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onAddFolder) {
                Text(if (state.galleryFolders.isEmpty()) "Pilih folder" else "Tambah folder")
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        ToggleRow(
            title = "Unggah otomatis",
            description = "Saat aplikasi dibuka, semua foto baru di folder terpilih " +
                "diunggah. Jadwal latar belakang mengambil beberapa foto tiap putaran " +
                "agar tidak melewati batas waktu sistem.",
            checked = state.galleryAutoSync,
            onCheckedChange = onGalleryAutoSyncChange,
            enabled = state.galleryFolders.isNotEmpty(),
        )

        if (state.galleryBackgroundSupported) {
            ToggleRow(
                title = "Sinkron saat aplikasi tertutup",
                description = "Sistem menjalankan batch kecil kira-kira tiap 6 jam saat " +
                    "ada internet. Tanpa layanan latar depan dan tanpa izin tambahan.",
                checked = state.galleryBackgroundSync,
                onCheckedChange = onBackgroundSyncChange,
                enabled = state.galleryAutoSync && state.galleryFolders.isNotEmpty(),
            )
        }

        Spacer(Modifier.height(2.dp))
        InfoRow(
            icon = AppIcons.History,
            title = "Sinkron terakhir",
            value = state.galleryLastSync ?: "Belum pernah",
        )
        InfoRow(
            icon = AppIcons.Check,
            title = "Foto terkirim",
            value = "${state.gallerySyncedCount} foto tercatat di perangkat ini",
            tint = Color(0xFF10B981),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onResetSync,
                enabled = state.gallerySyncedCount > 0 && !state.gallerySyncing,
            ) {
                Text("Atur ulang riwayat")
            }
            TextButton(
                onClick = onSyncNow,
                enabled = state.galleryFolders.isNotEmpty() && !state.gallerySyncing,
            ) {
                Text(if (state.gallerySyncing) "Menyinkronkan…" else "Sinkronkan sekarang")
            }
        }

        if (state.autoSaveSupported) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            ToggleRow(
                title = "Simpan foto yang diunduh",
                description = "Disalin ke Pictures/Unlimited Cloud lewat MediaStore. " +
                    "Berlaku untuk JPG, PNG, WebP, GIF, HEIC, dan BMP; Android 10 ke atas " +
                    "tidak meminta izin penyimpanan.",
                checked = state.autoSavePhotos,
                onCheckedChange = onAutoSaveChange,
            )
        }
    }
}

@Composable
private fun BatteryCard(
    battery: BatteryOptimizationSnapshot,
    feedback: String?,
    onRefresh: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenOemSettings: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Baterai",
            action = {
                TextButton(onClick = onRefresh) {
                    Text("Perbarui")
                }
            },
        )
        Spacer(Modifier.height(2.dp))
        InfoRow(
            icon = AppIcons.Battery,
            title = if (battery.ignoringOptimizations) {
                "Sudah dikecualikan"
            } else {
                "Masih dioptimalkan sistem"
            },
            value = if (battery.ignoringOptimizations) {
                "Unggahan besar tidak akan dijeda saat layar mati."
            } else {
                "Android dapat menjeda unggahan saat layar mati atau baterai lemah."
            },
            tint = if (battery.ignoringOptimizations) {
                Color(0xFF10B981)
            } else {
                Color(0xFFF59E0B)
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = batteryGuidance(battery),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        battery.appLabel?.let { label ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Cari \"$label\" (${battery.packageName.orEmpty()}) di daftar " +
                    "aplikasi pengaturan sistem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        battery.oemHint?.let { hint ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Perangkat ${battery.manufacturer.orEmpty()} punya pengaturan " +
                    "autostart terpisah agar sinkronisasi tidak dihentikan setelah reboot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenOemSettings) {
                Text(hint)
            }
        }
        if (feedback != null) {
            Text(
                text = feedback,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenAppInfo) {
                Text("Info aplikasi")
            }
            TextButton(onClick = onOpenBatterySettings) {
                Text("Pengaturan baterai")
            }
        }
    }
}

@Composable
private fun AboutCard(
    logCount: Int,
    onOpenChangelog: () -> Unit,
    onOpenLog: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = "Tentang & bantuan")
        Spacer(Modifier.height(4.dp))
        InfoRow(
            icon = AppIcons.Cloud,
            title = "Unlimited Cloud",
            value = "Versi ${AppBuildInfo.VERSION_NAME} · Compose Multiplatform",
        )
        InfoRow(
            icon = AppIcons.Shield,
            title = "Mesin Telegram",
            value = "TDLib · koneksi langsung, tanpa server perantara",
            tint = MaterialTheme.colorScheme.secondary,
        )
        InfoRow(
            icon = AppIcons.Lock,
            title = "Privasi",
            value = "Berkas tetap berada di akun Telegram Anda",
            tint = Color(0xFF10B981),
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        ActionRow(
            icon = AppIcons.History,
            title = "Apa yang baru",
            value = "Catatan rilis versi ${AppBuildInfo.VERSION_NAME}",
            onClick = onOpenChangelog,
        )
        ActionRow(
            icon = AppIcons.Tune,
            title = "Log diagnostik",
            value = "$logCount baris tersimpan di perangkat ini",
            onClick = onOpenLog,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun ProfileCard(user: AuthUiState.Ready) {
    val name = user.displayName.ifBlank { "Pengguna Telegram" }
    val subtitle = listOfNotNull(
        user.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
        user.phoneNumber?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { "Akun Telegram" }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(BrandGradientDeep))
                .padding(22.dp),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.06f),
                    radius = size.minDimension * 0.8f,
                    center = Offset(size.width * 0.9f, size.height * 0.05f),
                )
                drawCircle(
                    color = Color(0xFF06B6D4).copy(alpha = 0.10f),
                    radius = size.minDimension * 0.6f,
                    center = Offset(size.width * 0.05f, size.height * 1.05f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppAvatar(name = name, size = 62.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusPill(
                        text = "Tersambung ke Telegram",
                        color = Color(0xFF34D399),
                        icon = AppIcons.Check,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * Power guidance that follows the installed Android release: the advice for
 * Android 13's per-app "Restricted" battery setting is wrong on Android 8,
 * where only Doze exists.
 */
private fun batteryGuidance(snapshot: BatteryOptimizationSnapshot): String {
    val base = "Unlimited Cloud tidak meminta izin pengecualian otomatis. Tombol di bawah " +
        "hanya membuka pengaturan sistem, sehingga keputusan tetap ada di tangan Anda."
    val sdk = snapshot.sdkInt ?: return base
    val detail = when {
        sdk >= 33 ->
            "Android 13 ke atas menambahkan batas baterai \"Restricted\" per aplikasi; " +
                "pilih \"Tanpa batasan\" agar unggahan tidak dijeda."
        sdk >= 31 ->
            "Android 12 membatasi aplikasi yang jarang dibuka lewat App Standby; " +
                "kecualikan aplikasi ini dari optimasi."
        sdk >= 29 ->
            "Android 10 menaruh opsi baterai di Info aplikasi → Baterai; matikan " +
                "pembatasan agar unggahan tidak dijeda."
        sdk >= 26 ->
            "Android 8–9 memakai Doze: unggahan besar bisa berhenti saat layar mati."
        else -> "Versi Android $sdk memakai aturan Doze lama."
    }
    return "$detail $base"
}

private fun channelLabel(state: CloudChannelState): String = when (state) {
    CloudChannelState.Idle -> "Belum disiapkan"
    CloudChannelState.Resolving -> "Menyiapkan penyimpanan…"
    is CloudChannelState.Ready -> "Siap · chat ${state.chatId}"
    is CloudChannelState.Failed -> state.message
}
