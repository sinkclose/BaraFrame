package com.multicast.bara

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ArtworkCropTest {
    @Test fun centerCropProducesTheRequestedFrameWithoutStretching() {
        val r = CropMath.rect(1600, 1200, CropState(ratio = 16.0 / 9.0))
        assertEquals(0.0, r.left, 1e-8)
        assertEquals(150.0, r.top, 1e-8)
        assertEquals(1600.0, r.width, 1e-8)
        assertEquals(900.0, r.height, 1e-8)
    }

    @Test fun movingImageRightExposesPixelsToTheLeft() {
        val initial = CropState(zoom = 2.0, ratio = 16.0 / 9.0)
        val moved = CropMath.pan(1600, 900, initial, 0.25, -0.25)
        val r = CropMath.rect(1600, 900, moved)
        assertEquals(200.0, r.left, 1e-8)
        assertEquals(337.5, r.top, 1e-8)
    }

    @Test fun draggingCannotRevealEmptySpace() {
        for (direction in listOf(-100.0, 100.0)) {
            val crop = CropMath.pan(1600, 900, CropState(zoom = 3.0), direction, direction)
            val r = CropMath.rect(1600, 900, crop)
            assertTrue(r.left >= 0 && r.top >= 0)
            assertTrue(r.right <= 1600.000001 && r.bottom <= 900.000001)
        }
    }

    @Test fun pinchKeepsThePointUnderTheFingersInPlace() {
        val initial = CropState(ratio = 16.0 / 9.0)
        val before = CropMath.rect(1600, 900, initial)
        val after = CropMath.rect(1600, 900, CropMath.zoom(1600, 900, initial, 2.0, 0.25, 0.25))
        assertEquals(before.left + before.width * 0.25, after.left + after.width * 0.25, 1e-8)
        assertEquals(before.top + before.height * 0.25, after.top + after.height * 0.25, 1e-8)
    }

    @Test fun zoomLimitsAndInvalidSavedValuesAreSafe() {
        assertEquals(4.0, CropMath.zoom(1600, 900, CropState(), 100.0).zoom, 1e-8)
        assertEquals(1.0, CropMath.zoom(1600, 900, CropState(), 0.01).zoom, 1e-8)
        val normalized = CropState(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN, -1.0).safe()
        assertEquals(0.5, normalized.x, 1e-8)
        assertEquals(0.5, normalized.y, 1e-8)
        assertEquals(1.0, normalized.zoom, 1e-8)
        assertTrue(normalized.ratio > 0)
    }

    @Test fun cropRemainsValidAcrossDifferentImageSizesAndFrames() {
        val random = Random(42)
        repeat(500) {
            val width = random.nextInt(600, 4001)
            val height = random.nextInt(600, 4001)
            val crop = CropState(random.nextDouble(-1.0, 2.0), random.nextDouble(-1.0, 2.0),
                random.nextDouble(0.2, 8.0), random.nextDouble(0.3, 3.5))
            val r = CropMath.rect(width, height, crop)
            assertTrue(r.left >= 0 && r.top >= 0)
            assertTrue(r.right <= width + 1e-6 && r.bottom <= height + 1e-6)
            assertEquals(crop.safe().ratio, r.width / r.height, 1e-8)
            val normalized = CropMath.normalize(width, height, crop)
            assertEquals(r.left, CropMath.rect(width, height, normalized).left, 1e-6)
        }
    }

    @Test fun framePresetsIncludeLandscapePortraitAndOriginal() {
        assertEquals(16.0 / 9.0, ArtworkFrame.LANDSCAPE.ratio(1.0, 1.0), 1e-8)
        assertEquals(9.0 / 16.0, ArtworkFrame.PORTRAIT.ratio(1.0, 1.0), 1e-8)
        assertEquals(4.0 / 3.0, ArtworkFrame.TABLET_LANDSCAPE.ratio(1.0, 1.0), 1e-8)
        assertEquals(3.0 / 4.0, ArtworkFrame.TABLET_PORTRAIT.ratio(1.0, 1.0), 1e-8)
        assertEquals(2.0, ArtworkFrame.ORIGINAL.ratio(1.0, 2.0), 1e-8)
        assertEquals(1.6, ArtworkFrame.SCREEN.ratio(1.6, 2.0), 1e-8)
    }
}
