package com.multicast.bara

import org.junit.Assert.*
import org.junit.Test

class ArtworkPolicyTest {
    private fun post(rating: ArtworkRating = ArtworkRating.GENERAL, tags: Set<String> = setOf("bara", "male_focus")) =
        ArtworkPost(ArtworkSource.DANBOORU, "1", "hash", rating, tags,
            "https://cdn.donmai.us/art.jpg", "https://danbooru.donmai.us/posts/1", "artist", 1200, 1600)

    @Test fun sensitiveIsNotSafeOnDanbooru() {
        assertEquals(ArtworkRating.SENSITIVE, ArtworkRating.parse(ArtworkSource.DANBOORU, "s"))
        assertEquals(ArtworkRating.GENERAL, ArtworkRating.parse(ArtworkSource.SAFEBOORU, "s"))
        assertEquals(ArtworkRating.GENERAL, ArtworkRating.parse(ArtworkSource.SAFEBOORU, "safe"))
        assertNull(ArtworkRating.parse(ArtworkSource.SAFEBOORU, "e"))
        assertNull(ArtworkRating.parse(ArtworkSource.DANBOORU, "safe"))
        assertNull(ArtworkRating.parse(ArtworkSource.DANBOORU, ""))
    }

    @Test fun sfwRejectsEveryHigherRating() {
        val query = TagQuery.parse("bara")
        ArtworkRating.entries.forEach { rating ->
            assertEquals(rating == ArtworkRating.GENERAL, ArtworkPolicy.accepts(post(rating), query, ArtworkRating.GENERAL))
        }
    }

    @Test fun ratingCeilingAllowsOnlySelectedLevelAndBelow() {
        val query = TagQuery.parse("bara")
        ArtworkRating.entries.forEach { ceiling ->
            ArtworkRating.entries.forEach { rating ->
                assertEquals(rating.ordinal <= ceiling.ordinal, ArtworkPolicy.accepts(post(rating), query, ceiling))
            }
        }
    }

    @Test fun allTagsAndExclusionsAreEnforcedLocally() {
        val query = TagQuery.parse("BARA male_focus -comic")
        assertTrue(query.matches(setOf("bara", "male_focus", "beard")))
        assertFalse(query.matches(setOf("bara")))
        assertFalse(query.matches(setOf("bara", "male_focus", "comic")))
        assertFalse(query.matches(setOf("barazoku", "male_focus")))
        assertEquals("bara male_focus -comic", query.text)
    }

    @Test fun tagsCannotOverrideRatingOrInjectSearchOperators() {
        listOf("", "-comic", "bara -bara", "bara rating:e", "bara*", "bara ~yaoi", "bara --comic", "bara -",
            (1..9).joinToString(" ") { "tag$it" }).forEach {
            assertThrows("Must reject $it", IllegalArgumentException::class.java) { TagQuery.parse(it) }
        }
    }

    @Test fun youngCharactersAndGoreAreRejectedAtAllRatingLevels() {
        listOf("shota", "child", "underage", "loli", "gore").forEach { tag ->
            assertFalse(ArtworkPolicy.accepts(post(ArtworkRating.EXPLICIT, setOf("bara", tag)),
                TagQuery.parse("bara"), ArtworkRating.EXPLICIT))
        }
    }

    @Test fun lowResolutionIsRejected() {
        assertFalse(ArtworkPolicy.accepts(post().copy(width = 599), TagQuery.parse("bara"), ArtworkRating.GENERAL))
    }
}
