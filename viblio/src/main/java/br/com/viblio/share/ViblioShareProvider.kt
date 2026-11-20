package br.com.viblio.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal, read-only ContentProvider serving strictly validated PNG files inside `cacheDir/wrapped/`.
 * Eliminates external dependencies while keeping strict filename & canonical path containment.
 */
class ViblioShareProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "br.com.viblio.share"
        private const val SUBDIR = "wrapped"
        private val SAFE_NAME = Regex("^[a-z0-9_-]{1,64}\\.png$")

        fun directory(context: Context): File = File(context.cacheDir, SUBDIR).apply { mkdirs() }

        fun uriFor(fileName: String): Uri {
            require(SAFE_NAME.matches(fileName)) { "Nome de arquivo inválido." }
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(fileName)
                .build()
        }

        fun pruneOld(context: Context, keepMax: Int = 6) {
            val dir = directory(context)
            val files = dir.listFiles()?.filter { it.isFile && SAFE_NAME.matches(it.name) }
                ?.sortedByDescending { it.lastModified() } ?: return
            files.drop(keepMax).forEach { runCatching { it.delete() } }
        }
    }

    override fun onCreate(): Boolean = true

    private fun resolveFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("Context unavailable")
        val segments = uri.pathSegments
        if (segments.size != 1) throw FileNotFoundException("Invalid path")
        val name = segments[0]
        if (!SAFE_NAME.matches(name)) throw FileNotFoundException("Invalid filename")
        val root = directory(ctx).canonicalFile
        val target = File(root, name).canonicalFile
        if (target.parentFile != root || !target.isFile) throw FileNotFoundException("Not found")
        return target
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only provider")
        val file = resolveFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolveFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
}
