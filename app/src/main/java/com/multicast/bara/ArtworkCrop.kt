package com.multicast.bara

enum class ArtworkFrame {
    SCREEN, LANDSCAPE, PORTRAIT, TABLET_LANDSCAPE, TABLET_PORTRAIT, ORIGINAL;

    fun ratio(screen: Double, original: Double): Double = when (this) {
        SCREEN -> screen
        LANDSCAPE -> 16.0 / 9.0
        PORTRAIT -> 9.0 / 16.0
        TABLET_LANDSCAPE -> 4.0 / 3.0
        TABLET_PORTRAIT -> 3.0 / 4.0
        ORIGINAL -> original
    }
}

data class CropState(val x: Double = 0.5, val y: Double = 0.5, val zoom: Double = 1.0, val ratio: Double = 16.0 / 9.0) {
    fun safe() = CropState(
        x.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.5,
        y.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.5,
        zoom.takeIf(Double::isFinite)?.coerceIn(1.0, 4.0) ?: 1.0,
        ratio.takeIf(Double::isFinite)?.coerceIn(0.25, 4.0) ?: 16.0 / 9.0
    )
}

data class CropRect(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
}

object CropMath {
    fun rect(width: Int, height: Int, crop: CropState): CropRect {
        require(width > 0 && height > 0)
        val state = crop.safe()
        val w = minOf(width.toDouble(), height * state.ratio) / state.zoom
        val h = w / state.ratio
        return CropRect((state.x * width - w / 2).coerceIn(0.0, (width - w).coerceAtLeast(0.0)),
            (state.y * height - h / 2).coerceIn(0.0, (height - h).coerceAtLeast(0.0)), w, h)
    }

    fun pan(width: Int, height: Int, crop: CropState, dx: Double, dy: Double): CropState {
        val r = rect(width, height, crop)
        return normalize(width, height, crop.safe().copy(
            x = (r.left + r.width / 2 - dx * r.width) / width,
            y = (r.top + r.height / 2 - dy * r.height) / height))
    }

    fun zoom(width: Int, height: Int, crop: CropState, factor: Double, focusX: Double = 0.5, focusY: Double = 0.5): CropState {
        val before = rect(width, height, crop)
        val state = crop.safe().copy(zoom = (crop.safe().zoom * factor).coerceIn(1.0, 4.0))
        val after = rect(width, height, state)
        val fx = focusX.coerceIn(0.0, 1.0)
        val fy = focusY.coerceIn(0.0, 1.0)
        return normalize(width, height, state.copy(
            x = (before.left + fx * before.width + (0.5 - fx) * after.width) / width,
            y = (before.top + fy * before.height + (0.5 - fy) * after.height) / height))
    }

    fun normalize(width: Int, height: Int, crop: CropState): CropState {
        val r = rect(width, height, crop)
        return crop.safe().copy(x = (r.left + r.width / 2) / width, y = (r.top + r.height / 2) / height)
    }
}
