package com.unlimitedcloud.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelManager
import com.unlimitedcloud.cloud.CloudChannelState
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.cloud.ExplorerUiState
import com.unlimitedcloud.log.LogStore
import com.unlimitedcloud.platform.PlatformBackHandler
import com.unlimitedcloud.ui.components.AppAvatar
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.BrandMark
import com.unlimitedcloud.ui.components.ConfirmDialog
import com.unlimitedcloud.ui.components.StatusPill
import com.unlimitedcloud.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private enum class AppSection(
    val label: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Files("Berkas", "Berkas Anda", "Kelola penyimpanan di grup topik", AppIcons.Folder),
    Transfers("Transfer", "Transfer", "Unggahan dan unduhan terbaru", AppIcons.Transfer),
    Settings("Pengaturan", "Pengaturan", "Akun, tampilan, dan penyimpanan", AppIcons.Tune),
}

/**
 * Every visible page of the signed-in shell, including the two secondary pages
 * opened from Settings. Modelling them as one destination type keeps the
 * transition, the back handling and the navigation highlight consistent.
 */
private sealed interface AppDestination {
    data class Section(val section: AppSection) : AppDestination
    data object Diagnostics : AppDestination
    data object Changelog : AppDestination
}

private const val OVERLAY_LOG = "log"
private const val OVERLAY_CHANGELOG = "changelog"

/** Signed-in shell: adaptive rail/bottom navigation around the app sections. */
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
    val logEntries by LogStore.entries.collectAsState()

    // rememberSaveable keeps the selected section across rotation and the
    // declared configChanges, which plain `remember` lost.
    var sectionName by rememberSaveable { mutableStateOf(AppSection.Files.name) }
    val section = AppSection.entries.firstOrNull { it.name == sectionName } ?: AppSection.Files
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }

    var showLogout by remember { mutableStateOf(false) }

    val destination: AppDestination = when (overlay) {
        OVERLAY_LOG -> AppDestination.Diagnostics
        OVERLAY_CHANGELOG -> AppDestination.Changelog
        else -> AppDestination.Section(section)
    }

    val selectSection: (AppSection) -> Unit = { target ->
        overlay = null
        sectionName = target.name
    }

    PlatformBackHandler(enabled = overlay != null) { overlay = null }

    LaunchedEffect(Unit) {
        // Auto sync is a no-op unless the feature is enabled and a folder was
        // picked; it runs once when the signed-in shell first appears.
        viewModel.syncGallery(auto = true)
    }

    // Entering Berkas refreshes the list, so a file uploaded from another device
    // or by the background photo sync shows up without a manual pull.
    LaunchedEffect(section) {
        if (section == AppSection.Files) viewModel.refresh()
    }

    val background = MaterialTheme.colorScheme.background
    val ambient = MaterialTheme.colorScheme.primary

    // safeDrawing contains the keyboard inset, which used to shrink the shell and
    // push the bottom bar up to the middle of the screen as soon as the search
    // field was focused. The shell now ignores the keyboard: the file area takes
    // that inset itself and the bar slides away while typing.
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

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
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime)),
    ) {
        val expanded = maxWidth >= 920.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (expanded) {
                AppNavigationRail(
                    section = section,
                    onSelect = selectSection,
                    userName = state.displayName,
                    activeTransfers = filesState.activeTransfers.size,
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (overlay == null) {
                    AppHeader(
                        section = section,
                        userName = state.displayName,
                        channelState = channelState,
                        expanded = expanded,
                        onProfile = { selectSection(AppSection.Settings) },
                    )
                }

                AnimatedContent(
                    targetState = destination,
                    modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
                    transitionSpec = {
                        (
                            fadeIn(animationSpec = tween(220)) +
                                slideInVertically(animationSpec = tween(220)) { height ->
                                    height / 24
                                }
                            ).togetherWith(fadeOut(animationSpec = tween(140)))
                    },
                    label = "app-destination",
                ) { target ->
                    when (target) {
                        is AppDestination.Section -> when (target.section) {
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
                                viewModel = viewModel,
                                onOpenLog = { overlay = OVERLAY_LOG },
                                onOpenChangelog = { overlay = OVERLAY_CHANGELOG },
                                onLogout = { showLogout = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        AppDestination.Diagnostics -> LogScreen(
                            entries = logEntries,
                            onClear = LogStore::clear,
                            onBack = { overlay = null },
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppDestination.Changelog -> ChangelogScreen(
                            onBack = { overlay = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !expanded && !keyboardVisible,
                    enter = fadeIn(animationSpec = tween(160)) +
                        slideInVertically(animationSpec = tween(160)) { height -> height / 2 },
                    exit = fadeOut(animationSpec = tween(120)) +
                        slideOutVertically(animationSpec = tween(120)) { height -> height / 2 },
                ) {
                    AppBottomBar(
                        section = section,
                        onSelect = selectSection,
                        activeTransfers = filesState.activeTransfers.size,
                    )
                }
            }
        }

        // One notification surface for every process the app reports, on every
        // tab. It used to live inside the Berkas page, so a transfer that
        // finished while the Transfer tab was open said nothing.
        AppNotificationHost(
            state = filesState,
            onDismissStatus = viewModel::dismissStatus,
            onDismissError = viewModel::clearError,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = if (expanded) 16.dp else 96.dp),
        )
    }

    if (showLogout) {
        ConfirmDialog(
            title = "Keluar dari akun?",
            message = "Sesi Telegram akan ditutup. Berkas yang sudah diunggah tetap aman di grup topik pribadi Anda.",
            confirmLabel = "Keluar",
            onConfirm = {
                showLogout = false
                // Forget the previous account's channel and sync bookkeeping
                // before the session ends, otherwise the next sign-in inherits them.
                viewModel.forgotAccount()
                engine.logout()
            },
            onDismiss = { showLogout = false },
            destructive = true,
        )
    }
}

@Composable
private fun AppNotificationHost(
    state: ExplorerUiState,
    onDismissStatus: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = state.error ?: state.status
    val isError = state.error != null

    // Transient by design: a process line that stays on screen becomes part of
    // the layout instead of a notification.
    LaunchedEffect(message) {
        if (message != null) {
            delay(4500)
            if (isError) onDismissError() else onDismissStatus()
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(animationSpec = tween(180)) +
            slideInVertically(animationSpec = tween(180)) { height -> height / 3 },
        exit = fadeOut(animationSpec = tween(120)) +
            slideOutVertically(animationSpec = tween(120)) { height -> height / 3 },
        modifier = modifier,
    ) {
        val tint = if (isError) MaterialTheme.colorScheme.error else Color(0xFF10B981)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isError) AppIcons.Info else AppIcons.Check,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    activeTransfers: Int,
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
                    badge = item == AppSection.Transfers && activeTransfers > 0,
                    onClick = { onSelect(item) },
                )
            }

            Spacer(Modifier.weight(1f))

            AppAvatar(
                name = userName.ifBlank { "Pengguna Telegram" },
                size = 42.dp,
                modifier = Modifier.clickable { onSelect(AppSection.Settings) },
            )
        }
    }
}

@Composable
private fun RailItem(
    item: AppSection,
    selected: Boolean,
    badge: Boolean,
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
            if (badge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 3.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
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
                        maxLines = 1,
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
