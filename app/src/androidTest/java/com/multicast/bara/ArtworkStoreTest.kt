package com.multicast.bara

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArtworkStoreTest {
    private lateinit var store: ArtworkStore
    private lateinit var directory: File
    private lateinit var base: Context
    private lateinit var preferenceName: String

    @Before fun prepare() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceName = "test-artwork-${UUID.randomUUID()}"
        directory = File(base.cacheDir, preferenceName).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                base.getSharedPreferences(preferenceName, mode)
        }
        store = ArtworkStore(isolated)
        store.save(store.settings().copy(aspect = ArtworkAspect.ANY))
    }

    @After fun cleanup() {
        base.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
        directory.deleteRecursively()
    }

    private fun cache(rating: ArtworkRating = ArtworkRating.GENERAL): CachedArtwork {
        store.directory.mkdirs()
        val fileName = "${UUID.randomUUID()}.jpg"
        File(store.directory, fileName).writeBytes(byteArrayOf(1))
        val post = ArtworkPost(ArtworkSource.DANBOORU, "123", "a-fingerprint", rating, setOf("bara", "male_focus"),
            "https://cdn.donmai.us/art.jpg", "https://danbooru.donmai.us/posts/123", "artist", 1200, 1600)
        return CachedArtwork(post, fileName, System.currentTimeMillis()).also { assertTrue(store.setCurrent(it)) }
    }

    @Test fun metadataAndHistorySurviveRoundTrip() {
        val original = cache()
        assertEquals(original, store.current())
        assertTrue(original.post.fingerprint in store.recent())
        assertFalse(store.isDue())
    }

    @Test fun reducingRatingImmediatelyInvalidatesCachedArtwork() {
        store.save(store.settings().copy(rating = ArtworkRating.EXPLICIT))
        cache(ArtworkRating.EXPLICIT)
        assertNotNull(store.current())
        store.save(store.settings().copy(rating = ArtworkRating.GENERAL))
        assertNull(store.current())
    }

    @Test fun changedTagsAndDisabledSourceCannotReuseOldCache() {
        cache()
        store.save(store.settings().copy(tags = "shounen_ai"))
        assertNull(store.current())
        store.save(store.settings().copy(tags = "bara", danbooru = false))
        assertNull(store.current())
    }

    @Test fun missingFileFallsBackWithoutBrokenUri() {
        val original = cache()
        File(store.directory, original.fileName).delete()
        assertNull(store.current())
    }
}
