package com.unlimitedcloud.cloud

import kotlin.test.Test
import kotlin.test.assertEquals

class FileCategoriesTest {

    @Test
    fun commonTypesLandInTheirTopic() {
        assertEquals(FileCategories.PHOTOS, FileCategories.folderFor("IMG_2024.HEIC"))
        assertEquals(FileCategories.VIDEOS, FileCategories.folderFor("liburan.mkv"))
        assertEquals(FileCategories.AUDIO, FileCategories.folderFor("lagu.flac"))
        assertEquals(FileCategories.DOCUMENTS, FileCategories.folderFor("laporan.pdf"))
        assertEquals(FileCategories.ARCHIVES, FileCategories.folderFor("backup.tar.gz"))
        assertEquals(FileCategories.APPS, FileCategories.folderFor("app-release.apk"))
    }

    @Test
    fun extensionsAreMatchedCaseInsensitively() {
        assertEquals(FileCategories.PHOTOS, FileCategories.folderFor("foto.JPG"))
        assertEquals(FileCategories.DOCUMENTS, FileCategories.folderFor("NOTES.Md"))
    }

    @Test
    fun aPathIsClassifiedByItsFileName() {
        assertEquals(FileCategories.VIDEOS, FileCategories.folderFor("DCIM/Camera/clip.mp4"))
        assertEquals(FileCategories.PHOTOS, FileCategories.folderFor("C:\\Users\\me\\foto.png"))
    }

    @Test
    fun unknownAndMissingExtensionsFallBackToTheLastTopic() {
        assertEquals(FileCategories.OTHER, FileCategories.folderFor("catatan"))
        assertEquals(FileCategories.OTHER, FileCategories.folderFor("data.xyz"))
        assertEquals(FileCategories.OTHER, FileCategories.folderFor("berkas."))
        assertEquals(FileCategories.OTHER, FileCategories.folderFor(".gitignore"))
        assertEquals(FileCategories.OTHER, FileCategories.folderFor(""))
    }

    @Test
    fun everyTopicIsOfferedOnce() {
        assertEquals(FileCategories.all.size, FileCategories.all.distinct().size)
    }
}
