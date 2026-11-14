package com.dualcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.Surface
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val stage: String, val percent: Int) : ExportState
    data class Done(val result: VideoExporter.Result) : ExportState
    data class Error(val message: String) : ExportState
}

/** 16:9 frame me 9:16 box ki width (frame width ka hissa) = (9/16) * (9/16) */
private const val PORTRAIT_FRAC = 81f / 256f

@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var useBack by remember { mutableStateOf(true) }
    var filterIndex by remember { mutableIntStateOf(0) }
    var portraitCenter by remember { mutableFloatStateOf(0.5f) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    val previewView = remember {
        PreviewView(ctx).apply {
            // COMPATIBLE = TextureView, jispe live filter (color matrix) lag sakta hai
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    // ---- Camera bind (back/front badalne pe dobara) ----
    LaunchedEffect(useBack) {
        val provider = ctx.cameraProvider()

        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            )
            .build()
        val vc = VideoCapture.withOutput(recorder)

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            if (useBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            vc
        )
        preview.setSurfaceProvider(previewView.surfaceProvider)
        videoCapture = vc
    }

    DisposableEffect(Unit) { onDispose { recording?.stop() } }

    // ---- Export ----
    fun runExport(file: File, filter: Filter, center: Float) {
        scope.launch {
            exportState = ExportState.Working("Start ho raha hai", 0)
            try {
                val res = VideoExporter.exportBoth(ctx, file, filter, center) { stage, pct ->
                    exportState = ExportState.Working(stage, pct)
                }
                exportState = ExportState.Done(res)
            } catch (e: Exception) {
                exportState = ExportState.Error(e.message ?: e.toString())
            }
        }
    }

    // ---- Record ----
    @SuppressLint("MissingPermission")
    fun startRecording() {
        val vc = videoCapture ?: return
        vc.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_90

        val file = File(ctx.cacheDir, "raw_${System.currentTimeMillis()}.mp4")
        val options = FileOutputOptions.Builder(file).build()
        val filter = Filters.all[filterIndex]
        val center = portraitCenter
        seconds = 0

        recording = vc.output
            .prepareRecording(ctx, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        seconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()

                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        if (event.hasError()) {
                            file.delete()
                            exportState = ExportState.Error("Recording error code ${event.error}")
                        } else {
                            runExport(file, filter, center)
                        }
                    }

                    else -> Unit
                }
            }
    }

    val busy = recording != null || exportState !is ExportState.Idle

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // 1) Live preview (+ filter)
        AndroidView(
            factory = { previewView },
            update = { applyPreviewFilter(it, Filters.all[filterIndex]) },
            modifier = Modifier.fillMaxSize()
        )

        // 2) 16:9 frame + draggable 9:16 box
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        val frameW = minOf(size.width.toFloat(), size.height * 16f / 9f)
                        val half = PORTRAIT_FRAC / 2f
                        portraitCenter = (portraitCenter + dx / frameW).coerceIn(half, 1f - half)
                    }
                }
        ) { drawOverlay(portraitCenter) }

        // 3) Top hint / timer
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (recording != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Red))
                Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Color.White)
            } else {
                Text("Portrait (9:16) box ko left/right drag karo", color = Color.White.copy(alpha = 0.8f))
            }
        }

        // 4) Right side: flip + record
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            FilledTonalButton(onClick = { useBack = !useBack }, enabled = !busy) { Text("Flip") }

            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(enabled = exportState is ExportState.Idle) {
                        if (recording == null) startRecording() else recording?.stop()
                    },
                contentAlignment = Alignment.Center
            ) {
                val isRec = recording != null
                Box(
                    Modifier
                        .size(if (isRec) 30.dp else 58.dp)
                        .background(Color.Red, if (isRec) RoundedCornerShape(6.dp) else CircleShape)
                )
            }
        }

        // 5) Bottom: filter chips
        LazyRow(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 140.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(Filters.all) { i, f ->
                FilterChip(
                    selected = i == filterIndex,
                    onClick = { filterIndex = i },
                    enabled = !busy,
                    label = { Text(f.name) }
                )
            }
        }

        // 6) Export progress / result
        ExportOverlay(
            state = exportState,
            onClose = { exportState = ExportState.Idle },
            onOpen = { openVideo(ctx, it) }
        )
    }
}

// ---------------- helpers ----------------

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}

private fun applyPreviewFilter(v: View, f: Filter) {
    if (f.isIdentity) {
        v.setLayerType(View.LAYER_TYPE_NONE, null)
        return
    }
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(f.matrix)) }
    v.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
}

private fun DrawScope.drawOverlay(center: Float) {
    val fw = minOf(size.width, size.height * 16f / 9f)
    val fh = fw * 9f / 16f
    val left = (size.width - fw) / 2f
    val top = (size.height - fh) / 2f

    val pw = fw * PORTRAIT_FRAC
    val px = left + center * fw - pw / 2f

    // portrait box ke bahar ka hissa halka dim
    val dim = Color.Black.copy(alpha = 0.45f)
    drawRect(dim, Offset(left, top), Size(px - left, fh))
    drawRect(dim, Offset(px + pw, top), Size(left + fw - (px + pw), fh))

    // 16:9 frame
    drawRect(Color.White.copy(alpha = 0.5f), Offset(left, top), Size(fw, fh), style = Stroke(2f))
    // 9:16 box
    drawRect(Color.White, Offset(px, top), Size(pw, fh), style = Stroke(4f))

    // rule of thirds (portrait box ke andar)
    val grid = Color.White.copy(alpha = 0.35f)
    for (i in 1..2) {
        val x = px + pw * i / 3f
        drawLine(grid, Offset(x, top), Offset(x, top + fh), 1.5f)
        val y = top + fh * i / 3f
        drawLine(grid, Offset(px, y), Offset(px + pw, y), 1.5f)
    }
}

private fun openVideo(ctx: Context, uri: Uri) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        // koi video player nahi mila — gallery me file dekh lo
    }
}

@Composable
private fun ExportOverlay(state: ExportState, onClose: () -> Unit, onOpen: (Uri) -> Unit) {
    if (state is ExportState.Idle) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.width(380.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is ExportState.Working -> {
                        Text("Export ho raha hai: ${state.stage}")
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${state.percent}%")
                    }

                    is ExportState.Done -> {
                        Text("Ho gaya! Dono videos Movies/DualCam me save hain.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpen(state.result.landscape) }) { Text("16:9 dekho") }
                            Button(onClick = { onOpen(state.result.portrait) }) { Text("9:16 dekho") }
                        }
                        TextButton(onClick = onClose) { Text("Band karo") }
                    }

                    is ExportState.Error -> {
                        Text("Error: ${state.message}")
                        TextButton(onClick = onClose) { Text("Band karo") }
                    }

                    ExportState.Idle -> Unit
                }
            }
        }
    }
}
