package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture; overlays expose their own back button. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
