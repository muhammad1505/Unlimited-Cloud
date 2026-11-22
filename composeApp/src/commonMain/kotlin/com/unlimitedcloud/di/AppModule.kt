package com.unlimitedcloud.di

import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelManager
import com.unlimitedcloud.cloud.CloudFileRepository
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.config.TelegramConfig
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
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<TdJsonClient> { createTdJsonClient() }
    single { TdJsonSession(client = get(), scope = get()) }
    single<TelegramEngine> { TdLibTelegramEngine(session = get(), config = get(), scope = get()) }
    single { CloudChannelManager(session = get()) }
    single { CloudFileRepository(session = get()) }
    factory { CloudFilesViewModel(repository = get(), channelManager = get(), scope = get()) }
}
