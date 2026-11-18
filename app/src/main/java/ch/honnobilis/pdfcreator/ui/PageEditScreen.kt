package ch.honnobilis.pdfcreator.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.honnobilis.pdfcreator.AppViewModel
import ch.honnobilis.pdfcreator.Screen
import ch.honnobilis.pdfcreator.model.FilterMode
import ch.honnobilis.pdfcreator.model.ImagePage
import ch.honnobilis.pdfcreator.model.PdfPage
import ch.honnobilis.pdfcreator.model.PdfPageKind
import ch.honnobilis.pdfcreator.model.Pt
import ch.honnobilis.pdfcreator.model.Quad
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditScreen(vm: AppViewModel, pageId: Long) {
    val startIndex = vm.pages.indexOfFirst { it.id == pageId }
    LaunchedEffect(startIndex < 0) {
        if (startIndex < 0) vm.navigate(Screen.Document)
    }
    if (startIndex < 0 || vm.pages.isEmpty()) return

    // Crop mode is shared by all pages; swiping is disabled while cropping
    // so that dragging a corner never turns the page.
    var cropMode by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = startIndex) { vm.pages.size }
    val scope = rememberCoroutineScope()

    // Keep the screen's page id in sync (used by retake / back navigation).
    val currentId = vm.pages.getOrNull(pager.currentPage)?.id
    LaunchedEffect(currentId) {
        if (currentId != null && currentId != pageId) vm.navigate(Screen.PageEdit(currentId))
    }
    // Follow external changes (e.g. after deleting a page).
    LaunchedEffect(pageId) {
        val i = vm.pages.indexOfFirst { it.id == pageId }
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }

    val current = vm.pages.getOrNull(pager.currentPage)
    // Page that is currently zoomed in; swiping is paused while it is shown.
    var zoomedPageId by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var galleryAnchor by remember { mutableStateOf<Long?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val anchor = galleryAnchor
        if (uris.isNotEmpty() && anchor != null) vm.addFromGallery(uris, insertAfterPageId = anchor)
        galleryAnchor = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Page ${pager.currentPage + 1} of ${vm.pages.size}") },
                actions = {
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                    IconButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        enabled = pager.currentPage < vm.pages.lastIndex,
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
                    if (current != null) {
                        Box {
                            IconButton(onClick = { addMenu = true }) {
                                Icon(Icons.Filled.CameraAlt, "Retake or insert pages")
                            }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                if (current is ImagePage) {
                                    DropdownMenuItem(
                                        text = { Text("Retake this page") },
                                        leadingIcon = { Icon(Icons.Filled.Cameraswitch, null) },
                                        onClick = {
                                            addMenu = false
                                            vm.navigate(Screen.Camera(replacePageId = current.id))
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Insert photos after this page") },
                                    leadingIcon = { Icon(Icons.Filled.AddAPhoto, null) },
                                    onClick = {
                                        addMenu = false
                                        vm.navigate(Screen.Camera(insertAfterPageId = current.id))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Insert from gallery after this page") },
                                    leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                                    onClick = {
                                        addMenu = false
                                        galleryAnchor = current.id
                                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete page") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (current is ImagePage) {
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SegmentedButton(
                        selected = cropMode,
                        onClick = { cropMode = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { Icon(Icons.Filled.Crop, null) },
                    ) { Text("Crop") }
                    SegmentedButton(
                        selected = !cropMode,
                        onClick = { cropMode = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { Icon(Icons.Filled.Tune, null) },
                    ) { Text("Result") }
                }
            }
            HorizontalPager(
                state = pager,
                key = { i -> vm.pages.getOrNull(i)?.id ?: i },
                userScrollEnabled = !(cropMode && current is ImagePage) && zoomedPageId != current?.id,
                modifier = Modifier.weight(1f),
            ) { i ->
                when (val page = vm.pages.getOrNull(i)) {
                    is ImagePage -> ImagePageEditor(vm, page, cropMode) { zoomed ->
                        if (zoomed) zoomedPageId = page.id else if (zoomedPageId == page.id) zoomedPageId = null
                    }
                    is PdfPage -> PdfPageEditor(vm, page) { zoomed ->
                        if (zoomed) zoomedPageId = page.id else if (zoomedPageId == page.id) zoomedPageId = null
                    }
                    null -> Unit
                }
            }
        }
    }

    if (confirmDelete && current != null) {
        val number = pager.currentPage + 1
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete page $number?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deletePage(current.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Photo pages: crop + rotate + filter
// ---------------------------------------------------------------------------------------

@Composable
private fun ImagePageEditor(vm: AppViewModel, page: ImagePage, cropMode: Boolean, onZoomedChange: (Boolean) -> Unit) {
    val preview by produceState<Bitmap?>(null, page.previewFile) { value = vm.loadPreview(page) }
    val result by produceState<Bitmap?>(null, preview, page.quad, page.rotation, page.effectiveFilter, page.strength) {
        val p = preview
        value = if (p != null) vm.renderResult(page, p) else null
    }
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }
    val wantSharp = zoom > 1.3f && !cropMode
    // Sharper version from the full-resolution photo, only while zoomed in.
    val sharp by produceState<Bitmap?>(null, wantSharp, page.quad, page.rotation, page.effectiveFilter, page.strength) {
        value = null
        if (wantSharp) value = vm.renderResultSharp(page)
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val p = preview
            if (cropMode && p != null) {
                CropEditor(p, page.quad) { vm.setQuad(page.id, it) }
            } else {
                val r = sharp ?: result
                if (r != null) {
                    ZoomableImage(
                        bitmap = r,
                        contentDescription = "Result",
                        onScaleChange = {
                            zoom = it
                            onZoomedChange(it > 1.01f)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        }

        if (cropMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { vm.autoDetect(page.id) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.AutoFixHigh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Detect")
                }
                OutlinedButton(onClick = { vm.setQuad(page.id, Quad.FULL) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CropFree, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Full image")
                }
            }
        } else {
            FilterChips(
                selected = page.filter,
                autoResult = page.autoFilter,
                onSelect = { vm.setFilter(page.id, it) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            StrengthSlider(
                effective = page.effectiveFilter,
                strength = page.strength,
                onChange = { vm.setStrength(page.id, it) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                RotateButtons(Modifier.weight(1f), onLeft = { vm.rotate(page.id, -90) }, onRight = { vm.rotate(page.id, 90) })
            }
        }
    }
}

@Composable
private fun RotateButtons(modifier: Modifier, onLeft: () -> Unit, onRight: () -> Unit) {
    OutlinedButton(onClick = onLeft, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.RotateLeft, null)
        Spacer(Modifier.width(6.dp))
        Text("Left")
    }
    Spacer(Modifier.width(12.dp))
    OutlinedButton(onClick = onRight, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.RotateRight, null)
        Spacer(Modifier.width(6.dp))
        Text("Right")
    }
}

/**
 * Shows the uncropped photo with four corner handles and four side handles.
 * The outline follows the finger live; while dragging, a magnifier with a crosshair
 * in the opposite top corner shows exactly where the point lands.
 * The quad is committed to the view model when a drag ends.
 */
@Composable
private fun CropEditor(bitmap: Bitmap, quad: Quad, onChange: (Quad) -> Unit) {
    // One state object for the whole lifetime of the editor; the gesture coroutine
    // below keeps a reference to it, so it must never be replaced.
    val localState = remember { mutableStateOf(quad) }
    var local by localState
    // -1 = nothing; 0..3 corners (TL, TR, BR, BL); 4..7 sides (top, right, bottom, left)
    var active by remember { mutableIntStateOf(-1) }
    LaunchedEffect(quad) { if (active < 0) local = quad }

    val currentOnChange by rememberUpdatedState(onChange)
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / bitmap.width, boxH / bitmap.height)
        val imgRect = Rect(
            Offset((boxW - bitmap.width * scale) / 2, (boxH - bitmap.height * scale) / 2),
            androidx.compose.ui.geometry.Size(bitmap.width * scale, bitmap.height * scale),
        )
        val currentRect by rememberUpdatedState(imgRect)
        val touchRadius = with(density) { 48.dp.toPx() }

        fun toScreen(p: Pt, r: Rect) = Offset(r.left + p.x * r.width, r.top + p.y * r.height)

        /** Handle positions in screen coordinates: 4 corners, then 4 side midpoints. */
        fun handles(q: Quad, r: Rect): List<Offset> {
            val c = q.points.map { toScreen(it, r) }
            return c + List(4) { i -> (c[i] + c[(i + 1) % 4]) / 2f }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val hs = handles(localState.value, currentRect)
                            val nearest = hs.indices.minByOrNull { (hs[it] - start).getDistance() }
                            active = if (nearest != null && (hs[nearest] - start).getDistance() <= touchRadius) nearest else -1
                        },
                        onDrag = { change, drag ->
                            val a = active
                            if (a >= 0) {
                                change.consume()
                                val r = currentRect
                                val dx = drag.x / r.width
                                val dy = drag.y / r.height
                                fun move(q: Quad, i: Int): Quad {
                                    val p = q.points[i]
                                    return q.with(i, Pt((p.x + dx).coerceIn(0f, 1f), (p.y + dy).coerceIn(0f, 1f)))
                                }
                                val q = localState.value
                                localState.value = if (a < 4) move(q, a) else move(move(q, a - 4), (a - 3) % 4)
                            }
                        },
                        onDragEnd = {
                            if (active >= 0) currentOnChange(localState.value)
                            active = -1
                        },
                        onDragCancel = {
                            if (active >= 0) currentOnChange(localState.value)
                            active = -1
                        },
                    )
                },
        ) {
            val stroke = 2.dp.toPx()

            fun drawPhoto() {
                drawImage(
                    image,
                    dstOffset = androidx.compose.ui.unit.IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(imgRect.width.toInt(), imgRect.height.toInt()),
                )
            }

            fun outlinePath(pts: List<Offset>) = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }

            drawPhoto()
            val corners = local.points.map { toScreen(it, imgRect) }
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(imgRect)
                addPath(outlinePath(corners))
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            drawPath(outlinePath(corners), accent, style = Stroke(width = stroke))

            val hs = handles(local, imgRect)
            hs.forEachIndexed { i, p ->
                val r = if (i < 4) 12.dp.toPx() else 8.dp.toPx()
                drawCircle(Color.White, radius = r + 2.dp.toPx(), center = p)
                drawCircle(accent, radius = r, center = p)
            }

            // Magnifier
            val a = active
            if (a >= 0) {
                val focus = hs[a]
                val radius = 64.dp.toPx()
                val margin = 12.dp.toPx()
                val zoom = 3f
                val centerX = if (focus.x < size.width / 2) size.width - margin - radius else margin + radius
                val center = Offset(centerX, margin + radius)
                val circle = Path().apply { addOval(Rect(center, radius)) }

                drawCircle(Color.Black, radius = radius, center = center)
                clipPath(circle) {
                    withTransform({
                        translate(center.x - focus.x, center.y - focus.y)
                        scale(zoom, zoom, pivot = focus)
                    }) {
                        drawPhoto()
                        drawPath(outlinePath(corners), accent, style = Stroke(width = stroke / zoom))
                    }
                    // Crosshair
                    val gap = 6.dp.toPx()
                    val len = radius
                    val ch = 1.5.dp.toPx()
                    listOf(Color.Black.copy(alpha = 0.6f) to ch * 2.2f, Color.White to ch).forEach { (col, wdt) ->
                        drawLine(col, Offset(center.x - len, center.y), Offset(center.x - gap, center.y), wdt)
                        drawLine(col, Offset(center.x + gap, center.y), Offset(center.x + len, center.y), wdt)
                        drawLine(col, Offset(center.x, center.y - len), Offset(center.x, center.y - gap), wdt)
                        drawLine(col, Offset(center.x, center.y + gap), Offset(center.x, center.y + len), wdt)
                    }
                }
                drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 3.dp.toPx()))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Pages of an existing PDF
// ---------------------------------------------------------------------------------------

@Composable
private fun PdfPageEditor(vm: AppViewModel, page: PdfPage, onZoomedChange: (Boolean) -> Unit) {
    val rendered by produceState<Bitmap?>(null, page.index, page.extraRotation) { value = vm.renderPdfPage(page) }
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }
    val wantSharp = zoom > 1.3f
    val sharp by produceState<Bitmap?>(null, wantSharp, page.index, page.extraRotation) {
        value = null
        if (wantSharp) value = vm.renderPdfPage(page, sharp = true)
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val r = sharp ?: rendered
            if (r != null) {
                ZoomableImage(
                    bitmap = r,
                    contentDescription = "Page",
                    onScaleChange = {
                        zoom = it
                        onZoomedChange(it > 1.01f)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator()
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (page.kind == PdfPageKind.SCANNED) {
                    Text("Scanned page" + if (page.hasOcr) " · already searchable" else "", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "To crop it or apply a filter, convert it into an editable photo page. " +
                            "It will then be re-compressed with the quality you choose when saving" +
                            if (page.hasOcr) ", and OCR (if switched on) will replace the old text layer." else ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { vm.convertScanToImage(page.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) { Text("Edit scan (crop & filters)") }
                } else {
                    Text("Digital page", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "This page contains real text and graphics. Filters, cropping and OCR aren't offered " +
                            "because they would turn it into a lower-quality image. You can rotate, move or delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            RotateButtons(Modifier.weight(1f), onLeft = { vm.rotate(page.id, -90) }, onRight = { vm.rotate(page.id, 90) })
        }
    }
}
