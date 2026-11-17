package ch.honnobilis.pdfcreator.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import ch.honnobilis.pdfcreator.model.StoredPdf
import java.io.File

/**
 * PDFs live in the public "Documents/PDF Creator" folder (via MediaStore, no permission
 * needed). They stay there even if the app is uninstalled. The history is simply the
 * list of PDFs in that folder that this app created.
 */
class PdfStore(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun list(): List<StoredPdf> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val result = mutableListOf<StoredPdf>()
        resolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
            arrayOf(FOLDER, MIME),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                result += StoredPdf(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                    name = c.getString(name) ?: "Untitled.pdf",
                    sizeBytes = c.getLong(size),
                    modifiedMillis = c.getLong(modified) * 1000,
                )
            }
        }
        return result
    }

    /** Copies [file] into the folder as a new PDF. Returns its uri. */
    fun saveNew(file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ensurePdfExtension(name))
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create the file")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Replaces the contents of an existing document. */
    fun overwrite(uri: Uri, file: File) {
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("Cannot write to the original file")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    fun delete(uri: Uri): Boolean = resolver.delete(uri, null, null) > 0

    /** Renames a PDF in the folder. Returns the name it actually got. */
    fun rename(uri: Uri, newName: String): String {
        val name = ensurePdfExtension(newName)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
        check(resolver.update(uri, values, null, null) > 0) { "Could not rename the file" }
        return displayName(uri) ?: name
    }

    fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun canWrite(uri: Uri): Boolean = runCatching {
        resolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    }.getOrDefault(false)

    companion object {
        const val MIME = "application/pdf"
        val FOLDER = Environment.DIRECTORY_DOCUMENTS + "/PDF Creator/"

        fun ensurePdfExtension(name: String): String {
            val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Document" }
            return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
        }
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var qualityIndex: Int
        get() = sp.getInt("quality", 2)
        set(v) = sp.edit().putInt("quality", v).apply()

    var ocr: Boolean
        get() = sp.getBoolean("ocr", false)
        set(v) = sp.edit().putBoolean("ocr", v).apply()
}
