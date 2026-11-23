package com.unlimitedcloud.settings

import com.unlimitedcloud.platform.PlatformStorage
import com.unlimitedcloud.ui.theme.ThemeMode
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Tiny key/value store for local preferences and sync bookkeeping.
 *
 * okio keeps this multiplatform: Android and Desktop both write a plain text
 * `settings.properties` file inside the app data directory, with no
 * platform-specific preferences API.
 */
class AppPreferences(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val settingsPath = "${PlatformStorage.appDataDirectory}/settings.properties".toPath()

    fun get(key: String): String? = readAll()[key]

    fun put(key: String, value: String?) {
        val values = readAll().toMutableMap()
        if (value == null) values.remove(key) else values[key] = value
        writeAll(values)
    }

    // --- Appearance ------------------------------------------------------------

    fun loadThemeMode(): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == get(KEY_THEME) } ?: ThemeMode.System

    fun saveThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    // --- Changelog gating ------------------------------------------------------

    /**
     * versionCode the user already acknowledged, or `null` on a fresh install.
     * `null` lets the app record the current version silently instead of showing
     * a "what's new" dialog to someone who just installed it.
     */
    fun lastSeenVersionCode(): Int? = get(KEY_LAST_SEEN_VERSION)?.toIntOrNull()

    fun markVersionSeen(versionCode: Int) = put(KEY_LAST_SEEN_VERSION, versionCode.toString())

    // --- Gallery auto sync -----------------------------------------------------

    fun galleryAutoSync(): Boolean = get(KEY_GALLERY_AUTO_SYNC) == "true"

    fun setGalleryAutoSync(enabled: Boolean) = put(KEY_GALLERY_AUTO_SYNC, enabled.toString())

    /** Opt-in switch for the WorkManager job that runs while the app is closed. */
    fun galleryBackgroundSync(): Boolean = get(KEY_GALLERY_BACKGROUND_SYNC) == "true"

    fun setGalleryBackgroundSync(enabled: Boolean) =
        put(KEY_GALLERY_BACKGROUND_SYNC, enabled.toString())

    fun galleryFolderId(): String? = get(KEY_GALLERY_FOLDER_ID)

    fun galleryFolderName(): String? = get(KEY_GALLERY_FOLDER_NAME)

    fun setGalleryFolder(id: String?, name: String?) {
        put(KEY_GALLERY_FOLDER_ID, id)
        put(KEY_GALLERY_FOLDER_NAME, name)
    }

    /** Document keys already uploaded; keeps repeated syncs incremental. */
    fun gallerySyncedKeys(): Set<String> =
        get(KEY_GALLERY_SYNCED_KEYS)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    fun addGallerySyncedKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val merged = gallerySyncedKeys() + keys
        put(KEY_GALLERY_SYNCED_KEYS, merged.joinToString("\n"))
    }

    fun clearGallerySyncedKeys() = put(KEY_GALLERY_SYNCED_KEYS, null)

    fun galleryLastSync(): String? = get(KEY_GALLERY_LAST_SYNC)

    fun setGalleryLastSync(label: String) = put(KEY_GALLERY_LAST_SYNC, label)

    // --- Device gallery --------------------------------------------------------

    /** Copies downloaded photos into Pictures/Unlimited Cloud via MediaStore. */
    fun autoSavePhotos(): Boolean = get(KEY_AUTO_SAVE_PHOTOS) == "true"

    fun setAutoSavePhotos(enabled: Boolean) = put(KEY_AUTO_SAVE_PHOTOS, enabled.toString())

    // --- Storage ---------------------------------------------------------------

    private fun readAll(): Map<String, String> = runCatching {
        if (!fileSystem.exists(settingsPath)) return@runCatching emptyMap<String, String>()

        val source = fileSystem.source(settingsPath).buffer()
        val text = try {
            source.readUtf8()
        } finally {
            source.close()
        }

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .associate { line ->
                line.substringBefore('=').trim() to decode(line.substringAfter('='))
            }
    }.getOrDefault(emptyMap())

    private fun writeAll(values: Map<String, String>) {
        runCatching {
            settingsPath.parent?.let { fileSystem.createDirectories(it) }
            val sink = fileSystem.sink(settingsPath).buffer()
            try {
                values.forEach { (key, value) -> sink.writeUtf8("$key=${encode(value)}\n") }
            } finally {
                sink.close()
            }
        }
    }

    private fun encode(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n")

    // Single pass so a literal backslash followed by "n" survives a round trip.
    private fun decode(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> {
                        append('\n')
                        index += 2
                    }

                    '\\' -> {
                        append('\\')
                        index += 2
                    }

                    else -> {
                        append(char)
                        index++
                    }
                }
            } else {
                append(char)
                index++
            }
        }
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_LAST_SEEN_VERSION = "changelog.lastSeenVersionCode"
        const val KEY_GALLERY_AUTO_SYNC = "gallery.autoSync"
        const val KEY_GALLERY_BACKGROUND_SYNC = "gallery.backgroundSync"
        const val KEY_GALLERY_FOLDER_ID = "gallery.folderId"
        const val KEY_GALLERY_FOLDER_NAME = "gallery.folderName"
        const val KEY_GALLERY_SYNCED_KEYS = "gallery.syncedKeys"
        const val KEY_GALLERY_LAST_SYNC = "gallery.lastSync"
        const val KEY_AUTO_SAVE_PHOTOS = "gallery.autoSavePhotos"
    }
}
