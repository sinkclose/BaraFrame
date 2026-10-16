package com.multicast.bara

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

object ArtworkRenderer {
    fun render(store: ArtworkStore, artwork: CachedArtwork, crop: CropState): CachedArtwork {
        val source = BitmapFactory.decodeFile(File(store.directory, artwork.fileName).path)
            ?: throw ArtworkException(ArtworkMessage.IMAGE_CORRUPT)
        val temporary = File(store.directory, "${UUID.randomUUID()}.tmp")
        try {
            val state = CropMath.normalize(source.width, source.height, crop)
            val rect = CropMath.rect(source.width, source.height, state)
            val width = rect.width.roundToInt().coerceIn(1, 2048)
            val height = rect.height.roundToInt().coerceIn(1, 2048)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val matrix = Matrix().apply { setRectToRect(RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()), RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.FILL) }
                Canvas(output).apply { drawColor(Color.rgb(21, 25, 34)); drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
                temporary.outputStream().use {
                    if (!output.compress(Bitmap.CompressFormat.JPEG, 94, it)) throw ArtworkException(ArtworkMessage.IMAGE_PROCESSING)
                }
            } finally { output.recycle() }
            val result = File(store.directory, "${UUID.randomUUID()}.jpg")
            if (!temporary.renameTo(result)) throw ArtworkException(ArtworkMessage.SAVE_FAILED)
            return artwork.copy(renderedFileName = result.name, crop = state, revision = UUID.randomUUID().toString())
        } finally { source.recycle(); temporary.delete() }
    }

    fun prune(store: ArtworkStore) = synchronized(ArtworkStore.publicationLock) {
        val current = store.current() ?: return@synchronized
        val protected = setOf(current.fileName, current.displayFileName)
        store.directory.listFiles()?.filter { it.name !in protected }
            ?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
    }
}
