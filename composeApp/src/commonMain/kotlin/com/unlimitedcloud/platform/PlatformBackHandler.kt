package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * Intercepts the system back gesture while an in-app overlay is open.
 *
 * Desktop has no system back button, so the actual is a no-op there and every
 * overlay still exposes its own "Kembali" control.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
