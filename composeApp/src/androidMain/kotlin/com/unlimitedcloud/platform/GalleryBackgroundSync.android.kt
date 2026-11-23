package com.unlimitedcloud.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudFilesViewModel
import com.unlimitedcloud.di.appModule
import com.unlimitedcloud.settings.AppPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private const val GALLERY_WORK_NAME = "gallery-background-sync"
private const val SYNC_INTERVAL_HOURS = 6L
private const val AUTH_TIMEOUT_MS = 90_000L
private const val SYNC_START_TIMEOUT_MS = 10_000L
private const val SYNC_WORK_TIMEOUT_MS = 8 * 60_000L
private const val BACKGROUND_BATCH_SIZE = 8

actual fun createGalleryBackgroundSync(): GalleryBackgroundSync =
    AndroidGalleryBackgroundSync(AndroidPlatform.requireContext())

private class AndroidGalleryBackgroundSync(
    private val context: Context,
) : GalleryBackgroundSync {

    override val supported: Boolean = true

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<GallerySyncWorker>(
            SYNC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.MINUTES)
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                GALLERY_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    override fun cancel() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(GALLERY_WORK_NAME) }
    }
}

/**
 * One incremental upload with a small batch so it fits WorkManager's ten-minute
 * execution window. It exits quietly when the feature is off, the session needs
 * the UI to finish signing in, or another sync is already running.
 */
class GallerySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = koinOrStart() ?: return Result.retry()

        val preferences = koin.get<AppPreferences>()
        if (!preferences.galleryAutoSync() || !preferences.galleryBackgroundSync()) {
            return Result.success()
        }
        if (preferences.galleryFolders().isEmpty()) return Result.success()

        val engine = koin.get<TelegramEngine>()
        engine.start()
        val ready = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            engine.authState.first { it is AuthUiState.Ready }
        }
        if (ready == null) {
            // A session that needs a phone number or code can only be finished in
            // the UI, so retrying in the background would never succeed.
            return if (engine.authState.value is AuthUiState.Initializing) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        val viewModel = koin.get<CloudFilesViewModel>()
        viewModel.syncGallery(auto = true, limit = BACKGROUND_BATCH_SIZE)

        val started = withTimeoutOrNull(SYNC_START_TIMEOUT_MS) {
            viewModel.uiState.first { it.gallerySyncing }
        } != null
        if (!started) return Result.success()

        val finished = withTimeoutOrNull(SYNC_WORK_TIMEOUT_MS) {
            viewModel.uiState.first { !it.gallerySyncing }
        }
        return if (finished != null) Result.success() else Result.retry()
    }

    /**
     * WorkManager can start the process without MainActivity, so Koin may not be
     * running yet. Starting it here is safe: MainActivity only starts Koin when
     * the global context is still empty.
     */
    private fun koinOrStart(): Koin? {
        GlobalContext.getOrNull()?.let { return it }
        AndroidPlatform.init(applicationContext)
        return runCatching {
            startKoin {
                androidContext(applicationContext)
                modules(appModule)
            }.koin
        }.getOrElse { GlobalContext.getOrNull() }
    }
}
