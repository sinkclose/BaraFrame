package com.multicast.bara

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Saved URIs keep resolving to the latest publication, even after older files are pruned. */
class ArtworkContentProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val app = context ?: throw FileNotFoundException("Provider unavailable")
        if (uri.scheme != "content" || uri.authority != "${app.packageName}.artwork" || !ArtworkUris.supportedPath(uri.path.orEmpty())) {
            throw FileNotFoundException("Unknown artwork URI")
        }
        val store = ArtworkStore(app)
        val artwork = store.current() ?: throw FileNotFoundException("No eligible background")
        return File(store.directory, artwork.displayFileName)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = synchronized(ArtworkStore.publicationLock) {
        if (mode != "r") throw FileNotFoundException("Read-only provider")
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        file(uri)
        return "image/jpeg"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = synchronized(ArtworkStore.publicationLock) {
        val image = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> "BaraFrame.jpg"
                OpenableColumns.SIZE -> image.length()
                else -> null
            } }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
}

object ArtworkUris {
    const val CURRENT_PATH = "/background/current.jpg"
    fun supportedPath(path: String) = path == CURRENT_PATH || path.matches(Regex("/artwork/[a-f0-9-]{36}\\.jpg"))
}
