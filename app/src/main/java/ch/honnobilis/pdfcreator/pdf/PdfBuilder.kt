package ch.honnobilis.pdfcreator.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import ch.honnobilis.pdfcreator.imaging.BitmapIO
import ch.honnobilis.pdfcreator.imaging.ImageProcessor
import ch.honnobilis.pdfcreator.model.ImagePage
import ch.honnobilis.pdfcreator.model.PageItem
import ch.honnobilis.pdfcreator.model.PdfPage
import ch.honnobilis.pdfcreator.model.PdfPageKind
import ch.honnobilis.pdfcreator.model.SourcePdf
import ch.honnobilis.pdfcreator.ocr.OcrEngine
import ch.honnobilis.pdfcreator.ocr.OcrWord
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Output quality presets for photo pages, picked with the slider. */
enum class Quality(val label: String, val maxSide: Int, val jpeg: Int, val approxKbPerPage: Int) {
    SMALL("Small", 1400, 55, 120),
    LOW("Low", 1800, 65, 200),
    MEDIUM("Medium", 2400, 75, 350),
    HIGH("High", 3000, 85, 650),
    MAXIMUM("Maximum", 4000, 92, 1400),
}

class PdfBuilder(private val context: Context) {

    /** Long side of a photo page in PDF points (= A4 long side), short side follows the photo. */
    private val pageLongSide = 842f

    /**
     * Writes [pages] into [out]. When editing, [source] supplies the original PDF so
     * untouched pages are copied over unchanged.
     */
    suspend fun build(
        pages: List<PageItem>,
        source: SourcePdf?,
        quality: Quality,
        ocr: Boolean,
        out: File,
        progress: (done: Int, total: Int, what: String) -> Unit,
    ) {
        val total = pages.size
        var ocrEngine: OcrEngine? = null
        var srcDoc: PDDocument? = null
        var renderer: PdfRenderer? = null
        var fd: ParcelFileDescriptor? = null
        try {
            PDDocument().use { doc ->
                if (source != null) {
                    srcDoc = PdfLoader.open(source.cacheFile)
                }
                pages.forEachIndexed { i, page ->
                    coroutineContext.ensureActive()
                    val needsOcr = ocr && when (page) {
                        is ImagePage -> true
                        is PdfPage -> page.kind == PdfPageKind.SCANNED && !page.hasOcr
                    }
                    progress(i, total, if (needsOcr) "Page ${i + 1} of $total – recognising text…" else "Page ${i + 1} of $total")
                    if (needsOcr && ocrEngine == null) ocrEngine = OcrEngine(context)
                    when (page) {
                        is ImagePage -> addImagePage(doc, page, quality, if (needsOcr) ocrEngine else null)
                        is PdfPage -> {
                            val sd = srcDoc ?: error("Source PDF missing")
                            if (needsOcr && renderer == null) {
                                fd = ParcelFileDescriptor.open(source!!.cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                                renderer = PdfRenderer(fd!!)
                            }
                            copyPdfPage(doc, sd, page, if (needsOcr) ocrEngine to renderer else null)
                        }
                    }
                }
                progress(total, total, "Writing file…")
                doc.save(out)
            }
        } finally {
            ocrEngine?.close()
            renderer?.close()
            fd?.close()
            srcDoc?.close()
        }
    }

    private fun addImagePage(doc: PDDocument, page: ImagePage, quality: Quality, ocr: OcrEngine?) {
        val src = BitmapIO.decode(page.sourceFile, 4000)
        val bmp = ImageProcessor.render(src, page.quad, page.rotation, page.effectiveFilter, quality.maxSide)
        if (bmp !== src) src.recycle()
        val words = ocr?.recognize(bmp).orEmpty()

        val bytes = ByteArrayOutputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, it)
            it.toByteArray()
        }
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        bmp.recycle()

        val image = JPEGFactory.createFromByteArray(doc, bytes)
        val scale = pageLongSide / max(w, h)
        val pw = w * scale
        val ph = h * scale
        val pdPage = PDPage(PDRectangle(pw, ph))
        doc.addPage(pdPage)
        PDPageContentStream(doc, pdPage).use { cs ->
            cs.drawImage(image, 0f, 0f, pw, ph)
            TextLayer.write(cs, words, Matrix(pw / w, 0f, 0f, -ph / h, 0f, ph))
        }
    }

    private fun copyPdfPage(
        doc: PDDocument,
        srcDoc: PDDocument,
        page: PdfPage,
        ocr: Pair<OcrEngine?, PdfRenderer?>?,
    ) {
        val srcPage = srcDoc.getPage(page.index)
        val originalRotation = srcPage.rotation
        val imported = doc.importPage(srcPage)
        imported.resources = srcPage.resources

        val engine = ocr?.first
        val renderer = ocr?.second
        if (engine != null && renderer != null) {
            val bmp = renderForOcr(renderer, page.index, page.nativeScale)
            val words = engine.recognize(bmp)
            val box = imported.cropBox
            val toUser = TextLayer.renderedPageToUser(
                box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY,
                originalRotation, bmp.width, bmp.height,
            )
            bmp.recycle()
            PDPageContentStream(doc, imported, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                TextLayer.write(cs, words, toUser)
            }
        }
        imported.rotation = (originalRotation + page.extraRotation) % 360
    }

    private fun renderForOcr(renderer: PdfRenderer, index: Int, nativeScale: Float): Bitmap =
        renderPage(renderer, index, nativeScale.coerceIn(2f, 4.2f), 3500)

    companion object {
        /** Renders a page (white background, /Rotate applied) at [scale] pixels per point. */
        fun renderPage(renderer: PdfRenderer, index: Int, scale: Float, maxSide: Int): Bitmap {
            renderer.openPage(index).use { p ->
                val longPts = max(p.width, p.height).coerceAtLeast(1)
                val s = minOf(scale, maxSide.toFloat() / longPts)
                val w = (p.width * s).roundToInt().coerceAtLeast(1)
                val h = (p.height * s).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }
}

object PdfLoader {
    /** Opens a PDF for reading/copying. Owner-only encryption is removed; password-protected files fail. */
    fun open(file: File): PDDocument {
        val doc = PDDocument.load(file)
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }
}

/** Result of analysing an existing PDF page. */
data class PageAnalysis(val kind: PdfPageKind, val hasOcr: Boolean, val nativeScale: Float)

object PdfAnalyzer {
    fun analyze(doc: PDDocument, index: Int): PageAnalysis {
        val info = runCatching { PageInspector(doc.getPage(index)).run() }.getOrNull()
            ?: return PageAnalysis(PdfPageKind.DIGITAL, false, 2f)
        val scanned = info.visibleChars < 20 &&
            info.largestImageCoverage >= 0.6f &&
            info.vectorOps < 40
        return PageAnalysis(
            kind = if (scanned) PdfPageKind.SCANNED else PdfPageKind.DIGITAL,
            hasOcr = scanned && info.invisibleChars > 0,
            nativeScale = if (info.largestImageScale > 0f) info.largestImageScale else 2f,
        )
    }
}
