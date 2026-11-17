package ch.honnobilis.pdfcreator.imaging

import android.graphics.Bitmap
import ch.honnobilis.pdfcreator.model.FilterMode
import ch.honnobilis.pdfcreator.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cropping, perspective correction and document filters. */
object ImageProcessor {

    /**
     * Full pipeline: perspective crop → downscale to [maxSide] → rotate → filter.
     * [filter] must already be resolved (not AUTO). Never recycles [source].
     */
    fun render(source: Bitmap, quad: Quad, rotation: Int, filter: FilterMode, maxSide: Int): Bitmap {
        var bmp = if (quad.isFull) BitmapIO.scaledCopy(source, maxSide) else BitmapIO.scaleToMax(warp(source, quad), maxSide)
        bmp = BitmapIO.rotate(bmp, rotation)
        return applyFilter(bmp, filter)
    }

    /** Perspective-corrects [source] to the quad. Returns a new bitmap. */
    fun warp(source: Bitmap, quad: Quad): Bitmap {
        val w = source.width.toDouble()
        val h = source.height.toDouble()
        val p = quad.points.map { Point(it.x * w, it.y * h) }
        val outW = max(dist(p[0], p[1]), dist(p[3], p[2])).roundToInt().coerceAtLeast(1)
        val outH = max(dist(p[0], p[3]), dist(p[1], p[2])).roundToInt().coerceAtLeast(1)

        val src = Mat()
        Utils.bitmapToMat(source, src)
        val from = MatOfPoint2f(*p.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val dst = Mat()
        Imgproc.warpPerspective(
            src, dst, m, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE,
        )
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        listOf(src, from, to, m, dst).forEach { it.release() }
        return out
    }

    /** Applies a resolved filter. May return the same instance (ORIGINAL); otherwise recycles the input. */
    fun applyFilter(bmp: Bitmap, mode: FilterMode): Bitmap {
        if (mode == FilterMode.ORIGINAL || mode == FilterMode.AUTO) return bmp
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val result = when (mode) {
            FilterMode.COLOR -> enhanceColor(rgba)
            FilterMode.GRAY -> {
                val n = normalizedGray(rgba)
                n.convertTo(n, -1, 1.1, -12.0)
                toRgba(n)
            }
            FilterMode.BW -> {
                val n = normalizedGray(rgba)
                val bw = Mat()
                val block = (min(n.cols(), n.rows()) / 24).coerceAtLeast(15) or 1
                Imgproc.adaptiveThreshold(
                    n, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 14.0,
                )
                n.release()
                toRgba(bw)
            }
            else -> error("unreachable")
        }
        rgba.release()
        val out = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, out)
        result.release()
        bmp.recycle()
        return out
    }

    /**
     * Picks a filter for a (cropped) page:
     * visible colour → Colour, otherwise lots of mid-tones (photos, shading) → Gray, else B&W.
     */
    fun suggestFilter(cropped: Bitmap): FilterMode {
        val small = BitmapIO.scaledCopy(cropped, 500)
        val rgba = Mat()
        Utils.bitmapToMat(small, rgba)
        small.recycle()

        // Colour check on the background-normalised image, so a warm or blue light cast
        // on white paper doesn't count as colour.
        val enhanced = enhanceColor(rgba)
        val rgb = Mat()
        Imgproc.cvtColor(enhanced, rgb, Imgproc.COLOR_RGBA2RGB)
        enhanced.release()
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgb.release()
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 90.0, 80.0), Scalar(180.0, 255.0, 255.0), mask)
        val total = (mask.rows() * mask.cols()).toDouble()
        val colorFraction = Core.countNonZero(mask) / total
        hsv.release()

        val gray = normalizedGray(rgba)
        rgba.release()
        Core.inRange(gray, Scalar(70.0), Scalar(200.0), mask)
        val midFraction = Core.countNonZero(mask) / total
        gray.release()
        mask.release()

        return when {
            colorFraction > 0.008 -> FilterMode.COLOR
            midFraction > 0.10 -> FilterMode.GRAY
            else -> FilterMode.BW
        }
    }

    private fun enhanceColor(rgba: Mat): Mat {
        val channels = ArrayList<Mat>()
        Core.split(rgba, channels)
        for (i in 0..2) {
            val n = normalizeBackground(channels[i])
            channels[i].release()
            channels[i] = n
        }
        val out = Mat()
        Core.merge(channels, out)
        channels.forEach { it.release() }
        out.convertTo(out, -1, 1.15, -20.0)
        return out
    }

    private fun normalizedGray(rgba: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val n = normalizeBackground(gray)
        gray.release()
        return n
    }

    /**
     * Divides a single channel by an estimate of the paper background (text removed
     * by dilation + median blur). Shadows and uneven light disappear, paper turns white.
     */
    private fun normalizeBackground(channel: Mat): Mat {
        val scale = min(1.0, 600.0 / max(channel.cols(), channel.rows()))
        val small = Mat()
        Imgproc.resize(channel, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(small, small, k, Point(-1.0, -1.0), 2)
        Imgproc.medianBlur(small, small, 15)
        val bg = Mat()
        Imgproc.resize(small, bg, channel.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        k.release()
        val out = Mat()
        Core.divide(channel, bg, out, 255.0)
        bg.release()
        return out
    }

    private fun toRgba(single: Mat): Mat {
        val out = Mat()
        Imgproc.cvtColor(single, out, Imgproc.COLOR_GRAY2RGBA)
        single.release()
        return out
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}
