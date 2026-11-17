package ch.honnobilis.pdfcreator.pdf

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlin.math.max
import kotlin.math.min

/**
 * Walks a page's content stream and collects what is needed to tell a scanned page
 * (one big image, maybe an invisible OCR layer) from a "real" digital page.
 */
class PageInspector(private val page: PDPage) : PDFGraphicsStreamEngine(page) {

    var visibleChars = 0; private set
    var invisibleChars = 0; private set
    var vectorOps = 0; private set

    /** Fraction of the page covered by the largest image (0..1). */
    var largestImageCoverage = 0f; private set

    /** Pixel width of the largest image divided by its drawn width in points. */
    var largestImageScale = 0f; private set

    private val box = page.cropBox
    private var current = PointF(0f, 0f)

    fun run(): PageInspector {
        processPage(page)
        return this
    }

    override fun drawImage(pdImage: PDImage) {
        val ctm = graphicsState.currentTransformationMatrix
        val corners = listOf(
            ctm.transformPoint(0f, 0f), ctm.transformPoint(1f, 0f),
            ctm.transformPoint(1f, 1f), ctm.transformPoint(0f, 1f),
        )
        val x0 = max(corners.minOf { it.x }, box.lowerLeftX)
        val x1 = min(corners.maxOf { it.x }, box.upperRightX)
        val y0 = max(corners.minOf { it.y }, box.lowerLeftY)
        val y1 = min(corners.maxOf { it.y }, box.upperRightY)
        val area = max(0f, x1 - x0) * max(0f, y1 - y0)
        val pageArea = box.width * box.height
        if (pageArea <= 0f) return
        val coverage = area / pageArea
        if (coverage > largestImageCoverage) {
            largestImageCoverage = coverage
            val drawnWidth = max(corners.maxOf { it.x } - corners.minOf { it.x }, 1f)
            val drawnHeight = max(corners.maxOf { it.y } - corners.minOf { it.y }, 1f)
            // The image may be rotated by the CTM; use the larger ratio.
            largestImageScale = max(
                max(pdImage.width, pdImage.height).toFloat() / max(drawnWidth, drawnHeight),
                min(pdImage.width, pdImage.height).toFloat() / min(drawnWidth, drawnHeight),
            )
        }
    }

    override fun showText(string: ByteArray) {
        if (graphicsState.textState.renderingMode == RenderingMode.NEITHER) {
            invisibleChars += string.size
        } else {
            visibleChars += string.size
        }
    }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) { vectorOps++ }
    override fun clip(windingRule: Path.FillType) {}
    override fun moveTo(x: Float, y: Float) { current = PointF(x, y) }
    override fun lineTo(x: Float, y: Float) { current = PointF(x, y) }
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { current = PointF(x3, y3) }
    override fun getCurrentPoint(): PointF = current
    override fun closePath() {}
    override fun endPath() {}
    override fun strokePath() { vectorOps++ }
    override fun fillPath(windingRule: Path.FillType) { vectorOps++ }
    override fun fillAndStrokePath(windingRule: Path.FillType) { vectorOps++ }
    override fun shadingFill(shadingName: COSName) { vectorOps++ }
}
