package ch.honnobilis.pdfcreator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.honnobilis.pdfcreator.imaging.BitmapIO
import ch.honnobilis.pdfcreator.imaging.DocumentDetector
import ch.honnobilis.pdfcreator.imaging.ImageProcessor
import ch.honnobilis.pdfcreator.model.FilterMode
import ch.honnobilis.pdfcreator.model.ImagePage
import ch.honnobilis.pdfcreator.model.PageItem
import ch.honnobilis.pdfcreator.model.PdfPage
import ch.honnobilis.pdfcreator.model.PdfPageKind
import ch.honnobilis.pdfcreator.model.Quad
import ch.honnobilis.pdfcreator.model.SourcePdf
import ch.honnobilis.pdfcreator.model.StoredPdf
import ch.honnobilis.pdfcreator.pdf.PdfAnalyzer
import ch.honnobilis.pdfcreator.pdf.PdfBuilder
import ch.honnobilis.pdfcreator.pdf.PdfLoader
import ch.honnobilis.pdfcreator.pdf.Quality
import ch.honnobilis.pdfcreator.storage.PdfStore
import ch.honnobilis.pdfcreator.storage.Prefs
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

sealed interface Screen {
    data object Home : Screen
    data object Document : Screen
    data class PageEdit(val pageId: Long) : Screen
    /** [replacePageId] set = retake a single page. */
    data class Camera(val replacePageId: Long? = null) : Screen
}

