package com.unlimitedcloud.settings

import com.unlimitedcloud.platform.GalleryFolder
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

    // --- Storage channel -------------------------------------------------------

    /**
     * Chat id of the channel that holds the files. Remembering it is what keeps
     * the app from creating a fresh channel every launch: the title search can
     * miss a channel that TDLib has not indexed on this device yet.
     */
    fun cloudRootChatId(): Long? = get(KEY_CLOUD_ROOT_CHAT_ID)?.toLongOrNull()

    fun setCloudRootChatId(chatId: Long?) =
        put(KEY_CLOUD_ROOT_CHAT_ID, chatId?.toString())

    /**
     * Drops the keys that belong to the Telegram account. The picked gallery
     * folders are kept: those are device grants, not account data.
     */
    fun clearAccountData() {
        put(KEY_CLOUD_ROOT_CHAT_ID, null)
        put(KEY_GALLERY_SYNCED_KEYS, null)
        put(KEY_GALLERY_LAST_SYNC, null)
    }

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

    /**
     * Every folder the user picked, in the order they added them. One entry per
     * line, `id<TAB>name`; SAF tree ids never contain a tab or a newline.
     */
    fun galleryFolders(): List<GalleryFolder> {
        val stored = get(KEY_GALLERY_FOLDERS)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val separator = line.indexOf('\t')
                if (separator <= 0) return@mapNotNull null
                val id = line.substring(0, separator).trim()
                val name = line.substring(separator + 1).trim()
                if (id.isEmpty() || name.isEmpty()) null else GalleryFolder(id = id, name = name)
            }
            ?.toList()
            .orEmpty()
        if (stored.isNotEmpty()) return stored

        // Settings written by builds that supported a single folder only.
        val legacyId = get(KEY_GALLERY_FOLDER_ID)
        val legacyName = get(KEY_GALLERY_FOLDER_NAME)
        return if (legacyId.isNullOrBlank() || legacyName.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(GalleryFolder(id = legacyId, name = legacyName))
        }
    }

    fun addGalleryFolder(folder: GalleryFolder) {
        writeGalleryFolders(galleryFolders().filterNot { it.id == folder.id } + folder)
    }

    fun removeGalleryFolder(id: String) =
        writeGalleryFolders(galleryFolders().filterNot { it.id == id })

    fun clearGalleryFolders() {
        put(KEY_GALLERY_FOLDERS, null)
        put(KEY_GALLERY_FOLDER_ID, null)
        put(KEY_GALLERY_FOLDER_NAME, null)
    }

    private fun writeGalleryFolders(folders: List<GalleryFolder>) {
        // A list write always replaces the legacy single-folder pair, otherwise a
        // removed folder could reappear through the old keys.
        put(KEY_GALLERY_FOLDER_ID, null)
        put(KEY_GALLERY_FOLDER_NAME, null)
        if (folders.isEmpty()) {
            put(KEY_GALLERY_FOLDERS, null)
            return
        }
        put(
            KEY_GALLERY_FOLDERS,
            folders.joinToString("\n") { folder -> "${folder.id}\t${folder.name}" },
        )
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
        const val KEY_CLOUD_ROOT_CHAT_ID = "cloud.rootChatId"
        const val KEY_LAST_SEEN_VERSION = "changelog.lastSeenVersionCode"
        const val KEY_GALLERY_AUTO_SYNC = "gallery.autoSync"
        const val KEY_GALLERY_BACKGROUND_SYNC = "gallery.backgroundSync"
        const val KEY_GALLERY_FOLDERS = "gallery.folders"
        const val KEY_GALLERY_FOLDER_ID = "gallery.folderId"
        const val KEY_GALLERY_FOLDER_NAME = "gallery.folderName"
        const val KEY_GALLERY_SYNCED_KEYS = "gallery.syncedKeys"
        const val KEY_GALLERY_LAST_SYNC = "gallery.lastSync"
        const val KEY_AUTO_SAVE_PHOTOS = "gallery.autoSavePhotos"
    }
}
