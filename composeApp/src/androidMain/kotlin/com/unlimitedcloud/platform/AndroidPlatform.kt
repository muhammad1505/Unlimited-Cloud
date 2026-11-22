package com.unlimitedcloud.platform

import android.content.Context

/**
 * Holds the application context so platform `actual` code in `androidMain` can
 * resolve directories without leaking an Activity.
 */
object AndroidPlatform {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun requireContext(): Context =
        appContext ?: error("AndroidPlatform.init() must be called from the Activity before use")
}
