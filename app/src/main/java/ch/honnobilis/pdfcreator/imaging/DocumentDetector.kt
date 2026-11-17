package ch.honnobilis.pdfcreator.imaging

import android.graphics.Bitmap
import ch.honnobilis.pdfcreator.model.Pt
import ch.honnobilis.pdfcreator.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds the outline of a sheet of paper in a photo.
 *
 * Several segmentations produce candidate quadrilaterals:
 *  - "whiteness" (bright and unsaturated) – paper vs. coloured surfaces such as wood
 *  - low saturation alone – robust against shadows on the paper
 *  - plain brightness
 *  - edges (Canny on grey, whiteness and saturation, thresholds adapted to the image)
 * Each candidate is scored by how much of its outline lies on real edges, weighted
 * by its size; the best one wins. If nothing plausible is found, the whole image is used.
 *
 * Tuned against synthetic "paper on wooden table" photos (light oak, pine, dark wood,
 * shadows, rotated page, page cut off by the frame).
 */
object DocumentDetector {

    private const val WORK_SIZE = 640.0
    private const val MIN_AREA = 0.12
    private const val MAX_AREA = 0.985

    fun detect(bitmap: Bitmap): Quad {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val scale = WORK_SIZE / max(rgba.cols(), rgba.rows())
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA) else rgba.copyTo(small)
        rgba.release()
        val rgb = Mat()
        Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        small.release()

        val w = rgb.cols()
        val h = rgb.rows()
        val imageArea = w.toDouble() * h

        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgb.release()
        val hsvChannels = ArrayList<Mat>()
        Core.split(hsv, hsvChannels)
        hsv.release()
        val sat = hsvChannels[1]
        val value = hsvChannels[2]
        hsvChannels[0].release()

        // whiteness = V * (1 - S/255): high for paper, low for coloured surfaces
        val whiteness = Mat()
        val invSat = Mat()
        Core.bitwise_not(sat, invSat)
        Core.multiply(value, invSat, whiteness, 1.0 / 255.0)
        invSat.release()
        value.release()
        Imgproc.GaussianBlur(whiteness, whiteness, Size(7.0, 7.0), 0.0)
        val satBlur = Mat()
        Imgproc.GaussianBlur(sat, satBlur, Size(5.0, 5.0), 0.0)
        sat.release()

        // Combined edge map, used for edge candidates and for scoring.
        val edges = Mat.zeros(h, w, CvType.CV_8UC1)
        for (ch in listOf(gray, whiteness, satBlur)) {
            val m = median(ch)
            val e = Mat()
            Imgproc.Canny(ch, e, max(10.0, 0.33 * m), max(30.0, 0.66 * m))
            Core.bitwise_or(edges, e, edges)
            e.release()
        }
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val edgesThin = Mat()
        Imgproc.dilate(edges, edgesThin, k3, Point(-1.0, -1.0), 1)

        val candidates = mutableListOf<List<Point>>()

        // 1: whiteness
        candidates += quadsFrom(segment(whiteness, inverse = false, close = 15, open = 7), imageArea, external = true)
        // 2: low saturation
        candidates += quadsFrom(segment(satBlur, inverse = true, close = 15, open = 7), imageArea, external = true)
        // 3: brightness
        candidates += quadsFrom(segment(gray, inverse = false, close = 9, open = 0), imageArea, external = true)
        // 4: edges
        val edgesThick = Mat()
        Imgproc.dilate(edges, edgesThick, k3, Point(-1.0, -1.0), 2)
        candidates += quadsFrom(edgesThick, imageArea, external = false)
        edgesThick.release()

        val edgeBytes = ByteArray(w * h)
        edgesThin.get(0, 0, edgeBytes)
        listOf(gray, whiteness, satBlur, edges, edgesThin, k3).forEach { it.release() }

        val best = candidates.maxByOrNull { q ->
            sqrt(polygonArea(q) / imageArea) * edgeSupport(q, edgeBytes, w, h).let { it * it }
        } ?: return Quad.FULL
        if (edgeSupport(best, edgeBytes, w, h) < 0.15) return Quad.FULL

