package ch.honnobilis.pdfcreator.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlin.math.max
import kotlin.math.min

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Image with pinch-to-zoom, panning and double-tap zoom.
 * At 1× single-finger drags are left alone, so a surrounding pager can still swipe.
 * [onScaleChange] reports the current zoom (1 = not zoomed).
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    contentDescription: String?,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val report by rememberUpdatedState(onScaleChange)
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // Aspect ratio of the content; stays the same when a sharper bitmap replaces a smaller one.
    val aspect = bitmap.width.toFloat() / bitmap.height

    DisposableEffect(Unit) { onDispose { report(1f) } }

    BoxWithConstraints(modifier.clipToBounds()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // Size of the image on screen at 1× (ContentScale.Fit).
        val fitW = min(boxW, boxH * aspect)
        val fitH = min(boxH, boxW / aspect)
        val center = Offset(boxW / 2, boxH / 2)

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = max(0f, (fitW * s - boxW) / 2)
            val maxY = max(0f, (fitH * s - boxH) / 2)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        fun set(s: Float, o: Offset) {
            scale = s
            offset = clamp(o, s)
            report(s)
        }

        Image(
            image,
            contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(boxW, boxH, aspect) {
                    detectTapGestures(
                        onDoubleTap = { p ->
                            if (scale > 1.01f) {
                                set(1f, Offset.Zero)
                            } else {
                                val c = p - center
                                set(DOUBLE_TAP_ZOOM, c - c * DOUBLE_TAP_ZOOM)
                            }
                        },
                    )
                }
                .pointerInput(boxW, boxH, aspect) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid(useCurrent = true) - center
                                val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                // Keep the content under the fingers in place while zooming.
                                set(newScale, c - (c - offset) * (newScale / scale) + pan)
                                event.changes.forEach { it.consume() }
                            } else if (scale > 1.01f) {
                                val pan = event.calculatePan()
                                set(scale, offset + pan)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (scale < 1.02f) set(1f, Offset.Zero)
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
