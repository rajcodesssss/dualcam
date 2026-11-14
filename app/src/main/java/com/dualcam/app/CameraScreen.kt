package com.dualcam.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Look & feel
// ---------------------------------------------------------------------------

private val Accent = Color(0xFFD6FF3F)                       // electric lime
private val RecRed = Color(0xFFFF3B30)
private val Glass = Color(0xFF101010).copy(alpha = 0.55f)     // translucent dark panels
private val GlassStrong = Color(0xFF141414).copy(alpha = 0.94f)
private val Hairline = Color.White.copy(alpha = 0.14f)

/** 16:9 frame me 9:16 box ki width (frame width ka hissa) = (9/16) * (9/16) */
private const val PORTRAIT_FRAC = 81f / 256f

private sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val stage: String, val percent: Int) : ExportState
    data class Done(val result: VideoExporter.Result) : ExportState
    data class Error(val message: String) : ExportState
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var useBack by remember { mutableStateOf(true) }
    var filterIndex by remember { mutableIntStateOf(0) }
    var portraitCenter by remember { mutableFloatStateOf(0.5f) }
    var dragging by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(true) }
    var toastKey by remember { mutableIntStateOf(0) }
    var toastVisible by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var lastPortrait by remember { mutableStateOf<Uri?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    val previewView = remember {
        PreviewView(ctx).apply {
            // COMPATIBLE = TextureView, jispe live filter (color matrix) lag sakta hai
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            keepScreenOn = true
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

    // hint kuch der baad apne aap chhup jaata hai
    LaunchedEffect(Unit) {
        delay(5000)
        hintVisible = false
    }
    // filter ka naam thodi der ke liye beech me dikhta hai
    LaunchedEffect(toastKey) {
        if (toastKey > 0) {
            toastVisible = true
            delay(900)
            toastVisible = false
        }
    }

    // ---- Export ----
    fun runExport(file: File, filter: Filter, center: Float) {
        scope.launch {
            exportState = ExportState.Working("Landscape 16:9", 0)
            try {
                val res = VideoExporter.exportBoth(ctx, file, filter, center) { stage, pct ->
                    exportState = ExportState.Working(stage, pct)
                }
                lastPortrait = res.portrait
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
                            exportState = ExportState.Error("Recording failed (code ${event.error}).")
                        } else {
                            runExport(file, filter, center)
                        }
                    }

                    else -> Unit
                }
            }
    }

    val isRec = recording != null
    val busy = isRec || exportState !is ExportState.Idle

    val dragGlow by animateFloatAsState(if (dragging) 1f else 0f, tween(180), label = "dragGlow")
    val hintAlpha by animateFloatAsState(if (hintVisible && !isRec) 1f else 0f, tween(300), label = "hint")
    val toastAlpha by animateFloatAsState(if (toastVisible) 1f else 0f, tween(200), label = "toast")
    val stripAlpha by animateFloatAsState(if (busy) 0.35f else 1f, tween(200), label = "strip")

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
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            hintVisible = false
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            val frameW = minOf(size.width.toFloat(), size.height * 16f / 9f)
                            val half = PORTRAIT_FRAC / 2f
                            portraitCenter = (portraitCenter + dx / frameW).coerceIn(half, 1f - half)
                        }
                    )
                }
        ) { drawOverlay(portraitCenter, dragGlow) }

        // 3) Controls (notch se bachake)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {

            // top: recording timer ya hint
            Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp)) {
                if (isRec) {
                    RecordingPill(seconds)
                } else if (hintAlpha > 0.01f) {
                    Pill(Modifier.graphicsLayer { alpha = hintAlpha }) {
                        Text(
                            "Drag the frame to reframe your portrait",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // center: filter naam
            if (toastAlpha > 0.01f) {
                Text(
                    Filters.all[filterIndex].name,
                    modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = toastAlpha },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // right rail: flip, record, last shot
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                GlassButton(enabled = !busy, onClick = { useBack = !useBack }) { FlipIcon() }

                RecordButton(
                    isRec = isRec,
                    enabled = exportState is ExportState.Idle,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (recording == null) startRecording() else recording?.stop()
                    }
                )

                LastShot(lastPortrait) { openVideo(ctx, it) }
            }

            // bottom: filters
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 140.dp, bottom = 12.dp)
                    .widthIn(max = 560.dp)
                    .graphicsLayer { alpha = stripAlpha }
                    .clip(RoundedCornerShape(24.dp))
                    .background(Glass)
                    .border(1.dp, Hairline, RoundedCornerShape(24.dp))
            ) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(Filters.all) { i, f ->
                        FilterSwatch(f, selected = i == filterIndex) {
                            if (!busy && i != filterIndex) {
                                filterIndex = i
                                toastKey++
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                }
            }
        }

        // 4) Export progress / result
        ExportOverlay(
            state = exportState,
            onClose = { exportState = ExportState.Idle },
            onOpen = { openVideo(ctx, it) }
        )
    }
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

