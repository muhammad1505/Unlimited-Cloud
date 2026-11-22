package com.unlimitedcloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unlimitedcloud.di.appModule
import com.unlimitedcloud.platform.AndroidPlatform
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AndroidPlatform.init(this)
        if (GlobalContext.getOrNull() == null) {
            startKoin { modules(appModule) }
        }

        setContent {
            App()
        }
    }
}
