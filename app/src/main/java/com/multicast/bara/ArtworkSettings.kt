package com.multicast.bara

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ArtworkSettings(
    val tags: String = "bara",
    val rating: ArtworkRating = ArtworkRating.GENERAL,
    val danbooru: Boolean = true,
    val safebooru: Boolean = true,
    val automatic: Boolean = true,
    val wifiOnly: Boolean = true,
    val intervalMinutes: Long = 360,
    val aspect: ArtworkAspect = ArtworkAspect.SCREEN,
    val screenRatio: Double = 16.0 / 9.0
) {
    val sources get() = ArtworkSource.entries.filter {
        if (it == ArtworkSource.DANBOORU) danbooru else safebooru
    }
}

data class CachedArtwork(
    val post: ArtworkPost,
    val fileName: String,
    val savedAt: Long
)

class ArtworkStore(private val context: Context) {
    companion object { val publicationLock = Any() }
    private val prefs = context.getSharedPreferences("artwork", Context.MODE_PRIVATE)
    val directory = File(context.filesDir, "artwork")

    fun settings() = ArtworkSettings(
        tags = prefs.getString("tags", "bara") ?: "bara",
        rating = ArtworkRating.entries.getOrElse(prefs.getInt("rating", 0)) { ArtworkRating.GENERAL },
        danbooru = prefs.getBoolean("danbooru", true),
        safebooru = prefs.getBoolean("safebooru", true),
        automatic = prefs.getBoolean("automatic", true),
        wifiOnly = prefs.getBoolean("wifi", true),
        intervalMinutes = prefs.getLong("interval", 360).coerceIn(15, 1440),
        aspect = ArtworkAspect.entries.firstOrNull { it.name == prefs.getString("aspect", null) }
            ?: ArtworkAspect.SCREEN,
        screenRatio = savedScreenRatio()
    )

    fun save(settings: ArtworkSettings) = synchronized(publicationLock) {
        prefs.edit().putString("tags", settings.tags).putInt("rating", settings.rating.ordinal)
            .putBoolean("danbooru", settings.danbooru).putBoolean("safebooru", settings.safebooru)
            .putBoolean("automatic", settings.automatic).putBoolean("wifi", settings.wifiOnly)
            .putLong("interval", settings.intervalMinutes).putString("aspect", settings.aspect.name)
            .putString("screen_ratio", settings.screenRatio.toString()).remove("frame").apply()
    }

    fun current(): CachedArtwork? = runCatching {
        val json = JSONObject(prefs.getString("current", null) ?: return null)
        val source = ArtworkSource.valueOf(json.getString("source"))
        val post = ArtworkPost(source, json.getString("id"), json.getString("fingerprint"),
            ArtworkRating.valueOf(json.getString("rating")),
            json.getString("tags").split(' ').toSet(), json.getString("image"),
            json.getString("page"), json.getString("artist"), json.getInt("width"), json.getInt("height"))
        val fileName = json.getString("file")
        require(fileName.matches(Regex("[a-f0-9-]+\\.jpg")))
        val settings = settings()
        if (source !in settings.sources || !ArtworkPolicy.accepts(post, TagQuery.parse(settings.tags),
                settings.rating, settings.aspect, settings.screenRatio)) return null
        // Ignore crop metadata from older versions and serve the complete source image.
        CachedArtwork(post, fileName, json.getLong("saved"))
            .takeIf { File(directory, it.fileName).isFile }
    }.getOrNull()

    /** File is fully written and renamed before its metadata is committed. */
    private fun encode(artwork: CachedArtwork): JSONObject {
        val p = artwork.post
        return JSONObject().put("source", p.source.name).put("id", p.id)
            .put("fingerprint", p.fingerprint).put("rating", p.rating.name)
            .put("tags", p.tags.joinToString(" ")).put("image", p.imageUrl).put("page", p.pageUrl)
            .put("artist", p.artist).put("width", p.width).put("height", p.height)
            .put("file", artwork.fileName).put("saved", artwork.savedAt)
    }

    fun setCurrent(artwork: CachedArtwork): Boolean = synchronized(publicationLock) {
        val ratio = settings().screenRatio
        val p = artwork.post
        val history = (recent() + p.fingerprint).takeLast(64).joinToString("\n")
        prefs.edit().putString("current", encode(artwork).toString()).putString("recent", history)
            .putString("screen_ratio", ratio.toString())
            .putLong("last_success", artwork.savedAt).putString("status_key", ArtworkMessage.UPDATED.name)
            .remove("status").remove("error_key").remove("error_args").commit()
    }

    fun prune() = synchronized(publicationLock) {
        val current = current() ?: return@synchronized
        directory.listFiles()?.filter { it.name != current.fileName }
            ?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
    }

    fun recent(): List<String> = prefs.getString("recent", "").orEmpty().lines().filter(String::isNotBlank)
    fun status(): String {
        val message = ArtworkMessage.entries.firstOrNull { it.name == prefs.getString("status_key", null) }
            ?: if (current() != null) ArtworkMessage.UPDATED else ArtworkMessage.EMPTY
        if (message != ArtworkMessage.UPDATE_FAILED) return context.getString(message.resource)
        val failure = runCatching {
            val error = ArtworkMessage.valueOf(prefs.getString("error_key", null) ?: return@runCatching null)
            val args = JSONArray(prefs.getString("error_args", "[]"))
            ArtworkFailure(error, (0 until args.length()).map { args.getString(it) }).text(context)
        }.getOrNull() ?: context.getString(R.string.error_unknown)
        return context.getString(message.resource, failure)
    }

    fun status(message: ArtworkMessage, failure: ArtworkFailure? = null) {
        prefs.edit().putString("status_key", message.name).remove("status")
            .putString("error_key", failure?.message?.name)
            .putString("error_args", failure?.let { JSONArray(it.arguments).toString() }).apply()
    }
    fun markAttempt() { prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply() }

    @Suppress("DEPRECATION") // Full display dimensions are also needed outside an Activity on API 23+.
    fun deviceScreenRatio(): Double {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display?.getRealMetrics(metrics)
        val actual = if (metrics.widthPixels > 0 && metrics.heightPixels > 0) metrics else context.resources.displayMetrics
        return if (actual.widthPixels > 0 && actual.heightPixels > 0)
            actual.widthPixels.toDouble() / actual.heightPixels else 16.0 / 9.0
    }

    private fun savedScreenRatio(): Double {
        val saved = prefs.getString("screen_ratio", null)?.toDoubleOrNull()
        if (saved != null && saved.isFinite() && saved in 0.25..4.0) return saved
        // Upgrade old caches without letting Home's orientation invalidate their background.
        val legacy = runCatching {
            val json = JSONObject(prefs.getString("current", null) ?: return@runCatching null)
            json.getDouble("width") / json.getDouble("height")
        }.getOrNull()
        val resolved = AspectTarget.resolve(saved, legacy, deviceScreenRatio())
        return synchronized(publicationLock) {
            val existing = prefs.getString("screen_ratio", null)?.toDoubleOrNull()
            if (existing != null && existing.isFinite() && existing in 0.25..4.0) existing else {
                prefs.edit().putString("screen_ratio", resolved.toString()).apply()
                resolved
            }
        }
    }

    fun isDue(): Boolean {
        val now = System.currentTimeMillis()
        val attempted = prefs.getLong("last_attempt", 0)
        val succeeded = prefs.getLong("last_success", 0)
        return (now < attempted || now - attempted >= 15 * 60_000L) &&
            (current() == null || now < succeeded || now - succeeded >= settings().intervalMinutes * 60_000L)
    }
}