/** Press pe instantly halka sa shrink, chhodne pe spring se wapas. */
private fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "press"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Glass)
            .border(1.dp, Hairline, shape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
private fun RecordingPill(seconds: Int) {
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Pill {
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer { alpha = pulse }
                .clip(CircleShape)
                .background(RecRed)
        )
        Text(
            "%02d:%02d".format(seconds / 60, seconds % 60),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun GlassButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .pressable(enabled, onClick)
            .size(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, Hairline, CircleShape),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun FlipIcon() {
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        val pad = 3.dp.toPx()
        val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = arcSize.width / 2f
        val arrow = 3.6.dp.toPx()

        drawArc(Color.White, 200f, 140f, false, Offset(pad, pad), arcSize, style = stroke)
        drawArc(Color.White, 20f, 140f, false, Offset(pad, pad), arcSize, style = stroke)

        fun head(angleDeg: Float) {
            val a = Math.toRadians(angleDeg.toDouble())
            val p = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            val tangent = Offset(-sin(a).toFloat(), cos(a).toFloat())
            val normal = Offset(cos(a).toFloat(), sin(a).toFloat())
            val path = Path().apply {
                moveTo(p.x + tangent.x * arrow * 1.3f, p.y + tangent.y * arrow * 1.3f)
                lineTo(p.x + normal.x * arrow, p.y + normal.y * arrow)
                lineTo(p.x - normal.x * arrow, p.y - normal.y * arrow)
                close()
            }
            drawPath(path, Color.White)
        }
        head(340f)
        head(160f)
    }
}

@Composable
private fun RecordButton(isRec: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t by animateFloatAsState(
        if (isRec) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "rec"
    )
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0.1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ringPulse"
    )
    val ring = if (isRec) lerp(Color.White, RecRed, pulse) else Color.White

    Box(
        Modifier
            .pressable(enabled, onClick)
            .size(84.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .border(4.dp, ring, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size((62f - 32f * t).dp)
                .clip(RoundedCornerShape(percent = (50f - 34f * t).toInt()))
                .background(RecRed)
        )
    }
}

@Composable
private fun LastShot(uri: Uri?, onOpen: (Uri) -> Unit) {
    if (uri == null) {
        Spacer(Modifier.size(48.dp))
        return
    }
    val thumb = rememberThumb(uri)
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .pressable { onOpen(uri) }
            .size(48.dp)
            .clip(shape)
            .background(Glass)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape)
    ) {
        thumb?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

// ---------------------------------------------------------------------------
// Filter swatches — har filter ke 4 sample rang (skin, sky, leaves, pink) us filter se guzar ke
// ---------------------------------------------------------------------------

private val SAMPLES = intArrayOf(0xE0AC69, 0x5CA0E6, 0x469650, 0xE66E78)

private fun swatchColor(m: FloatArray, rgb: Int): Color {
    val r = ((rgb shr 16) and 0xFF).toFloat()
    val g = ((rgb shr 8) and 0xFF).toFloat()
    val b = (rgb and 0xFF).toFloat()
    fun ch(a: Float, bb: Float, c: Float, off: Float) = (a * r + bb * g + c * b + off).coerceIn(0f, 255f) / 255f
    return Color(
        ch(m[0], m[1], m[2], m[4]),
        ch(m[5], m[6], m[7], m[9]),
        ch(m[10], m[11], m[12], m[14])
    )
}

@Composable
private fun FilterSwatch(f: Filter, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Canvas(
            Modifier
                .size(52.dp)
                .clip(shape)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) Accent else Hairline, shape)
        ) {
            val w = size.width / 2f
            val h = size.height / 2f
            SAMPLES.forEachIndexed { i, rgb ->
                drawRect(swatchColor(f.matrix, rgb), Offset((i % 2) * w, (i / 2) * h), Size(w, h))
            }
        }
        Text(
            f.name,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Accent else Color.White.copy(alpha = 0.75f),
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Overlay drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawOverlay(center: Float, active: Float) {
    val fw = minOf(size.width, size.height * 16f / 9f)
    val fh = fw * 9f / 16f
    val left = (size.width - fw) / 2f
    val top = (size.height - fh) / 2f

    val pw = fw * PORTRAIT_FRAC
    val px = left + center * fw - pw / 2f

    // portrait box ke bahar ka hissa dim
    val dim = Color.Black.copy(alpha = 0.5f)
    drawRect(dim, Offset(left, top), Size(px - left, fh))
    drawRect(dim, Offset(px + pw, top), Size(left + fw - (px + pw), fh))

    // 16:9 frame
    drawRect(Color.White.copy(alpha = 0.28f), Offset(left, top), Size(fw, fh), style = Stroke(1.dp.toPx()))

    // rule of thirds (portrait box ke andar)
    val grid = Color.White.copy(alpha = 0.16f + 0.14f * active)
    for (i in 1..2) {
        val x = px + pw * i / 3f
        drawLine(grid, Offset(x, top), Offset(x, top + fh), 1.dp.toPx())
        val y = top + fh * i / 3f
        drawLine(grid, Offset(px, y), Offset(px + pw, y), 1.dp.toPx())
    }

    // accent corner brackets
    val arm = 22.dp.toPx()
    val sw = 3.dp.toPx()
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(Accent, Offset(x, y), Offset(x + dx * arm, y), sw, StrokeCap.Round)
        drawLine(Accent, Offset(x, y), Offset(x, y + dy * arm), sw, StrokeCap.Round)
    }
    corner(px, top, 1f, 1f)
    corner(px + pw, top, -1f, 1f)
    corner(px, top + fh, 1f, -1f)
    corner(px + pw, top + fh, -1f, -1f)

    // drag handles (left/right edge ke beech me)
    val hw = (3f + 2f * active).dp.toPx()
    val hh = (28f + 10f * active).dp.toPx()
    val cy = top + fh / 2f
    for (x in listOf(px, px + pw)) {
        drawRoundRect(Accent, Offset(x - hw / 2f, cy - hh / 2f), Size(hw, hh), CornerRadius(hw / 2f))
    }

    // labels
    drawIntoCanvas { c ->
        val p = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 12.sp.toPx()
        }
        p.color = Accent.toArgb()
        c.nativeCanvas.drawText("9:16", px + 12.dp.toPx(), top + 30.dp.toPx(), p)

        p.color = android.graphics.Color.argb(170, 255, 255, 255)
        val label = "16:9"
        val tw = p.measureText(label)
        val lx = if (center > 0.35f) left + 10.dp.toPx() else left + fw - tw - 10.dp.toPx()
        c.nativeCanvas.drawText(label, lx, top + 22.dp.toPx(), p)
    }
}

