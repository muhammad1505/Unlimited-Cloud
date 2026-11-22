package com.unlimitedcloud.platform

import androidx.compose.runtime.Composable

/**
 * Keeps the Android status/navigation bar icon contrast in sync with the
 * in-app theme. Desktop windows have no equivalent, so the actual is a no-op.
 */
@Composable
expect fun SystemBars(darkTheme: Boolean)
