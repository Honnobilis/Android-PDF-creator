package ch.honnobilis.pdfcreator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File

/** A recognised word with its bounding box in the pixel coordinates of the OCR'd bitmap. */
data class OcrWord(val text: String, val box: Rect)

/**
 * Offline OCR with Tesseract (German + English, LSTM "fast" models shipped in assets).
 * Not thread-safe: use one instance per worker and close it when done.
 */
class OcrEngine(context: Context) : Closeable {

    private val api = TessBaseAPI()

    init {
        val dataDir = installLanguageData(context)
        check(api.init(dataDir.absolutePath, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) {
            "Could not start the OCR engine"
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
    }

    fun recognize(bitmap: Bitmap): List<OcrWord> {
        api.setImage(bitmap)
        api.getUTF8Text() // runs recognition
        val words = mutableListOf<OcrWord>()
        val it = api.getResultIterator()
        if (it != null) {
            try {
                it.begin()
                do {
                    val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                    val box = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (!text.isNullOrEmpty() && box != null && box.width() > 0 && box.height() > 0) {
                        words += OcrWord(text, Rect(box))
                    }
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            } finally {
                it.delete()
            }
        }
        api.clear()
        return words
    }

    override fun close() {
        api.recycle()
    }

    companion object {
        private const val LANGUAGES = "deu+eng"

        /** Copies the traineddata files from assets once; Tesseract needs real files. */
        @Synchronized
        fun installLanguageData(context: Context): File {
            val root = File(context.filesDir, "ocr")
            val tessdata = File(root, "tessdata").apply { mkdirs() }
            val assets = context.assets
            for (name in assets.list("tessdata").orEmpty()) {
                val target = File(tessdata, name)
                val expected = assets.openFd("tessdata/$name").use { it.length }
                if (target.length() != expected) {
                    val tmp = File(tessdata, "$name.tmp")
                    assets.open("tessdata/$name").use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp.renameTo(target)
                }
            }
            return root
        }
    }
}