// ---------------------------------------------------------------------------
// Export overlay
// ---------------------------------------------------------------------------

@Composable
private fun ExportOverlay(state: ExportState, onClose: () -> Unit, onOpen: (Uri) -> Unit) {
    if (state is ExportState.Idle) return

    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .pointerInput(Unit) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(16.dp)
                .clip(shape)
                .background(GlassStrong)
                .border(1.dp, Hairline, shape)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (state) {
                is ExportState.Working -> {
                    val landscapeDone = !state.stage.startsWith("Landscape")
                    Text("Making your videos", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Keep the app open for a moment.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    ProgressRow("Landscape 16:9", if (landscapeDone) 100 else state.percent)
                    ProgressRow("Portrait 9:16", if (landscapeDone) state.percent else 0)
                }

                is ExportState.Done -> {
                    Text("Saved to your gallery", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.Top
                    ) {
                        ResultCard("Landscape 16:9", state.result.landscape, 16f / 9f, onOpen)
                        ResultCard("Portrait 9:16", state.result.portrait, 9f / 16f, onOpen)
                    }
                    Text("Movies / DualCam", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    AccentButton("Done", onClose)
                }

                is ExportState.Error -> {
                    Text("Something went wrong", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    AccentButton("Close", onClose)
                }

                ExportState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String, percent: Int) {
    val fraction by animateFloatAsState(percent / 100f, tween(250), label = "bar")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp)
            Text(
                "$percent%",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Accent))
        }
    }
}

@Composable
private fun ResultCard(label: String, uri: Uri, ratio: Float, onOpen: (Uri) -> Unit) {
    val thumb = rememberThumb(uri)
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .pressable { onOpen(uri) }
                .height(110.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Hairline, shape)
        ) {
            thumb?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Canvas(Modifier.align(Alignment.Center).size(30.dp)) {
                drawCircle(Color.Black.copy(alpha = 0.5f))
                val s = size.width
                val tri = Path().apply {
                    moveTo(s * 0.4f, s * 0.3f)
                    lineTo(s * 0.72f, s * 0.5f)
                    lineTo(s * 0.4f, s * 0.7f)
                    close()
                }
                drawPath(tri, Color.White)
            }
        }
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
    }
}

@Composable
private fun AccentButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(Accent)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun rememberThumb(uri: Uri): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.loadThumbnail(uri, android.util.Size(480, 480), null).asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

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