        val o = orderCorners(best)
        fun n(p: Point) = Pt((p.x / w).toFloat().coerceIn(0f, 1f), (p.y / h).toFloat().coerceIn(0f, 1f))
        return Quad(n(o[0]), n(o[1]), n(o[2]), n(o[3]))
    }

    /** Otsu threshold + morphological clean-up. Returns a new binary Mat. */
    private fun segment(src: Mat, inverse: Boolean, close: Int, open: Int): Mat {
        val t = Mat()
        val type = (if (inverse) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY) + Imgproc.THRESH_OTSU
        Imgproc.threshold(src, t, 0.0, 255.0, type)
        if (close > 0) {
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(close.toDouble(), close.toDouble()))
            Imgproc.morphologyEx(t, t, Imgproc.MORPH_CLOSE, k)
            k.release()
        }
        if (open > 0) {
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(open.toDouble(), open.toDouble()))
            Imgproc.morphologyEx(t, t, Imgproc.MORPH_OPEN, k)
            k.release()
        }
        return t
    }

    private fun median(m: Mat): Double {
        val bytes = ByteArray((m.total() * m.channels()).toInt())
        m.get(0, 0, bytes)
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xFF]++
        var acc = 0
        val half = bytes.size / 2
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= half) return i.toDouble()
        }
        return 128.0
    }

    /** Releases [binary]. */
    private fun quadsFrom(binary: Mat, imageArea: Double, external: Boolean): List<List<Point>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            binary, contours, hierarchy,
            if (external) Imgproc.RETR_EXTERNAL else Imgproc.RETR_LIST,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )
        hierarchy.release()
        binary.release()

        val result = mutableListOf<List<Point>>()
        val big = contours
            .map { it to Imgproc.contourArea(it) }
            .filter { it.second > imageArea * MIN_AREA }
            .sortedByDescending { it.second }
            .take(8)
        for ((contour, _) in big) {
            val quad = approxQuad(contour, STRICT) ?: hull(contour).let { hc -> approxQuad(hc, LOOSE).also { hc.release() } }
            if (quad != null) {
                val a = polygonArea(quad)
                if (a > imageArea * MIN_AREA && a < imageArea * MAX_AREA && anglesOk(quad)) result += quad
            }
        }
        contours.forEach { it.release() }
        return result
    }

    private val STRICT = doubleArrayOf(0.02, 0.03, 0.045, 0.06, 0.08)
    private val LOOSE = doubleArrayOf(0.02, 0.03, 0.045, 0.06, 0.08, 0.1, 0.12)

    private fun approxQuad(contour: MatOfPoint, epsilons: DoubleArray): List<Point>? {
        val c2f = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(c2f, true)
        try {
            for (eps in epsilons) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                val pts = approx.toArray()
                approx.release()
                if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) return pts.toList()
                if (pts.size < 4) break
            }
            return null
        } finally {
            c2f.release()
        }
    }

    private fun hull(contour: MatOfPoint): MatOfPoint {
        val idx = MatOfInt()
        Imgproc.convexHull(contour, idx)
        val pts = contour.toArray()
        val hullPts = idx.toArray().map { pts[it] }
        idx.release()
        return MatOfPoint(*hullPts.toTypedArray())
    }

    /** Rejects shapes with corners far from 90° (outside roughly 53°–127°). */
    private fun anglesOk(q: List<Point>): Boolean {
        for (i in 0 until 4) {
            val a = q[(i + 3) % 4]
            val b = q[i]
            val c = q[(i + 1) % 4]
            val v1x = a.x - b.x
            val v1y = a.y - b.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val cos = abs(v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-9)
            if (cos > 0.6) return false
        }
        return true
    }

    /** Fraction of the outline that lies on edge pixels. */
    private fun edgeSupport(q: List<Point>, edges: ByteArray, w: Int, h: Int): Double {
        var n = 0
        var hit = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val len = max(2, hypot(b.x - a.x, b.y - a.y).toInt())
            for (s in 0 until len) {
                val t = s.toDouble() / (len - 1)
                val x = (a.x + (b.x - a.x) * t).toInt()
                val y = (a.y + (b.y - a.y) * t).toInt()
                if (x in 0 until w && y in 0 until h) {
                    n++
                    if (edges[y * w + x].toInt() != 0) hit++
                }
            }
        }
        return if (n == 0) 0.0 else hit.toDouble() / n
    }

    private fun polygonArea(p: List<Point>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    /** Orders four points as TL, TR, BR, BL. */
    fun orderCorners(p: List<Point>): List<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return if (setOf(tl, tr, br, bl).size == 4) {
            listOf(tl, tr, br, bl)
        } else {
            val cx = p.sumOf { it.x } / 4
            val cy = p.sumOf { it.y } / 4
            val sorted = p.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y }!!
            List(4) { sorted[(start + it) % 4] }
        }
    }
}
