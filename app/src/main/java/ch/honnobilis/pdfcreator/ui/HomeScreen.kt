package ch.honnobilis.pdfcreator.ui

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.honnobilis.pdfcreator.AppViewModel
import ch.honnobilis.pdfcreator.Screen
import ch.honnobilis.pdfcreator.model.StoredPdf
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var toDelete by remember { mutableStateOf<List<StoredPdf>>(emptyList()) }
    var toRename by remember { mutableStateOf<StoredPdf?>(null) }

    // Selection mode for batch actions. null = not selecting.
    var selection by remember { mutableStateOf<Set<Uri>?>(null) }
    val selecting = selection != null
    fun toggle(pdf: StoredPdf) {
        val s = selection ?: emptySet()
        selection = if (pdf.uri in s) s - pdf.uri else s + pdf.uri
    }
    // Drop entries that no longer exist (deleted, or removed in a file manager).
    LaunchedEffect(vm.history) {
        selection = selection?.intersect(vm.history.map { it.uri }.toSet())
    }
    BackHandler(enabled = selecting) { selection = null }
    val selectedPdfs = vm.history.filter { selection?.contains(it.uri) == true }

    /** Runs [action] right away, or after confirming that an unsaved document may be discarded. */
    fun startNew(action: () -> Unit) {
        if (vm.hasSession && vm.dirty) pendingAction = action else action()
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            vm.newDocument()
            vm.addFromGallery(uris)
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openPdf(uri)
    }

    // The folder may have changed while we were away (files deleted in a file manager…).
    LifecycleResumeEffect(Unit) {
        vm.refreshHistory()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { selection = null }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    },
                    title = { Text("${selectedPdfs.size} selected") },
                    actions = {
                        IconButton(onClick = {
                            selection = if (selectedPdfs.size == vm.history.size) emptySet()
                            else vm.history.map { it.uri }.toSet()
                        }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton(
                            onClick = { sharePdfs(context, selectedPdfs.map { it.uri to it.name }) },
                            enabled = selectedPdfs.isNotEmpty(),
                        ) { Icon(Icons.Filled.Share, "Share selected") }
                        IconButton(
                            onClick = { toDelete = selectedPdfs },
                            enabled = selectedPdfs.isNotEmpty(),
                        ) { Icon(Icons.Filled.Delete, "Delete selected") }
                    },
                )
            } else {
                TopAppBar(title = { Text("PDF Creator") })
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!selecting) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ActionCard("Camera", Icons.Filled.CameraAlt, Modifier.weight(1f)) {
                            startNew {
                                vm.newDocument()
                                vm.navigate(Screen.Camera())
                            }
                        }
                        ActionCard("Gallery", Icons.Filled.PhotoLibrary, Modifier.weight(1f)) {
                            startNew {
                                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                        ActionCard("Edit PDF", Icons.Filled.EditNote, Modifier.weight(1f)) {
                            startNew { pickPdf.launch(arrayOf("application/pdf")) }
                        }
                    }
                }

                if (vm.hasSession) {
                    item {
                        Card(
                            onClick = { vm.navigate(Screen.Document) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Continue current document", fontWeight = FontWeight.SemiBold)
                                    val n = vm.pages.size
                                    Text(
                                        (vm.source?.displayName?.let { "$it · " } ?: "") + "$n page" + if (n == 1) "" else "s",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                            }
                        }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Recent PDFs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (vm.history.isNotEmpty()) {
                            TextButton(onClick = { selection = emptySet() }) {
                                Icon(Icons.Filled.Checklist, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Select")
                            }
                        }
                    }
                }
            }

            if (vm.history.isEmpty()) {
                item {
                    Text(
                        "PDFs you create are saved to Documents/PDF Creator and listed here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            items(vm.history, key = { it.uri.toString() }) { pdf ->
                HistoryRow(
                    vm = vm,
                    pdf = pdf,
                    selecting = selecting,
                    selected = selection?.contains(pdf.uri) == true,
                    onClick = { if (selecting) toggle(pdf) else openPdf(context, pdf.uri) },
                    onLongClick = { if (selecting) toggle(pdf) else selection = setOf(pdf.uri) },
                    onShare = { sharePdf(context, pdf.uri, pdf.name) },
                    onEdit = { startNew { vm.openPdf(pdf.uri) } },
                    onRename = { toRename = pdf },
                    onDelete = { toDelete = listOf(pdf) },
                )
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Discard current document?") },
            text = { Text("The document you're working on hasn't been saved. Start a new one anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    action()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancel") } },
        )
    }

    if (toDelete.isNotEmpty()) {
        val list = toDelete
        AlertDialog(
            onDismissRequest = { toDelete = emptyList() },
            title = { Text(if (list.size == 1) "Delete PDF?" else "Delete ${list.size} PDFs?") },
            text = {
                Text(
                    if (list.size == 1) "\"${list[0].name}\" will be deleted from the phone."
                    else "The ${list.size} selected PDFs will be deleted from the phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteStored(list)
                    toDelete = emptyList()
                    selection = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = emptyList() }) { Text("Cancel") } },
        )
    }

    toRename?.let { pdf ->
        NameDialog(
            title = "Rename PDF",
            initial = pdf.name.removeSuffix(".pdf").removeSuffix(".PDF"),
            confirmLabel = "Rename",
            onDismiss = { toRename = null },
            onConfirm = {
                vm.renameStored(pdf, it)
                toRename = null
            },
        )
    }
}

@Composable
private fun ActionCard(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier.height(96.dp)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    vm: AppViewModel,
    pdf: StoredPdf,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val thumb by produceState<Bitmap?>(null, pdf.uri, pdf.modifiedMillis) { value = vm.historyThumbnail(pdf) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
        Box(
            Modifier
                .size(width = 44.dp, height = 58.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .background(Color.White, RoundedCornerShape(8.dp)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pdf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pdf.modifiedMillis)) +
                    " · " + Formatter.formatShortFileSize(context, pdf.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selecting) {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                        onClick = { menu = false; onClick() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit pages") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
