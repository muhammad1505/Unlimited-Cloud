package com.unlimitedcloud

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.unlimitedcloud.di.appModule
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

fun main() {
    if (GlobalContext.getOrNull() == null) {
        startKoin { modules(appModule) }
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Unlimited Cloud",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            App()
        }
    }
}
