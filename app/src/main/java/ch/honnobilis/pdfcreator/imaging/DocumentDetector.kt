package ch.honnobilis.pdfcreator.imaging

import android.graphics.Bitmap
import ch.honnobilis.pdfcreator.model.Pt
import ch.honnobilis.pdfcreator.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the outline of a sheet of paper in a photo.
 *
 * Two strategies run on a downscaled copy: edge detection (Canny) and brightness
 * segmentation (Otsu). The largest convex quadrilateral that either finds wins.
 * If nothing plausible is found, the whole image is used.
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
        if (scale < 1.0) {
            Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        } else {
            rgba.copyTo(small)
        }
        rgba.release()

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val w = small.cols().toDouble()
        val h = small.rows().toDouble()
        small.release()
        val imageArea = w * h

        val candidates = mutableListOf<List<Point>>()

        // Strategy 1: edges.
        val edges = Mat()
        Imgproc.Canny(gray, edges, 30.0, 100.0)
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, k3, Point(-1.0, -1.0), 2)
        candidates += findQuads(edges, imageArea)
        edges.release()

        // Strategy 2: bright paper on darker background.
        val th = Mat()
        Imgproc.threshold(gray, th, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(th, th, Imgproc.MORPH_CLOSE, k9)
        candidates += findQuads(th, imageArea)
        th.release()
        gray.release()

        val best = candidates.maxByOrNull { polygonArea(it) } ?: return Quad.FULL
        val o = orderCorners(best)
        fun n(p: Point) = Pt((p.x / w).toFloat().coerceIn(0f, 1f), (p.y / h).toFloat().coerceIn(0f, 1f))
        return Quad(n(o[0]), n(o[1]), n(o[2]), n(o[3]))
    }

    private fun findQuads(binary: Mat, imageArea: Double): List<List<Point>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val result = mutableListOf<List<Point>>()
        val big = contours
            .map { it to Imgproc.contourArea(it) }
            .filter { it.second > imageArea * MIN_AREA }
            .sortedByDescending { it.second }
            .take(8)

        for ((contour, _) in big) {
            val quad = approxQuad(contour) ?: approxQuad(hull(contour))
            if (quad != null) {
                val a = polygonArea(quad)
                if (a > imageArea * MIN_AREA && a < imageArea * MAX_AREA) result += quad
            }
        }
        contours.forEach { it.release() }
        return result
    }

    private fun approxQuad(contour: MatOfPoint): List<Point>? {
        val c2f = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(c2f, true)
        for (eps in doubleArrayOf(0.02, 0.03, 0.045, 0.06, 0.08)) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
            val pts = approx.toArray()
            approx.release()
            if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                c2f.release()
                return pts.toList()
            }
            if (pts.size < 4) break
        }
        c2f.release()
        return null
    }

    private fun hull(contour: MatOfPoint): MatOfPoint {
        val idx = MatOfInt()
        Imgproc.convexHull(contour, idx)
        val pts = contour.toArray()
        val hullPts = idx.toArray().map { pts[it] }
        idx.release()
        return MatOfPoint(*hullPts.toTypedArray())
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
            // Degenerate (e.g. strongly rotated square): sort by angle around the centre.
            val cx = p.sumOf { it.x } / 4
            val cy = p.sumOf { it.y } / 4
            val sorted = p.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) } // starts at ~-180°, i.e. left
            val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y }!!
            List(4) { sorted[(start + it) % 4] }
        }
    }
}
