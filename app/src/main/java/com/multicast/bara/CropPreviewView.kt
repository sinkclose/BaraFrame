package com.multicast.bara

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import com.google.android.material.color.MaterialColors
import android.view.View

class CropPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    var crop = CropState()
        private set
    var onCropChanged: ((CropState) -> Unit)? = null
    private val frame = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MaterialColors.getColor(this@CropPreviewView, com.google.android.material.R.attr.colorOnSurface); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density }
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val image = bitmap ?: return false
            if (frame.width() <= 0 || frame.height() <= 0) return false
            change(CropMath.zoom(image.width, image.height, crop, detector.scaleFactor.toDouble(),
                ((detector.focusX - frame.left) / frame.width()).toDouble(),
                ((detector.focusY - frame.top) / frame.height()).toDouble()))
            return true
        }
    })

    init { isClickable = true; isFocusable = true }

    fun setImage(image: Bitmap?, state: CropState) {
        val previous = bitmap
        bitmap = image
        crop = state.safe()
        if (previous !== image) previous?.recycle()
        invalidate()
    }

    fun setCrop(state: CropState) { crop = state.safe(); invalidate() }

    fun reset() = change(CropState(ratio = crop.ratio))

    fun zoomBy(factor: Double) {
        val image = bitmap ?: return
        change(CropMath.zoom(image.width, image.height, crop, factor))
    }

    private fun change(state: CropState) {
        crop = state
        invalidate()
        onCropChanged?.invoke(state)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val pad = 12 * resources.displayMetrics.density
        val availableWidth = (width - pad * 2).coerceAtLeast(1f)
        val availableHeight = (height - pad * 2).coerceAtLeast(1f)
        val w = minOf(availableWidth, (availableHeight * crop.ratio).toFloat())
        val h = (w / crop.ratio).toFloat()
        frame.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        val rect = CropMath.rect(image.width, image.height, crop)
        val matrix = Matrix().apply { setRectToRect(RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()), frame, Matrix.ScaleToFit.FILL) }
        canvas.save()
        canvas.clipRect(frame)
        canvas.drawBitmap(image, matrix, paint)
        if (dragging) {
            border.alpha = 90
            for (third in 1..2) {
                val x = frame.left + frame.width() * third / 3
                val y = frame.top + frame.height() * third / 3
                canvas.drawLine(x, frame.top, x, frame.bottom, border)
                canvas.drawLine(frame.left, y, frame.right, y, border)
            }
        }
        canvas.restore()
        border.alpha = 180
        canvas.drawRect(frame, border)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val image = bitmap ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!frame.contains(event.x, event.y)) return false
            dragging = true
            parent.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && event.pointerCount == 1 && frame.width() > 0 && frame.height() > 0) {
                change(CropMath.pan(image.width, image.height, crop,
                    ((event.x - lastX) / frame.width()).toDouble(), ((event.y - lastY) / frame.height()).toDouble()))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                invalidate()
            }
        }
        lastX = event.x
        lastY = event.y
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val image = bitmap ?: return super.onKeyDown(keyCode, event)
        if (!isEnabled) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> change(CropMath.pan(image.width, image.height, crop, -0.05, 0.0))
            KeyEvent.KEYCODE_DPAD_RIGHT -> change(CropMath.pan(image.width, image.height, crop, 0.05, 0.0))
            KeyEvent.KEYCODE_DPAD_UP -> change(CropMath.pan(image.width, image.height, crop, 0.0, -0.05))
            KeyEvent.KEYCODE_DPAD_DOWN -> change(CropMath.pan(image.width, image.height, crop, 0.0, 0.05))
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> zoomBy(1.25)
            KeyEvent.KEYCODE_MINUS -> zoomBy(0.8)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
