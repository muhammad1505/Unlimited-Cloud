package com.unlimitedcloud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelManager
import com.unlimitedcloud.cloud.CloudChannelState
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.ui.components.AppAvatar
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.BrandMark
import com.unlimitedcloud.ui.components.ConfirmDialog
import com.unlimitedcloud.ui.components.StatusPill
import com.unlimitedcloud.ui.theme.ThemeMode
import org.koin.compose.koinInject

private enum class AppSection(
    val label: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Files("Berkas", "Berkas Anda", "Kelola penyimpanan di channel pribadi", AppIcons.Folder),
    Transfers("Transfer", "Transfer", "Unggahan dan unduhan terbaru", AppIcons.Transfer),
    Settings("Pengaturan", "Pengaturan", "Akun, tampilan, dan penyimpanan", AppIcons.Tune),
}

/** Signed-in shell: adaptive rail/bottom navigation around the three sections. */
@Composable
fun SignedInScreen(
    state: AuthUiState.Ready,
    engine: TelegramEngine,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val channelManager = koinInject<CloudChannelManager>()
    val channelState by channelManager.channelState.collectAsState()
    val viewModel = koinInject<CloudFilesViewModel>()
    val filesState by viewModel.uiState.collectAsState()

    var section by remember { mutableStateOf(AppSection.Files) }
    var showLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val background = MaterialTheme.colorScheme.background
    val ambient = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        ambient.copy(alpha = 0.05f),
                        background,
                        background,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val expanded = maxWidth >= 920.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (expanded) {
                AppNavigationRail(
                    section = section,
                    onSelect = { section = it },
                    userName = state.displayName,
                    onLogout = { showLogout = true },
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                AppHeader(
                    section = section,
                    userName = state.displayName,
                    channelState = channelState,
                    expanded = expanded,
                    onProfile = { section = AppSection.Settings },
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (section) {
                        AppSection.Files -> FilesScreen(
                            viewModel = viewModel,
                            state = filesState,
                            expanded = expanded,
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppSection.Transfers -> TransfersScreen(
                            viewModel = viewModel,
                            state = filesState,
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppSection.Settings -> SettingsScreen(
                            user = state,
                            channelState = channelState,
                            filesState = filesState,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            engine = engine,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (!expanded) {
                    AppBottomBar(
                        section = section,
                        onSelect = { section = it },
                        activeTransfers = filesState.activeTransfers.size,
                    )
                }
            }
        }
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
private fun AppHeader(
    section: AppSection,
    userName: String,
    channelState: CloudChannelState,
    expanded: Boolean,
    onProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!expanded) {
            BrandMark(size = 36.dp)
            Spacer(Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ChannelPill(state = channelState, compact = !expanded)
        Spacer(Modifier.width(10.dp))
        AppAvatar(
            name = userName.ifBlank { "Pengguna Telegram" },
            size = 40.dp,
            modifier = Modifier.clickable(onClick = onProfile),
        )
    }
}

@Composable
private fun ChannelPill(state: CloudChannelState, compact: Boolean) {
    val (label, color) = when (state) {
        CloudChannelState.Idle -> "Menunggu" to Color(0xFFF59E0B)
        CloudChannelState.Resolving -> "Menyiapkan" to Color(0xFFF59E0B)
        is CloudChannelState.Ready -> "Siap" to Color(0xFF10B981)
        is CloudChannelState.Failed -> "Gagal" to MaterialTheme.colorScheme.error
    }
    StatusPill(
        text = if (compact) "" else label,
        color = color,
    )
}

@Composable
private fun AppNavigationRail(
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    userName: String,
    onLogout: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier
                .width(96.dp)
                .fillMaxHeight()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(size = 46.dp)
            Spacer(Modifier.height(26.dp))

            AppSection.entries.forEach { item ->
                RailItem(
                    item = item,
                    selected = item == section,
                    onClick = { onSelect(item) },
                )
            }

            Spacer(Modifier.weight(1f))

            AppAvatar(
                name = userName.ifBlank { "Pengguna Telegram" },
                size = 42.dp,
                modifier = Modifier.clickable { onSelect(AppSection.Settings) },
            )
            Spacer(Modifier.height(6.dp))
            IconButton(onClick = onLogout) {
                Icon(
                    imageVector = AppIcons.Logout,
                    contentDescription = "Keluar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    item: AppSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                    } else {
                        Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun AppBottomBar(
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    activeTransfers: Int,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AppSection.entries.forEach { item ->
                val selected = item == section
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(item) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    Color.Transparent
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(19.dp),
                        )
                        if (item == AppSection.Transfers && activeTransfers > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 2.dp, end = 4.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                            )
                        }
                    }
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
