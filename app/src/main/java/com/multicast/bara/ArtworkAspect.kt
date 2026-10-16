package com.multicast.bara

/** Ratio checks use dimensions, so both sources follow the same rules. */
enum class ArtworkAspect {
    SCREEN, LANDSCAPE, PORTRAIT, ANY;

    fun accepts(width: Int, height: Int, screenRatio: Double): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = width.toDouble() / height
        val landscape = ratio in 4.0 / 3.0..21.0 / 9.0
        val portrait = ratio in 9.0 / 21.0..3.0 / 4.0
        return when (this) {
            ANY -> true
            LANDSCAPE -> landscape
            PORTRAIT -> portrait
            SCREEN -> (landscape || portrait) && screenRatio.isFinite() && screenRatio > 0 &&
                minOf(ratio / screenRatio, screenRatio / ratio) >= 0.85
        }
    }
}

object AspectTarget {
    fun resolve(saved: Double?, legacy: Double?, device: Double): Double =
        listOf(saved, legacy, device).firstOrNull { it != null && it.isFinite() && it in 0.25..4.0 } ?: 16.0 / 9.0
}
