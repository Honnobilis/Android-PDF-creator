package ch.honnobilis.pdfcreator.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapIO {

    /** Decodes an image, applying its EXIF orientation and limiting the long side to [maxSide]. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        return decode({ resolver.openInputStream(uri) ?: error("Cannot open $uri") }, maxSide, readExif = true)
    }

    /** Decodes a file written by this app (no EXIF orientation to honour). */
    fun decode(file: File, maxSide: Int): Bitmap =
        decode({ file.inputStream() }, maxSide, readExif = false)

    private fun decode(open: () -> InputStream, maxSide: Int, readExif: Boolean): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Cannot decode image")
        bmp = scaleToMax(bmp, maxSide)

        if (readExif) {
            val exif = runCatching { open().use { ExifInterface(it) } }.getOrNull()
            if (exif != null) {
                val rotation = exif.rotationDegrees
                val flipped = exif.isFlipped
                if (rotation != 0 || flipped) {
                    val m = Matrix()
                    if (flipped) m.postScale(-1f, 1f)
                    m.postRotate(rotation.toFloat())
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (rotated !== bmp) bmp.recycle()
                    bmp = rotated
                }
            }
        }
        return bmp
    }

    fun scaleToMax(bmp: Bitmap, maxSide: Int): Bitmap {
        val long = max(bmp.width, bmp.height)
        if (long <= maxSide) return bmp
        val f = maxSide.toFloat() / long
        val w = (bmp.width * f).roundToInt().coerceAtLeast(1)
        val h = (bmp.height * f).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** Like [scaleToMax] but never recycles the input. */
    fun scaledCopy(bmp: Bitmap, maxSide: Int): Bitmap {
        val long = max(bmp.width, bmp.height)
        if (long <= maxSide) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val f = maxSide.toFloat() / long
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * f).roundToInt().coerceAtLeast(1),
            (bmp.height * f).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bmp
        val m = Matrix().apply { postRotate(d.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 92) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
