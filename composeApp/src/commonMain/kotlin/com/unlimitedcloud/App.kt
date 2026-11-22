package com.unlimitedcloud

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.platform.SystemBars
import com.unlimitedcloud.settings.AppPreferences
import com.unlimitedcloud.ui.AuthScreen
import com.unlimitedcloud.ui.SignedInScreen
import com.unlimitedcloud.ui.SplashScreen
import com.unlimitedcloud.ui.theme.ThemeMode
import com.unlimitedcloud.ui.theme.UnlimitedCloudTheme
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private enum class RootDestination {
    Splash,
    Auth,
    Main,
}

/**
 * Root composable shared by the Android and Desktop entry points.
 *
 * Phase 2–8 built the TDLib engine, storage channel and explorer. This tree now
 * adds the branding/UI phase: a short branded splash, the redesigned auth flow,
 * and the signed-in shell with Berkas, Transfer and Pengaturan sections.
 */
@Composable
fun App() {
    val preferences = koinInject<AppPreferences>()
    var themeMode by remember(preferences) { mutableStateOf(preferences.loadThemeMode()) }
    val darkTheme = when (themeMode) {
        ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    UnlimitedCloudTheme(darkTheme = darkTheme) {
        SystemBars(darkTheme = darkTheme)

        val engine = koinInject<TelegramEngine>()
        val authState by engine.authState.collectAsState()
        var splashDone by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            engine.start()
            delay(700)
            splashDone = true
        }

        val destination = when {
            !splashDone || authState is AuthUiState.Initializing -> RootDestination.Splash
            authState is AuthUiState.Ready -> RootDestination.Main
            else -> RootDestination.Auth
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Crossfade(
                targetState = destination,
                animationSpec = tween(durationMillis = 350),
                label = "root-destination",
            ) { target ->
                when (target) {
                    RootDestination.Splash -> SplashScreen()
                    RootDestination.Auth -> AuthScreen()
                    RootDestination.Main -> {
                        val ready = authState as? AuthUiState.Ready
                        if (ready != null) {
                            SignedInScreen(
                                state = ready,
                                engine = engine,
                                themeMode = themeMode,
                                onThemeModeChange = { mode ->
                                    themeMode = mode
                                    preferences.saveThemeMode(mode)
                                },
                            )
                        } else {
                            SplashScreen()
                        }
                    }
                }
            }
        }
    }
}
