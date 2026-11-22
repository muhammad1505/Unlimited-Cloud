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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelState
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.cloud.ExplorerUiState
import com.unlimitedcloud.ui.components.AppAvatar
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.ConfirmDialog
import com.unlimitedcloud.ui.components.InfoRow
import com.unlimitedcloud.ui.components.SectionHeader
import com.unlimitedcloud.ui.components.StatusPill
import com.unlimitedcloud.ui.components.SurfaceCard
import com.unlimitedcloud.ui.theme.BrandGradientDeep
import com.unlimitedcloud.ui.theme.ThemeMode
import com.unlimitedcloud.ui.util.formatBytes

@Composable
fun SettingsScreen(
    user: AuthUiState.Ready,
    channelState: CloudChannelState,
    filesState: ExplorerUiState,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    engine: TelegramEngine,
    viewModel: CloudFilesViewModel,
    modifier: Modifier = Modifier,
) {
    var showLogout by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileCard(user = user)

        SurfaceCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "Tampilan")
            Spacer(Modifier.height(10.dp))
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

        SurfaceCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "Penyimpanan")
            Spacer(Modifier.height(4.dp))
            InfoRow(
                icon = AppIcons.Cloud,
                title = "Channel penyimpanan",
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
                value = "${filesState.files.size} berkas · ${filesState.folders.size} folder",
            )
            InfoRow(
                icon = AppIcons.Storage,
                title = "Total ukuran",
                value = formatBytes(filesState.totalBytes),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = viewModel::refresh) {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Sinkronkan ulang")
                }
            }
        }

        SurfaceCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(title = "Tentang")
            Spacer(Modifier.height(4.dp))
            InfoRow(
                icon = AppIcons.Cloud,
                title = "Unlimited Cloud",
                value = "Versi 1.0.0 · Compose Multiplatform",
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
        }

        OutlinedButton(
            onClick = { showLogout = true },
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

    if (showLogout) {
        ConfirmDialog(
            title = "Keluar dari akun?",
            message = "Sesi Telegram akan ditutup. Berkas yang sudah diunggah tetap aman di channel pribadi Anda.",
            confirmLabel = "Keluar",
            onConfirm = {
                showLogout = false
                engine.logout()
            },
            onDismiss = { showLogout = false },
            destructive = true,
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

private fun channelLabel(state: CloudChannelState): String = when (state) {
    CloudChannelState.Idle -> "Belum disiapkan"
    CloudChannelState.Resolving -> "Menyiapkan channel…"
    is CloudChannelState.Ready -> "Siap · chat ${state.chatId}"
    is CloudChannelState.Failed -> state.message
}
