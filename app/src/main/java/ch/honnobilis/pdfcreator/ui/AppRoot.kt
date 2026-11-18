package ch.honnobilis.pdfcreator.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ch.honnobilis.pdfcreator.AppViewModel
import ch.honnobilis.pdfcreator.Screen
import ch.honnobilis.pdfcreator.storage.PdfStore

@Composable
fun AppRoot(vm: AppViewModel) {
    val context = LocalContext.current

    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }

    when (val s = vm.screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Document -> DocumentScreen(vm)
        is Screen.PageEdit -> PageEditScreen(vm, s.pageId)
        is Screen.Camera -> CameraScreen(vm, s.replacePageId, s.insertAfterPageId)
    }

    vm.busyMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            },
        )
    }

    vm.saveProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Saving PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(p.message)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelSave) { Text("Cancel") } },
        )
    }

    vm.saved?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.finishSaved(keepEditing = false) },
            title = { Text("PDF saved") },
            text = {
                Column {
                    Text(r.name)
                    Spacer(Modifier.height(4.dp))
                    Text("in ${PdfStore.FOLDER.trimEnd('/')}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sharePdf(context, r.uri, r.name)
                    vm.finishSaved(keepEditing = false)
                }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        openPdf(context, r.uri)
                        vm.finishSaved(keepEditing = false)
                    }) { Text("Open") }
                    TextButton(onClick = { vm.finishSaved(keepEditing = false) }) { Text("Done") }
                }
            },
        )
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }
}

fun sharePdf(context: Context, uri: Uri, name: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = PdfStore.MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name.removeSuffix(".pdf"))
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share PDF"))
}

fun sharePdfs(context: Context, pdfs: List<Pair<Uri, String>>) {
    if (pdfs.size == 1) return sharePdf(context, pdfs[0].first, pdfs[0].second)
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = PdfStore.MIME
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(pdfs.map { it.first }))
        clipData = ClipData.newRawUri(pdfs[0].second, pdfs[0].first).also { clip ->
            pdfs.drop(1).forEach { clip.addItem(ClipData.Item(it.first)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ${pdfs.size} PDFs"))
}

fun openPdf(context: Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, PdfStore.MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app installed that can show PDFs", Toast.LENGTH_LONG).show()
    }
}
