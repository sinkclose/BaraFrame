package com.multicast.bara

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object ArtworkScheduler {
    const val REFRESH = "artwork-refresh"
    const val PERIODIC = "artwork-periodic"
    const val TAG = "artwork-jobs"

    private fun constraints(settings: ArtworkSettings) = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    fun schedule(context: Context, update: Boolean = true) {
        val settings = ArtworkStore(context).settings()
        val manager = WorkManager.getInstance(context)
        // Cancel pending local crop jobs left by older versions.
        manager.cancelUniqueWork("artwork-crop")
        if (!settings.automatic) {
            manager.cancelUniqueWork(PERIODIC)
            return
        }
        val work = PeriodicWorkRequestBuilder<ArtworkWorker>(settings.intervalMinutes, TimeUnit.MINUTES)
            .setInitialDelay(settings.intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints(settings)).addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun refresh(context: Context, force: Boolean = true) {
        val work = OneTimeWorkRequestBuilder<ArtworkWorker>()
            .setInputData(workDataOf("force" to force)).setConstraints(constraints(ArtworkStore(context).settings()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH, ExistingWorkPolicy.KEEP, work)
    }

    fun cancelRefresh(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(REFRESH)
        manager.cancelUniqueWork(PERIODIC)
        schedule(context)
        ArtworkStore(context).status(ArtworkMessage.CANCELLED)
    }
}

class ArtworkWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    companion object { private val lock = Any() }

    override fun doWork(): Result = synchronized(lock) {
        val store = ArtworkStore(applicationContext)
        val settings = store.settings()
        val forced = inputData.getBoolean("force", false)
        if (isStopped || (!forced && !settings.automatic)) return@synchronized Result.success()
        if (!forced && runAttemptCount == 0 && !store.isDue()) return@synchronized Result.success()
        store.markAttempt()
        store.status(ArtworkMessage.SEARCHING)
        val query = try { TagQuery.parse(settings.tags) } catch (e: TagQueryException) {
            store.status(e.reason.message)
            return@synchronized Result.failure()
        }
        val api = ArtworkApi()
        val recent = store.recent().toSet()
        var networkFailed = false
        var downloadFailed = false
        var lastError = ArtworkFailure(ArtworkMessage.UNKNOWN)
        var requests = 0
        var downloads = 0
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(7)
        // Shuffle providers so one provider doesn't monopolize the rotation.
        search@ for (source in settings.sources.shuffled()) {
            val ratings = if (source == ArtworkSource.SAFEBOORU) listOf(ArtworkRating.GENERAL)
                else ArtworkRating.entries.filter { it.ordinal <= settings.rating.ordinal }.shuffled()
            for (rating in ratings) {
                // A small historical window adds variety without potentially huge page scans.
                for (page in listOf(Random.nextInt(1, 5), 0)) {
                    stopResult(settings, store)?.let { return@synchronized it }
                    if (requests >= 8 || downloads >= 3 || SystemClock.elapsedRealtime() >= deadline) break@search
                    requests++
                    val posts = try { api.posts(source, query, rating, page) } catch (e: Exception) {
                        networkFailed = true
                        lastError = ArtworkFailure.from(e)
                        continue@search
                    }
                    val candidates = posts.filter {
                        ArtworkPolicy.accepts(it, query, settings.rating, settings.aspect, settings.screenRatio) &&
                            it.fingerprint !in recent
                    }.shuffled().take(3)
                    for (post in candidates) {
                        stopResult(settings, store)?.let { return@synchronized it }
                        if (downloads >= 3 || SystemClock.elapsedRealtime() >= deadline) break@search
                        downloads++
                        val cached = try {
                            download(store, post, settings)
                        } catch (e: Exception) {
                            downloadFailed = true
                            lastError = ArtworkFailure.from(e)
                            continue
                        }
                        val published = synchronized(ArtworkStore.publicationLock) {
                            stopResult(settings, store) == null && store.setCurrent(cached)
                        }
                        if (!published) {
                            File(store.directory, cached.fileName).delete()
                            stopResult(settings, store)?.let { return@synchronized it }
                            store.status(ArtworkMessage.SAVE_FAILED)
                            return@synchronized Result.failure()
                        }
                        ExampleBackgroundReceiver.broadcastUpdate(applicationContext)
                        runCatching { store.prune() }.onFailure {
                            Log.w("ArtworkWorker", "Could not prune old artwork", it)
                        }
                        return@synchronized Result.success()
                    }
                }
            }
        }
        if (isStopped) return@synchronized Result.success()
        val failed = networkFailed || downloadFailed
        if (failed) store.status(ArtworkMessage.UPDATE_FAILED, lastError)
        else store.status(ArtworkMessage.NO_RESULTS)
        if (failed && runAttemptCount < 2) Result.retry() else if (failed) Result.failure() else Result.success()
    }

    private fun stopResult(settings: ArtworkSettings, store: ArtworkStore): Result? {
        if (isStopped) return Result.success()
        val latest = store.settings()
        if (settings != latest) return Result.success()
        return null
    }

    private fun download(store: ArtworkStore, post: ArtworkPost, settings: ArtworkSettings): CachedArtwork {
        if (!store.directory.exists() && !store.directory.mkdirs()) throw ArtworkException(ArtworkMessage.STORAGE)
        val raw = File.createTempFile("download-", ".tmp", applicationContext.cacheDir)
        val temporary = File(store.directory, "${UUID.randomUUID()}.tmp")
        try {
            raw.outputStream().use { ArtworkHttp().transfer(post.imageUrl, it, 15 * 1024 * 1024, true) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            if (bounds.outWidth < 600 || bounds.outHeight < 600 ||
                bounds.outWidth.toLong() * bounds.outHeight > 80_000_000L) throw ArtworkException(ArtworkMessage.IMAGE_SIZE)
            if (!settings.aspect.accepts(bounds.outWidth, bounds.outHeight, settings.screenRatio)) {
                throw ArtworkException(ArtworkMessage.IMAGE_ASPECT)
            }
            val options = BitmapFactory.Options().apply {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                inSampleSize = sample
            }
            val bitmap = BitmapFactory.decodeFile(raw.path, options) ?: throw ArtworkException(ArtworkMessage.IMAGE_CORRUPT)
            try {
                // JPEG gives ClockDesk a static, bounded, universally supported texture.
                val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(flattened).apply { drawColor(Color.rgb(21, 25, 34)); drawBitmap(bitmap, 0f, 0f, null) }
                    temporary.outputStream().use {
                        if (!flattened.compress(Bitmap.CompressFormat.JPEG, 92, it)) throw ArtworkException(ArtworkMessage.IMAGE_PROCESSING)
                    }
                } finally { flattened.recycle() }
            } finally { bitmap.recycle() }
            val destination = File(store.directory, "${UUID.randomUUID()}.jpg")
            if (!temporary.renameTo(destination)) throw ArtworkException(ArtworkMessage.SAVE_FAILED)
            return CachedArtwork(post.copy(width = bounds.outWidth, height = bounds.outHeight),
                destination.name, System.currentTimeMillis())
        } finally { raw.delete(); temporary.delete() }
    }

}
