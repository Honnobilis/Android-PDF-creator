package ch.honnobilis.pdfcreator.model

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

enum class FilterMode(val label: String) {
    AUTO("Auto"),
    ORIGINAL("Original"),
    COLOR("Colour"),
    GRAY("Gray"),
    BW("B&W"),
}

/** A point in normalised image coordinates (0..1). */
data class Pt(val x: Float, val y: Float)

/** Document corners in normalised source-image coordinates, ordered TL, TR, BR, BL. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    val isFull: Boolean
        get() = this == FULL

    fun with(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    companion object {
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
    }
}

sealed interface PageItem {
    val id: Long
    val thumbnail: Bitmap?
}

/**
 * A page built from a photo (camera, gallery, or an edited scanned PDF page).
 * The photo lives in the app cache only and never reaches the gallery.
 */
data class ImagePage(
    override val id: Long,
    /** Full-resolution source, orientation already applied. */
    val sourceFile: File,
    /** Smaller copy of the source, used for the interactive editor and previews. */
    val previewFile: File,
    val quad: Quad,
    /** Clockwise rotation applied after cropping: 0, 90, 180 or 270. */
    val rotation: Int,
    val filter: FilterMode,
    /** What AUTO resolves to for this page (computed from the cropped image). */
    val autoFilter: FilterMode,
    override val thumbnail: Bitmap?,
) : PageItem {
    val effectiveFilter: FilterMode
        get() = if (filter == FilterMode.AUTO) autoFilter else filter
}

enum class PdfPageKind { SCANNED, DIGITAL }

/** An untouched page of an existing PDF that is being edited. */
data class PdfPage(
    override val id: Long,
    /** Index of the page in the source PDF. */
    val index: Int,
    val kind: PdfPageKind,
    /** Scanned page that already carries an (invisible) OCR text layer. */
    val hasOcr: Boolean,
    /** Rotation added in the editor, clockwise: 0, 90, 180 or 270. */
    val extraRotation: Int,
    /** Approximate pixels per PDF point of the page's main image (scanned pages). */
    val nativeScale: Float,
    override val thumbnail: Bitmap?,
) : PageItem

/** The PDF being edited, if the session was opened from an existing file. */
data class SourcePdf(
    val uri: Uri,
    val displayName: String,
    /** Private copy of the file; PDFBox and PdfRenderer read from here. */
    val cacheFile: File,
    /** Whether the original can be overwritten in place. */
    val canOverwrite: Boolean,
)

data class StoredPdf(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)
