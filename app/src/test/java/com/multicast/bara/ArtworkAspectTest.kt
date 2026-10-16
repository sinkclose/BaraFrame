package com.multicast.bara

import org.junit.Assert.*
import org.junit.Test

class ArtworkAspectTest {
    private val screen = 16.0 / 9.0

    @Test fun squareAndNearSquareImagesAreOnlyAllowedWhenUnrestricted() {
        for ((width, height) in listOf(1000 to 1000, 1100 to 1000, 1000 to 1100, 1200 to 1000)) {
            for (aspect in ArtworkAspect.entries) {
                assertEquals("$aspect $width x $height", aspect == ArtworkAspect.ANY,
                    aspect.accepts(width, height, screen))
            }
        }
    }

    @Test fun standardFormatsAreAcceptedAtBothEndsOfTheOrientationRange() {
        for ((width, height) in listOf(1600 to 1200, 1920 to 1080, 1920 to 1200, 2100 to 900)) {
            assertTrue(ArtworkAspect.LANDSCAPE.accepts(width, height, screen))
            assertFalse(ArtworkAspect.PORTRAIT.accepts(width, height, screen))
            assertTrue(ArtworkAspect.PORTRAIT.accepts(height, width, screen))
            assertFalse(ArtworkAspect.LANDSCAPE.accepts(height, width, screen))
        }
    }

    @Test fun bannersAndVeryTallImagesAreExcluded() {
        for (aspect in listOf(ArtworkAspect.SCREEN, ArtworkAspect.LANDSCAPE, ArtworkAspect.PORTRAIT)) {
            assertFalse(aspect.accepts(3200, 900, screen))
            assertFalse(aspect.accepts(900, 3200, 1.0 / screen))
        }
        assertTrue(ArtworkAspect.ANY.accepts(3200, 900, screen))
    }

    @Test fun screenModeLimitsCroppingInEitherDirection() {
        assertTrue(ArtworkAspect.SCREEN.accepts(1920, 1080, screen))
        assertTrue(ArtworkAspect.SCREEN.accepts(1920, 1200, screen))
        assertFalse(ArtworkAspect.SCREEN.accepts(1600, 1200, screen))
        assertFalse(ArtworkAspect.SCREEN.accepts(2100, 900, screen))
        // 1500 x 1000 retains 84.375% on a 16:9 screen; 1520 x 1000 retains 85.5%.
        assertFalse(ArtworkAspect.SCREEN.accepts(1500, 1000, screen))
        assertTrue(ArtworkAspect.SCREEN.accepts(1520, 1000, screen))
        assertFalse(ArtworkAspect.SCREEN.accepts(2400, 1000, screen))
    }

    @Test fun screenModeRespectsRotationAndDifferentDeviceShapes() {
        assertTrue(ArtworkAspect.SCREEN.accepts(1080, 2400, 1080.0 / 2400.0))
        assertFalse(ArtworkAspect.SCREEN.accepts(2400, 1080, 1080.0 / 2400.0))
        assertTrue(ArtworkAspect.SCREEN.accepts(2400, 1080, 2400.0 / 1080.0))
        assertTrue(ArtworkAspect.SCREEN.accepts(1600, 1200, 4.0 / 3.0))
        assertFalse(ArtworkAspect.SCREEN.accepts(1600, 1200, screen))
    }

    @Test fun invalidDimensionsAndScreenRatiosAreRejected() {
        for (aspect in ArtworkAspect.entries) {
            assertFalse(aspect.accepts(0, 1080, screen))
            assertFalse(aspect.accepts(1920, -1, screen))
        }
        for (target in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(ArtworkAspect.SCREEN.accepts(1920, 1080, target))
        }
    }

    @Test fun policyAppliesRatioAlongsideTagsAndRating() {
        val post = ArtworkPost(ArtworkSource.DANBOORU, "1", "hash", ArtworkRating.GENERAL, setOf("bara"),
            "https://cdn.donmai.us/art.jpg", "https://danbooru.donmai.us/posts/1", "artist", 1200, 1200)
        val query = TagQuery.parse("bara")
        assertFalse(ArtworkPolicy.accepts(post, query, ArtworkRating.GENERAL, ArtworkAspect.LANDSCAPE, screen))
        assertTrue(ArtworkPolicy.accepts(post, query, ArtworkRating.GENERAL, ArtworkAspect.ANY, screen))
        assertFalse(ArtworkPolicy.accepts(post.copy(rating = ArtworkRating.EXPLICIT), query,
            ArtworkRating.GENERAL, ArtworkAspect.ANY, screen))
    }
}
