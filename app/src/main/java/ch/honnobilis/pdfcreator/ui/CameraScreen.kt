package ch.honnobilis.pdfcreator.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.honnobilis.pdfcreator.AppViewModel
import ch.honnobilis.pdfcreator.InsertCursor
import ch.honnobilis.pdfcreator.Screen
import kotlinx.coroutines.launch

/**
 * In-app camera. Photos are written to the app's private cache and handed to the
 * page pipeline; they never appear in the phone's gallery.
 */
@Composable
fun CameraScreen(vm: AppViewModel, replacePageId: Long?, insertAfterPageId: Long?) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            CameraContent(vm, replacePageId, insertAfterPageId)
        } else {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (asked) "Camera access is needed to take photos of documents." else "Waiting for camera permission…",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (asked) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    TextButton(onClick = { vm.back() }) { Text("Back", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun CameraContent(vm: AppViewModel, replacePageId: Long?, insertAfterPageId: Long?) {
    val cursor = remember { InsertCursor(insertAfterPageId) }

    /** Leaves the camera: inserting → editor on the first new page, otherwise the page grid. */
    fun done() {
        if (insertAfterPageId != null) {
            vm.showFirstInserted(cursor, fallbackId = insertAfterPageId)
        } else {
            vm.navigate(Screen.Document)
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageCaptureFlashMode = ImageCapture.FLASH_MODE_AUTO
        }
    }
    LaunchedEffect(lifecycleOwner) { controller.bindToLifecycle(lifecycleOwner) }

    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var capturing by remember { mutableStateOf(false) }
    var shots by remember { mutableIntStateOf(0) }
    val shutterFlash = remember { Animatable(0f) }
    val lastThumb = vm.pages.lastOrNull()?.thumbnail

    fun capture() {
        if (capturing) return
        capturing = true
        val file = vm.newCaptureFile()
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    shots++
                    vm.addCapturedPhoto(file, replacePageId, cursor)
                    if (replacePageId != null) vm.navigate(Screen.PageEdit(replacePageId))
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    file.delete()
                    vm.message = "Photo failed: ${exception.message}"
                }
            },
        )
        scope.launch {
            shutterFlash.snapTo(0.8f)
            shutterFlash.animateTo(0f, tween(250))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .alpha(shutterFlash.value)
                .background(Color.White),
        )

        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    replacePageId != null -> "Retake page"
                    insertAfterPageId != null -> "Insert pages"
                    else -> "Photos are not saved to the gallery"
                },
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                flash = when (flash) {
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                }
                controller.imageCaptureFlashMode = flash
            }) {
                Icon(
                    when (flash) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                        ImageCapture.FLASH_MODE_OFF -> Icons.Filled.FlashOff
                        else -> Icons.Filled.FlashAuto
                    },
                    "Flash",
                    tint = Color.White,
                )
            }
        }

        // Bottom controls
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Last page thumbnail + count
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (replacePageId == null && lastThumb != null) {
                    Image(
                        lastThumb.asImageBitmap(), null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                            .clickable { done() },
                    )
                }
                if (vm.processingCount > 0) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
                }
            }

            // Shutter
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.Gray else Color.White)
                    .clickable(enabled = !capturing) { capture() },
            )

            // Done
            Box(Modifier.size(width = 96.dp, height = 56.dp), contentAlignment = Alignment.Center) {
                if (replacePageId == null) {
                    TextButton(onClick = { done() }) {
                        Text(if (shots > 0) "Done ($shots)" else "Done", color = Color.White)
                    }
                }
            }
        }
    }
}
