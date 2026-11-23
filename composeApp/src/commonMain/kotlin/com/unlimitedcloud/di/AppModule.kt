package com.unlimitedcloud.di

import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelManager
import com.unlimitedcloud.cloud.CloudFileRepository
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.config.TelegramConfig
import com.unlimitedcloud.platform.GalleryBackgroundSync
import com.unlimitedcloud.platform.GallerySyncSource
import com.unlimitedcloud.platform.MediaSaver
import com.unlimitedcloud.platform.createGalleryBackgroundSync
import com.unlimitedcloud.platform.createGallerySyncSource
import com.unlimitedcloud.platform.createMediaSaver
import com.unlimitedcloud.settings.AppPreferences
import com.unlimitedcloud.tdjson.TdJsonClient
import com.unlimitedcloud.tdjson.TdJsonSession
import com.unlimitedcloud.tdjson.TdLibTelegramEngine
import com.unlimitedcloud.tdjson.createTdJsonClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val appModule = module {
    single { TelegramConfig.fromBuild() }
    single { AppPreferences() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<TdJsonClient> { createTdJsonClient() }
    single { TdJsonSession(client = get(), scope = get()) }
    single<TelegramEngine> { TdLibTelegramEngine(session = get(), config = get(), scope = get()) }
    single { CloudChannelManager(session = get(), preferences = get()) }
    single { CloudFileRepository(session = get()) }
    single<GallerySyncSource> { createGallerySyncSource() }
    single<GalleryBackgroundSync> { createGalleryBackgroundSync() }
    single<MediaSaver> { createMediaSaver() }
    // A single instance keeps files, gallery sync and transfer progress alive
    // while the user switches between the Berkas, Transfer and Pengaturan tabs.
    single {
        CloudFilesViewModel(
            repository = get(),
            channelManager = get(),
            preferences = get(),
            gallerySource = get(),
            backgroundSync = get(),
            mediaSaver = get(),
            scope = get(),
        )
    }
}
