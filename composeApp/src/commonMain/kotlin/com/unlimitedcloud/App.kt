package com.unlimitedcloud

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unlimitedcloud.ui.AuthScreen

/**
 * Root composable shared by the Android and Desktop entry points.
 *
 * Phase 2 renders the Telegram authorization flow; the file explorer, transfers
 * and settings are wired into this tree in later phases.
 */
@Composable
fun App() {
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AuthScreen()
        }
    }
}
