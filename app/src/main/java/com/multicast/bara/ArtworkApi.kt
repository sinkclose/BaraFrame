package com.multicast.bara

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** No credentials, arbitrary hosts, insecure redirects or unbounded downloads. */
class ArtworkHttp {
    companion object {
        fun allowedUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
                uri.host in setOf("danbooru.donmai.us", "cdn.donmai.us", "safebooru.org")
        }.getOrDefault(false)
    }

    fun get(url: String): String {
        val output = java.io.ByteArrayOutputStream()
        transfer(url, output, 2 * 1024 * 1024, false)
        return output.toString("UTF-8")
    }

    fun transfer(url: String, output: OutputStream, limit: Int, image: Boolean) {
        var target = url
        val deadline = SystemClock.elapsedRealtime() + 45_000
        repeat(4) {
            if (!allowedUrl(target)) throw ArtworkException(ArtworkMessage.URL)
            val connection = URI(target).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "BaraFrame/1.3.1 (Android background provider)")
                connection.setRequestProperty("Accept", if (image) "image/jpeg,image/png,image/webp" else "application/json")
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    target = URI(target).resolve(connection.getHeaderField("Location")
                        ?: throw ArtworkException(ArtworkMessage.REDIRECT)).toString()
                    return@repeat
                }
                if (code != 200) throw ArtworkException(ArtworkMessage.HTTP, code.toString(), URI(target).host)
                if ((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1) > limit) {
                    throw ArtworkException(ArtworkMessage.FILE_TOO_LARGE)
                }
                val mime = connection.contentType.orEmpty().substringBefore(';').lowercase()
                if (image && mime !in setOf("image/jpeg", "image/png", "image/webp")) {
                    throw ArtworkException(ArtworkMessage.IMAGE_FORMAT)
                }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() > deadline) {
                            throw ArtworkException(ArtworkMessage.TIMEOUT)
                        }
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > limit) throw ArtworkException(ArtworkMessage.FILE_TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                }
                return
            } finally { connection.disconnect() }
        }
        throw ArtworkException(ArtworkMessage.REDIRECT_LIMIT)
    }
}

object ArtworkParser {
    fun parse(source: ArtworkSource, payload: String): List<ArtworkPost> {
        val array = JSONArray(payload)
        return (0 until array.length()).mapNotNull { index ->
            runCatching { parsePost(source, array.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun parsePost(source: ArtworkSource, json: JSONObject): ArtworkPost? {
        val rating = ArtworkRating.parse(source, json.optString("rating")) ?: return null
        if (json.optBoolean("is_deleted") || json.optBoolean("is_banned") ||
            json.optBoolean("is_pending") || json.optBoolean("is_flagged")) return null
        if (source == ArtworkSource.SAFEBOORU && json.optString("status", "active") != "active") return null
        val id = json.optString("id").takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val tags = json.optString(if (source == ArtworkSource.DANBOORU) "tag_string" else "tags")
            .split(Regex("\\s+")).filter(String::isNotBlank).toSet()
        if (tags.isEmpty() || tags.any { it in setOf("animated", "video", "animated_gif", "animated_webp") }) return null
        val candidates = if (source == ArtworkSource.DANBOORU)
            listOf(json.optString("file_url"), json.optString("large_file_url"))
        else listOf(json.optString("file_url"), json.optString("sample_url"))
        val image = candidates.firstOrNull { url ->
            ArtworkHttp.allowedUrl(url) && URI(url).path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp")
        } ?: return null
        val hash = json.optString(if (source == ArtworkSource.DANBOORU) "md5" else "hash")
        return ArtworkPost(source, id, hash.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: "${source.name}:$id",
            rating, tags, image,
            if (source == ArtworkSource.DANBOORU) "https://${source.host}/posts/$id"
            else "https://${source.host}/index.php?page=post&s=view&id=$id",
            json.optString("tag_string_artist").replace('_', ' '),
            json.optInt(if (source == ArtworkSource.DANBOORU) "image_width" else "width"),
            json.optInt(if (source == ArtworkSource.DANBOORU) "image_height" else "height"))
    }
}

class ArtworkApi(private val http: ArtworkHttp = ArtworkHttp()) {
    fun posts(source: ArtworkSource, query: TagQuery, rating: ArtworkRating, page: Int): List<ArtworkPost> {
        // One positive tag + rating stays within anonymous Danbooru search limits.
        // All remaining positive and negative tags are enforced locally.
        val sourceRating = if (source == ArtworkSource.SAFEBOORU) "safe" else when (rating) {
            ArtworkRating.GENERAL -> "g"
            ArtworkRating.SENSITIVE -> "s"
            ArtworkRating.QUESTIONABLE -> "q"
            ArtworkRating.EXPLICIT -> "e"
        }
        val tags = URLEncoder.encode("${query.required.first()} rating:$sourceRating", "UTF-8")
        val url = if (source == ArtworkSource.DANBOORU)
            "https://${source.host}/posts.json?limit=100&page=${page + 1}&tags=$tags"
        else "https://${source.host}/index.php?page=dapi&s=post&q=index&json=1&limit=100&pid=$page&tags=$tags"
        return ArtworkParser.parse(source, http.get(url))
    }
}
