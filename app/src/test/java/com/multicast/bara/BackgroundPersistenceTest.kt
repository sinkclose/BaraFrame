package com.multicast.bara

import org.junit.Assert.*
import org.junit.Test

class BackgroundPersistenceTest {
    @Test fun goingHomeCannotChangeTheSavedLandscapeTarget() {
        val landscape = 16.0 / 9.0
        assertEquals(landscape, AspectTarget.resolve(landscape, null, 9.0 / 16.0), 1e-8)
        assertEquals(landscape, AspectTarget.resolve(landscape, null, landscape), 1e-8)
        assertTrue(ArtworkAspect.SCREEN.accepts(1920, 1080, AspectTarget.resolve(landscape, null, 9.0 / 16.0)))
    }

    @Test fun upgradingAnOldCacheUsesItsShapeBeforeHomesOrientation() {
        assertEquals(1.6, AspectTarget.resolve(null, 1.6, 9.0 / 16.0), 1e-8)
    }

    @Test fun newInstallUsesDeviceShapeAndCorruptValuesAreIgnored() {
        assertEquals(9.0 / 16.0, AspectTarget.resolve(null, null, 9.0 / 16.0), 1e-8)
        assertEquals(1.6, AspectTarget.resolve(Double.NaN, 1.6, 0.0), 1e-8)
        assertEquals(16.0 / 9.0, AspectTarget.resolve(Double.NaN, -1.0, Double.POSITIVE_INFINITY), 1e-8)
    }

    @Test fun currentAndLegacyUrisAreSupportedWithoutExposingArbitraryFiles() {
        assertTrue(ArtworkUris.supportedPath("/background/current.jpg"))
        assertTrue(ArtworkUris.supportedPath("/artwork/512188bc-31de-4c09-9070-90004ca3f485.jpg"))
        listOf("/", "/background/", "/background/other.jpg", "/artwork/../../shared_prefs/artwork.xml",
            "/artwork/secret.jpg", "/background/current.jpg/../secret", "/artwork/%2E%2E/secret")
            .forEach { assertFalse(it, ArtworkUris.supportedPath(it)) }
    }
}