data class SaveProgress(val done: Int, val total: Int, val message: String)
data class SavedResult(val uri: Uri, val name: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val store = PdfStore(app)
    val prefs = Prefs(app)

    // --- UI state -------------------------------------------------------------------------
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    val pages = mutableStateListOf<PageItem>()
    var source by mutableStateOf<SourcePdf?>(null)
        private set
    /** Pages changed since the last save. */
    var dirty by mutableStateOf(false)
        private set
    var processingCount by mutableIntStateOf(0)
        private set
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var saveProgress by mutableStateOf<SaveProgress?>(null)
        private set
    var saved by mutableStateOf<SavedResult?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var history by mutableStateOf<List<StoredPdf>>(emptyList())
        private set

    // --- internals ------------------------------------------------------------------------
    private val ids = AtomicLong(1)
    private val importMutex = Mutex()
    private var saveJob: Job? = null
    private val pageJobs = HashMap<Long, Job>()
    private val importJobs = mutableSetOf<Job>()
    /** Unrotated thumbnails of existing PDF pages. */
    private val pdfBaseThumbs = HashMap<Long, Bitmap>()
    private val historyThumbs = LruCache<Uri, Bitmap>(40)
    private val sessionDir get() = File(ctx.cacheDir, "session").apply { mkdirs() }

    init {
        refreshHistory()
    }

    // --- navigation -----------------------------------------------------------------------
    fun navigate(to: Screen) {
        screen = to
    }

    /** Returns false when the caller should leave the app. */
    fun back(): Boolean {
        when (val s = screen) {
            is Screen.Camera -> screen = if (s.replacePageId != null) Screen.PageEdit(s.replacePageId) else Screen.Document
            is Screen.PageEdit -> screen = Screen.Document
            Screen.Document -> screen = Screen.Home
            Screen.Home -> return false
        }
        return true
    }

    val hasSession: Boolean get() = pages.isNotEmpty() || source != null

    /** Starts an empty document (discarding any current one). */
    fun newDocument() {
        clearSession()
        screen = Screen.Document
    }

    fun discardSession() {
        clearSession()
        screen = Screen.Home
    }

    private fun clearSession() {
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        importJobs.forEach { it.cancel() }
        importJobs.clear()
        pages.clear()
        pdfBaseThumbs.clear()
        source = null
        dirty = false
        sessionDir.deleteRecursively()
    }

    // --- adding photos --------------------------------------------------------------------
    fun addFromGallery(uris: List<Uri>) {
        uris.forEach { uri -> enqueueImport(null) { BitmapIO.decode(ctx, uri, SOURCE_MAX) } }
    }

    fun addCapturedPhoto(file: File, replacePageId: Long?) {
        enqueueImport(replacePageId) {
            try {
                BitmapIO.decode(ctx, Uri.fromFile(file), SOURCE_MAX)
            } finally {
                file.delete()
            }
        }
    }

    fun newCaptureFile(): File = File(File(ctx.cacheDir, "capture").apply { mkdirs() }, "${System.nanoTime()}.jpg")

    private fun enqueueImport(replaceId: Long?, decode: () -> Bitmap) {
        processingCount++
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                importMutex.withLock {
                    val page = withContext(Dispatchers.Default) {
                        createImagePage(replaceId ?: ids.getAndIncrement(), decode(), detect = true, FilterMode.AUTO)
                    }
                    val idx = pages.indexOfFirst { it.id == page.id }
                    if (idx >= 0) pages[idx] = page else pages.add(page)
                    dirty = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Could not load a photo: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                processingCount--
                importJobs.remove(job)
            }
        }
        importJobs += job
        job.start()
    }

    private fun createImagePage(id: Long, bitmap: Bitmap, detect: Boolean, filter: FilterMode): ImagePage {
        val stamp = System.nanoTime()
        val sourceFile = File(sessionDir, "p${id}_${stamp}_src.jpg")
        val previewFile = File(sessionDir, "p${id}_${stamp}_preview.jpg")
        BitmapIO.saveJpeg(bitmap, sourceFile, 92)
        val preview = BitmapIO.scaledCopy(bitmap, PREVIEW_MAX)
        bitmap.recycle()
        BitmapIO.saveJpeg(preview, previewFile, 90)

        val quad = if (detect) DocumentDetector.detect(preview) else Quad.FULL
        val auto = suggestFilter(preview, quad)
        val page = ImagePage(id, sourceFile, previewFile, quad, 0, filter, auto, null)
        val thumb = ImageProcessor.render(preview, quad, 0, page.effectiveFilter, THUMB_MAX)
        preview.recycle()
        return page.copy(thumbnail = thumb)
    }

    private fun suggestFilter(preview: Bitmap, quad: Quad): FilterMode {
        val cropped = if (quad.isFull) preview else ImageProcessor.warp(preview, quad)
        return ImageProcessor.suggestFilter(cropped).also { if (cropped !== preview) cropped.recycle() }
    }

    // --- editing pages --------------------------------------------------------------------
    fun page(id: Long): PageItem? = pages.firstOrNull { it.id == id }

    fun movePage(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
        dirty = true
    }

    fun deletePage(id: Long) {
        pageJobs.remove(id)?.cancel()
        pages.removeAll { it.id == id }
        pdfBaseThumbs.remove(id)
        dirty = true
        if (screen is Screen.PageEdit) screen = Screen.Document
    }

    fun rotate(id: Long, delta: Int) {
        when (val p = page(id)) {
            is ImagePage -> updateImagePage(p.copy(rotation = norm(p.rotation + delta)), recomputeAuto = false)
            is PdfPage -> {
                val r = norm(p.extraRotation + delta)
                val base = pdfBaseThumbs[id]
                val thumb = base?.let { BitmapIO.rotate(it.copy(Bitmap.Config.ARGB_8888, false), r) } ?: p.thumbnail
                replace(p.copy(extraRotation = r, thumbnail = thumb))
            }
            null -> Unit
        }
    }

    fun setFilter(id: Long, filter: FilterMode) {
        val p = page(id) as? ImagePage ?: return
        updateImagePage(p.copy(filter = filter), recomputeAuto = false)
    }

    fun setQuad(id: Long, quad: Quad) {
        val p = page(id) as? ImagePage ?: return
        updateImagePage(p.copy(quad = quad), recomputeAuto = true)
    }

    /** Re-runs edge detection on the page photo. */
    fun autoDetect(id: Long) {
        val p = page(id) as? ImagePage ?: return
        viewModelScope.launch {
            val quad = withContext(Dispatchers.Default) {
                val preview = BitmapIO.decode(p.previewFile, PREVIEW_MAX)
                DocumentDetector.detect(preview).also { preview.recycle() }
            }
            if (quad == Quad.FULL) message = "No document edges found – adjust the corners by hand"
            setQuad(id, quad)
        }
    }

    /** Applies the change at once and refreshes the thumbnail (and auto filter) in the background. */
    private fun updateImagePage(updated: ImagePage, recomputeAuto: Boolean) {
        replace(updated)
        dirty = true
        pageJobs.remove(updated.id)?.cancel()
        pageJobs[updated.id] = viewModelScope.launch {
            val refreshed = withContext(Dispatchers.Default) {
                val preview = BitmapIO.decode(updated.previewFile, PREVIEW_MAX)
                val auto = if (recomputeAuto) suggestFilter(preview, updated.quad) else updated.autoFilter
                val p = updated.copy(autoFilter = auto)
                val thumb = ImageProcessor.render(preview, p.quad, p.rotation, p.effectiveFilter, THUMB_MAX)
                preview.recycle()
                p.copy(thumbnail = thumb)
            }
            // Only apply if nothing changed in the meantime.
            val current = page(updated.id) as? ImagePage
            if (current != null && current.copy(thumbnail = null, autoFilter = refreshed.autoFilter) ==
                refreshed.copy(thumbnail = null)
            ) {
                replace(refreshed)
            }
        }
    }

    /** Converts an untouched scanned PDF page into an editable photo page. */
    fun convertScanToImage(id: Long) {
        val p = page(id) as? PdfPage ?: return
        val src = source ?: return
        busyMessage = "Preparing page…"
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.Default) {
                    val bmp = withRenderer(src.cacheFile) { r ->
                        PdfBuilder.renderPage(r, p.index, p.nativeScale.coerceIn(1f, 5f), SOURCE_MAX)
                    }
                    createImagePage(p.id, BitmapIO.rotate(bmp, p.extraRotation), detect = false, FilterMode.ORIGINAL)
                }
                replace(page)
                pdfBaseThumbs.remove(id)
                dirty = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = "Could not prepare the page: ${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    private fun replace(page: PageItem) {
        val idx = pages.indexOfFirst { it.id == page.id }
        if (idx >= 0) pages[idx] = page
    }

    /** Loads the editor preview of an image page (source photo, uncropped). */
    suspend fun loadPreview(page: ImagePage): Bitmap = withContext(Dispatchers.Default) {
        BitmapIO.decode(page.previewFile, PREVIEW_MAX)
    }

    suspend fun renderResult(page: ImagePage, preview: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        ImageProcessor.render(preview, page.quad, page.rotation, page.effectiveFilter, PREVIEW_MAX)
    }

    suspend fun renderPdfPage(page: PdfPage): Bitmap? {
        val src = source ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bmp = withRenderer(src.cacheFile) { PdfBuilder.renderPage(it, page.index, 3f, PREVIEW_MAX) }
                BitmapIO.rotate(bmp, page.extraRotation)
            }.getOrNull()
        }
    }

    // --- opening existing PDFs ------------------------------------------------------------
    fun openPdf(uri: Uri) {
        clearSession()
        busyMessage = "Opening PDF…"
        viewModelScope.launch {
            try {
                val (src, loaded) = withContext(Dispatchers.IO) {
                    val file = File(sessionDir, "source.pdf")
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    val name = store.displayName(uri) ?: "Document.pdf"
                    val src = SourcePdf(uri, name, file, store.canWrite(uri))
                    val analyses = PdfLoader.open(file).use { doc ->
                        (0 until doc.numberOfPages).map { PdfAnalyzer.analyze(doc, it) }
                    }
                    val list = withRenderer(file) { r ->
                        analyses.mapIndexed { i, a ->
                            val id = ids.getAndIncrement()
                            val thumb = PdfBuilder.renderPage(r, i, 1f, THUMB_MAX)
                            pdfBaseThumbs[id] = thumb
                            PdfPage(id, i, a.kind, a.hasOcr, 0, a.nativeScale, thumb)
                        }
                    }
                    src to list
                }
                source = src
                pages.addAll(loaded)
                dirty = false
                screen = Screen.Document
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidPasswordException) {
                clearSession()
                message = "This PDF is password-protected and can't be edited."
            } catch (e: Throwable) {
                clearSession()
                message = "Could not open the PDF: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busyMessage = null
            }
        }
    }

    private inline fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { return block(it) }
        }
    }

    // --- saving ---------------------------------------------------------------------------
    fun defaultFileName(): String {
        val src = source
        return if (src != null) {
            src.displayName.removeSuffix(".pdf").removeSuffix(".PDF") + " (edited)"
        } else {
            "Scan " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date())
        }
    }

    val hasImagePages: Boolean get() = pages.any { it is ImagePage }
    val ocrCandidates: Int
        get() = pages.count { it is ImagePage || (it is PdfPage && it.kind == PdfPageKind.SCANNED && !it.hasOcr) }

    fun save(name: String, quality: Quality, ocr: Boolean, overwrite: Boolean) {
        prefs.qualityIndex = quality.ordinal
        prefs.ocr = ocr
        val snapshot = pages.toList()
        val src = source
        saveProgress = SaveProgress(0, snapshot.size, "Preparing…")
        saveJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val tmp = File(ctx.cacheDir, "output.pdf")
                    PdfBuilder(ctx).build(snapshot, src, quality, ocr, tmp) { done, total, what ->
                        saveProgress = SaveProgress(done, total, what)
                    }
                    val saved = if (overwrite && src != null) {
                        store.overwrite(src.uri, tmp)
                        SavedResult(src.uri, src.displayName)
                    } else {
                        val uri = store.saveNew(tmp, name)
                        SavedResult(uri, store.displayName(uri) ?: PdfStore.ensurePdfExtension(name))
                    }
                    tmp.delete()
                    saved
                }
                dirty = false
                saved = result
                refreshHistory()
            } catch (e: CancellationException) {
                message = "Saving cancelled"
            } catch (e: Throwable) {
                message = "Saving failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                saveProgress = null
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
    }

    /** Called when the "saved" dialog is closed. */
    fun finishSaved(keepEditing: Boolean) {
        saved = null
        if (!keepEditing) discardSession()
    }

    // --- history --------------------------------------------------------------------------
    fun refreshHistory() {
        viewModelScope.launch {
            history = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) }
        }
    }

    fun deleteStored(pdfs: Collection<StoredPdf>) {
        if (pdfs.isEmpty()) return
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                pdfs.filterNot { runCatching { store.delete(it.uri) }.getOrDefault(false) }
            }
            when {
                failed.size == 1 -> message = "Could not delete ${failed[0].name}"
                failed.isNotEmpty() -> message = "Could not delete ${failed.size} files"
                pdfs.size > 1 -> message = "${pdfs.size} PDFs deleted"
            }
            pdfs.forEach { historyThumbs.remove(it.uri) }
            refreshHistory()
        }
    }

    fun renameStored(pdf: StoredPdf, newName: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.rename(pdf.uri, newName) }
                // Keep the editor title in sync if this file is open.
                source?.let { if (it.uri == pdf.uri) source = it.copy(displayName = store.displayName(pdf.uri) ?: it.displayName) }
            } catch (e: Throwable) {
                message = "Could not rename: ${e.message ?: e.javaClass.simpleName}"
            }
            refreshHistory()
        }
    }

    suspend fun historyThumbnail(pdf: StoredPdf): Bitmap? {
        historyThumbs.get(pdf.uri)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openFileDescriptor(pdf.uri, "r")!!.use { fd ->
                    PdfRenderer(fd).use { r -> PdfBuilder.renderPage(r, 0, 1f, 200) }
                }
            }.getOrNull()?.also { historyThumbs.put(pdf.uri, it) }
        }
    }

    companion object {
        const val SOURCE_MAX = 4000
        const val PREVIEW_MAX = 1600
        const val THUMB_MAX = 480

        private fun norm(r: Int) = ((r % 360) + 360) % 360
    }
}
