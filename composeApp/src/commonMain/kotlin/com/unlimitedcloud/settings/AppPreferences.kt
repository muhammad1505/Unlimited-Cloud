package com.unlimitedcloud.settings

import com.unlimitedcloud.platform.PlatformStorage
import com.unlimitedcloud.ui.theme.ThemeMode
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Tiny key/value store for local UI preferences.
 *
 * okio keeps this multiplatform: Android and Desktop both write a plain text
 * file inside the app data directory, no platform-specific preferences API.
 */
class AppPreferences(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val settingsPath = "${PlatformStorage.appDataDirectory}/settings.properties".toPath()

    fun loadThemeMode(): ThemeMode = runCatching {
        if (!fileSystem.exists(settingsPath)) return@runCatching ThemeMode.System

        val source = fileSystem.source(settingsPath).buffer()
        val text = try {
            source.readUtf8()
        } finally {
            source.close()
        }

        val value = text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("theme=") }
            ?.substringAfter('=')

        ThemeMode.entries.firstOrNull { it.name == value } ?: ThemeMode.System
    }.getOrDefault(ThemeMode.System)

    fun saveThemeMode(mode: ThemeMode) {
        runCatching {
            settingsPath.parent?.let { fileSystem.createDirectories(it) }
            val sink = fileSystem.sink(settingsPath).buffer()
            try {
                sink.writeUtf8("theme=${mode.name}\n")
            } finally {
                sink.close()
            }
        }
    }
}
