package ch.honnobilis.pdfcreator.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.honnobilis.pdfcreator.AppViewModel
import ch.honnobilis.pdfcreator.Screen
import ch.honnobilis.pdfcreator.model.ImagePage
import ch.honnobilis.pdfcreator.model.PageItem
import ch.honnobilis.pdfcreator.model.PdfPage
import ch.honnobilis.pdfcreator.model.PdfPageKind
import ch.honnobilis.pdfcreator.pdf.Quality
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(vm: AppViewModel) {
    var showSave by remember { mutableStateOf(false) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.addFromGallery(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text(
                            vm.source?.displayName ?: "New document",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val n = vm.pages.size
                        Text("$n page" + if (n == 1) "" else "s", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showSave = true },
                        enabled = vm.pages.isNotEmpty() && vm.processingCount == 0,
                    ) {
                        Icon(Icons.Filled.Save, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(onClick = { vm.navigate(Screen.Camera()) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Camera")
                    }
                    FilledTonalButton(
                        onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Gallery")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (vm.processingCount > 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Processing ${vm.processingCount} photo" + if (vm.processingCount == 1) "…" else "s…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (vm.pages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (vm.processingCount == 0) {
                        Text(
                            "Add pages with the camera or from the gallery.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                PageGrid(vm)
            }
        }
    }

    if (showSave) {
        SaveDialog(vm, onDismiss = { showSave = false })
    }
}

@Composable
private fun PageGrid(vm: AppViewModel) {
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        vm.movePage(from.index, to.index)
    }
    Text(
        "Tap a page to edit it · long-press and drag to reorder",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(vm.pages, key = { it.id }) { page ->
            ReorderableItem(reorderState, key = page.id) { dragging ->
                val number = vm.pages.indexOfFirst { it.id == page.id } + 1
                PageCard(
                    page = page,
                    number = number,
                    dragging = dragging,
                    onClick = { vm.navigate(Screen.PageEdit(page.id)) },
                    modifier = Modifier.longPressDraggableHandle(),
                )
            }
        }
    }
}

@Composable
private fun PageCard(
    page: PageItem,
    number: Int,
    dragging: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.then(if (dragging) Modifier.shadow(12.dp, RoundedCornerShape(12.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = page.thumbnail
            if (thumb != null) {
                Image(thumb.asImageBitmap(), "Page $number", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Badge(pageLabel(page))
        }
    }
}

fun pageLabel(page: PageItem): String = when (page) {
    is ImagePage -> page.effectiveFilter.label
    is PdfPage -> if (page.kind == PdfPageKind.SCANNED) "Scan" else "Digital"
}

@Composable
fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SaveDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val source = vm.source
    var name by remember { mutableStateOf(vm.defaultFileName()) }
    var overwrite by remember { mutableStateOf(false) }
    var qualityPos by remember {
        mutableFloatStateOf(vm.prefs.qualityIndex.coerceIn(0, Quality.entries.lastIndex).toFloat())
    }
    var ocr by remember { mutableStateOf(vm.prefs.ocr) }
    val quality = Quality.entries[qualityPos.roundToInt().coerceIn(0, Quality.entries.lastIndex)]
    val ocrPages = vm.ocrCandidates

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save PDF") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (source != null) {
                    ChoiceRow("Save as new file", selected = !overwrite) { overwrite = false }
                    ChoiceRow(
                        if (source.canOverwrite) "Overwrite original" else "Overwrite original (not allowed for this file)",
                        selected = overwrite,
                        enabled = source.canOverwrite,
                    ) { overwrite = true }
                }
                if (!overwrite) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("File name") },
                        suffix = { Text(".pdf") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (vm.hasImagePages) {
                    Column {
                        Text(
                            "Image quality: ${quality.label} (~${formatKb(quality.approxKbPerPage)} per page)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = qualityPos,
                            onValueChange = { qualityPos = it },
                            valueRange = 0f..Quality.entries.lastIndex.toFloat(),
                            steps = Quality.entries.size - 2,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Searchable text (OCR)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (ocrPages == 0) "No pages need OCR" else "German + English · a few seconds per page",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ocr && ocrPages > 0, onCheckedChange = { ocr = it }, enabled = ocrPages > 0)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    vm.save(name, quality, ocr && ocrPages > 0, overwrite)
                },
                enabled = overwrite || name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatKb(kb: Int): String = if (kb >= 1000) "%.1f MB".format(kb / 1000f) else "$kb KB"
