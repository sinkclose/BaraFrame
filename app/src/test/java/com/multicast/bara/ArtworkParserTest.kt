package com.multicast.bara

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtworkParserTest {
    private fun json() = JSONObject().put("id", 123).put("rating", "g")
        .put("tag_string", "bara male_focus").put("image_width", 1200).put("image_height", 1600)
        .put("file_url", "https://cdn.donmai.us/original/art.jpg")
        .put("tag_string_artist", "test_artist").put("md5", "0123456789abcdef0123456789abcdef")

    private fun parse(json: JSONObject) = ArtworkParser.parse(ArtworkSource.DANBOORU, JSONArray().put(json).toString())

    @Test fun preservesAttributionAndRating() {
        val post = parse(json()).single()
        assertEquals("https://danbooru.donmai.us/posts/123", post.pageUrl)
        assertEquals("test artist", post.artist)
        assertEquals(ArtworkRating.GENERAL, post.rating)
        assertEquals("0123456789abcdef0123456789abcdef", post.fingerprint)
    }

    @Test fun prefersOriginalAndFallsBackToSample() {
        val json = json().put("large_file_url", "https://cdn.donmai.us/sample/art.jpg")
        assertTrue(parse(json).single().imageUrl.contains("/original/"))
        json.put("file_url", JSONObject.NULL)
        assertTrue(parse(json).single().imageUrl.contains("/sample/"))
    }

    @Test fun rejectsUnknownRatingsAndUnavailablePosts() {
        assertTrue(parse(json().put("rating", "unknown")).isEmpty())
        listOf("is_banned", "is_deleted", "is_flagged", "is_pending").forEach {
            assertTrue(parse(json().put(it, true)).isEmpty())
        }
    }

    @Test fun rejectsVideoAndAnimation() {
        assertTrue(parse(json().put("file_url", "https://cdn.donmai.us/art.webm")).isEmpty())
        assertTrue(parse(json().put("tag_string", "bara animated")).isEmpty())
    }

    @Test fun rejectsUntrustedAndInsecureUrls() {
        listOf("http://cdn.donmai.us/art.jpg", "https://evil.example/art.jpg", "https://cdn.donmai.us.evil.example/art.jpg",
            "https://user@cdn.donmai.us/art.jpg", "https://127.0.0.1/art.jpg", "file:///tmp/art.jpg", "https://cdn.donmai.us:444/art.jpg")
            .forEach { url ->
                assertFalse(url, ArtworkHttp.allowedUrl(url))
                assertTrue(parse(json().put("file_url", url)).isEmpty())
            }
    }

    @Test fun handlesBothRealSourceSchemas() {
        val danbooru = javaClass.getResource("/danbooru-general.json")!!.readText()
        val safebooru = javaClass.getResource("/safebooru-safe.json")!!.readText()
        assertEquals(ArtworkRating.GENERAL, ArtworkParser.parse(ArtworkSource.DANBOORU, danbooru).single().rating)
        assertEquals(ArtworkRating.GENERAL, ArtworkParser.parse(ArtworkSource.SAFEBOORU, safebooru).single().rating)
    }

    @Test fun malformedEntryDoesNotDiscardValidNeighbors() {
        val payload = JSONArray().put(JSONObject().put("id", "bad")).put(json()).toString()
        assertEquals(1, ArtworkParser.parse(ArtworkSource.DANBOORU, payload).size)
    }

    @Test fun errorResponseIsNotAnEmptySuccess() {
        assertThrows(org.json.JSONException::class.java) {
            ArtworkParser.parse(ArtworkSource.DANBOORU, "{\"success\":false,\"message\":\"rate limited\"}")
        }
    }
}
