package ch.honnobilis.pdfcreator.ui

import android.graphics.Bitmap
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
import kotlin.math.hypot
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditScreen(vm: AppViewModel, pageId: Long) {
    val page = vm.page(pageId)
    LaunchedEffect(page == null) {
        if (page == null) vm.navigate(Screen.Document)
    }
    if (page == null) return
    val number = vm.pages.indexOfFirst { it.id == pageId } + 1
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Page $number of ${vm.pages.size}") },
                actions = {
                    if (page is ImagePage) {
                        IconButton(onClick = { vm.navigate(Screen.Camera(replacePageId = page.id)) }) {
                            Icon(Icons.Filled.CameraAlt, "Retake")
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete page") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (page) {
                is ImagePage -> ImagePageEditor(vm, page)
                is PdfPage -> PdfPageEditor(vm, page)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete page $number?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deletePage(pageId)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Photo pages: crop + rotate + filter
// ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagePageEditor(vm: AppViewModel, page: ImagePage) {
    var cropMode by remember(page.id) { mutableIntStateOf(1) } // 0 = crop, 1 = result

    val preview by produceState<Bitmap?>(null, page.previewFile) { value = vm.loadPreview(page) }
    val result by produceState<Bitmap?>(null, preview, page.quad, page.rotation, page.effectiveFilter) {
        val p = preview
        value = if (p != null) vm.renderResult(page, p) else null
    }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = cropMode == 0,
                onClick = { cropMode = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Filled.Crop, null) },
            ) { Text("Crop") }
            SegmentedButton(
                selected = cropMode == 1,
                onClick = { cropMode = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Filled.Tune, null) },
            ) { Text("Result") }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val p = preview
            if (cropMode == 0 && p != null) {
                CropEditor(p, page.quad) { vm.setQuad(page.id, it) }
            } else {
                val r = result
                if (r != null) {
                    Image(r.asImageBitmap(), "Result", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator()
                }
            }
        }

        if (cropMode == 0) {
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterMode.entries.forEach { mode ->
                    FilterChip(
                        selected = page.filter == mode,
                        onClick = { vm.setFilter(page.id, mode) },
                        label = {
                            Text(if (mode == FilterMode.AUTO) "Auto · ${page.autoFilter.label}" else mode.label)
                        },
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
 * Shows the uncropped photo with four draggable corner handles.
 * The quad is committed to the view model when a drag ends.
 */
@Composable
private fun CropEditor(bitmap: Bitmap, quad: Quad, onChange: (Quad) -> Unit) {
    var local by remember(quad) { mutableStateOf(quad) }
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
        val touchRadius = with(density) { 40.dp.toPx() }

        fun toScreen(p: Pt) = Offset(imgRect.left + p.x * imgRect.width, imgRect.top + p.y * imgRect.height)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(imgRect) {
                    var active = -1
                    detectDragGestures(
                        onDragStart = { start ->
                            val pts = local.points.map(::toScreen)
                            val nearest = pts.indices.minByOrNull { hypot(pts[it].x - start.x, pts[it].y - start.y) }
                            active = if (nearest != null &&
                                hypot(pts[nearest].x - start.x, pts[nearest].y - start.y) <= touchRadius * 1.5f
                            ) nearest else -1
                        },
                        onDrag = { change, drag ->
                            if (active >= 0) {
                                change.consume()
                                val old = local.points[active]
                                val np = Pt(
                                    (old.x + drag.x / imgRect.width).coerceIn(0f, 1f),
                                    (old.y + drag.y / imgRect.height).coerceIn(0f, 1f),
                                )
                                local = local.with(active, np)
                            }
                        },
                        onDragEnd = {
                            if (active >= 0) currentOnChange(local)
                            active = -1
                        },
                        onDragCancel = { active = -1 },
                    )
                },
        ) {
            drawImage(
                image,
                dstOffset = androidx.compose.ui.unit.IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(imgRect.width.toInt(), imgRect.height.toInt()),
            )
            val pts = local.points.map(::toScreen)
            // Dim everything outside the selection.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(imgRect)
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            val outline = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(outline, accent, style = Stroke(width = 2.dp.toPx()))
            pts.forEach {
                drawCircle(Color.White, radius = 13.dp.toPx(), center = it)
                drawCircle(accent, radius = 10.dp.toPx(), center = it)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Pages of an existing PDF
// ---------------------------------------------------------------------------------------

@Composable
private fun PdfPageEditor(vm: AppViewModel, page: PdfPage) {
    val rendered by produceState<Bitmap?>(null, page.index, page.extraRotation) { value = vm.renderPdfPage(page) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val r = rendered
            if (r != null) {
                Image(r.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
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
