package com.unlimitedcloud.cloud

/**
 * Topics that files are filed into when the user did not pick a folder.
 *
 * Every upload still becomes a document message inside a forum topic, so the
 * Telegram group and the app show the same structure without the user having to
 * choose anything: a photo lands in "Foto", a PDF in "Dokumen", and so on.
 */
object FileCategories {
    /** Topic the folder sync used for every file before per-type filing. */
    const val GALLERY = "Galeri"
    const val PHOTOS = "Foto"
    const val VIDEOS = "Video"
    const val AUDIO = "Audio"
    const val DOCUMENTS = "Dokumen"
    const val ARCHIVES = "Arsip"
    const val APPS = "Aplikasi"
    const val OTHER = "Lainnya"

    /** Every folder an automatic upload can land in, in the order shown in the UI. */
    val all: List<String> = listOf(PHOTOS, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES, APPS, OTHER)

    /** Topics this app created, including the one the gallery sync used to use. */
    private val generated: List<String> = all + GALLERY

    /**
     * The app-made topic a folder path starts with, or null for a folder the
     * user named themselves. "Video/Liburan" gives "Video"; "Kerja" gives null,
     * so a hand-picked folder is never re-filed behind the user's back.
     */
    fun generatedTopicOf(folder: String): String? {
        val head = folder.substringBefore('/').trim()
        return generated.firstOrNull { it.equals(head, ignoreCase = true) }
    }

    private val photos = setOf(
        "jpg", "jpeg", "jpe", "png", "gif", "webp", "heic", "heif", "avif", "bmp",
        "tif", "tiff", "svg", "raw", "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2",
    )
    private val videos = setOf(
        "mp4", "m4v", "mkv", "mov", "avi", "webm", "wmv", "flv", "mpg", "mpeg",
        "mpe", "3gp", "3g2", "ts", "m2ts", "mts", "vob", "ogv",
    )
    private val audio = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "amr",
        "mid", "midi", "aiff", "aif", "ape",
    )
    private val documents = setOf(
        "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "markdown", "csv", "tsv",
        "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub", "mobi", "azw3", "html",
        "htm", "json", "xml", "yaml", "yml", "log", "ics", "vcf",
    )
    private val archives = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "iso",
        "cab", "jar", "war",
    )
    private val apps = setOf(
        "apk", "aab", "apks", "xapk", "exe", "msi", "msix", "deb", "rpm", "dmg",
        "pkg", "appimage", "ipa", "app",
    )

    /**
     * Topic for a file name, based on its extension. Unknown and extension-less
     * names still get a topic ("Lainnya"), so no upload ever lands loose in
     * General and the user never has to sort anything by hand.
     */
    fun folderFor(fileName: String): String {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return OTHER
        return when (name.substring(dot + 1).lowercase()) {
            in photos -> PHOTOS
            in videos -> VIDEOS
            in audio -> AUDIO
            in documents -> DOCUMENTS
            in archives -> ARCHIVES
            in apps -> APPS
            else -> OTHER
        }
    }
}
