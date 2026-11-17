package ch.honnobilis.pdfcreator.pdf

import ch.honnobilis.pdfcreator.ocr.OcrWord
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix

/**
 * Writes OCR results as invisible text (render mode 3) so the PDF becomes
 * searchable and selectable while it still looks like the scan.
 */
object TextLayer {

    private val font = PDType1Font.HELVETICA
    private val encodable = HashMap<Char, Boolean>()

    /**
     * @param toUser maps OCR pixel coordinates (origin top-left, y down) to PDF user space.
     */
    fun write(cs: PDPageContentStream, words: List<OcrWord>, toUser: Matrix) {
        if (words.isEmpty()) return
        cs.saveGraphicsState()
        cs.transform(toUser)
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        for (w in words) {
            val text = sanitize(w.text)
            if (text.isBlank()) continue
            val h = w.box.height().toFloat()
            val natural = font.getStringWidth(text) / 1000f * h
            if (h <= 0f || natural <= 0f) continue
            val sx = w.box.width() / natural
            cs.setFont(font, h)
            // d = -1 flips the glyphs upright again, because pixel space has y pointing down.
            cs.setTextMatrix(Matrix(sx, 0f, 0f, -1f, w.box.left.toFloat(), w.box.bottom - h * 0.18f))
            cs.showText(text)
        }
        cs.endText()
        cs.restoreGraphicsState()
    }

    /** Helvetica/WinAnsi covers German and English; anything else is dropped. */
    private fun sanitize(s: String): String = buildString {
        for (c in s) {
            val ok = synchronized(encodable) {
                encodable.getOrPut(c) { runCatching { font.encode(c.toString()) }.isSuccess }
            }
            if (ok) append(c)
        }
    }

    /**
     * Mapping from pixels of a page bitmap rendered by PdfRenderer (which honours /Rotate)
     * to the page's user space.
     */
    fun renderedPageToUser(
        llx: Float, lly: Float, urx: Float, ury: Float,
        rotation: Int, pixelWidth: Int, pixelHeight: Int,
    ): Matrix {
        val cw = urx - llx
        val ch = ury - lly
        val w = pixelWidth.toFloat()
        val h = pixelHeight.toFloat()
        return when (((rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, ch / w, cw / h, 0f, llx, lly)
            180 -> Matrix(-cw / w, 0f, 0f, ch / h, urx, lly)
            270 -> Matrix(0f, -ch / w, -cw / h, 0f, urx, ury)
            else -> Matrix(cw / w, 0f, 0f, -ch / h, llx, ury)
        }
    }
}
