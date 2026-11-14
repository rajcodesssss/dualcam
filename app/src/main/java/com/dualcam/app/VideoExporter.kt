@file:OptIn(UnstableApi::class)

package com.dualcam.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Ek raw 16:9 recording se 2 videos banata hai:
 *   - 16:9 landscape (1920x1080)
 *   - 9:16 portrait  (1080x1920), crop window user ke chune hue center pe
 * Dono pe same filter (3D LUT) lagta hai. Final files Movies/DualCam me save hoti hain.
 */
object VideoExporter {

    data class Result(val landscape: Uri, val portrait: Uri)

    suspend fun exportBoth(
        ctx: Context,
        source: File,
        filter: Filter,
        portraitCenter: Float,
        onProgress: (stage: String, percent: Int) -> Unit
    ): Result = withContext(Dispatchers.Main) {
        val (w, h) = uprightSize(source)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        fun lutEffect(): Effect? =
            if (filter.isIdentity) null
            else SingleColorLut.createFromCube(Filters.buildCube(filter))

        val landscapeEffects = buildList<Effect> {
            landscapeCrop(w, h)?.let { add(it) }
            add(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT))
            lutEffect()?.let { add(it) }
        }
        val portraitEffects = buildList<Effect> {
            add(portraitCrop(w, h, portraitCenter))
            add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT))
            lutEffect()?.let { add(it) }
        }

        val tmpL = File(ctx.cacheDir, "land_$stamp.mp4")
        val tmpP = File(ctx.cacheDir, "port_$stamp.mp4")

        runTransformer(ctx, source, tmpL, landscapeEffects) { onProgress("Landscape 16:9", it) }
        runTransformer(ctx, source, tmpP, portraitEffects) { onProgress("Portrait 9:16", it) }

        val result = withContext(Dispatchers.IO) {
            Result(
                landscape = saveToGallery(ctx, tmpL, "DualCam_${stamp}_16x9.mp4"),
                portrait = saveToGallery(ctx, tmpP, "DualCam_${stamp}_9x16.mp4")
            )
        }
        tmpL.delete(); tmpP.delete(); source.delete()
        result
    }

    // ---------- crop math (NDC: -1..1) ----------

    private fun landscapeCrop(w: Int, h: Int): Crop? {
        val target = 16f / 9f
        val cur = w.toFloat() / h
        return when {
            abs(cur - target) < 0.01f -> null
            cur > target -> { val f = target / cur; Crop(-f, f, -1f, 1f) }
            else -> { val f = cur / target; Crop(-1f, 1f, -f, f) }
        }
    }

    /** center: 0..1, portrait window ka center frame ki width me. */
    private fun portraitCrop(w: Int, h: Int, center: Float): Crop {
        val target = 9f / 16f
        val cur = w.toFloat() / h
        if (cur <= target) {
            val f = cur / target
            return Crop(-1f, 1f, -f, f)
        }
        val frac = target / cur                       // 16:9 source -> ~0.316 of width
        val c = (center * 2f - 1f).coerceIn(-(1f - frac), 1f - frac)
        return Crop(c - frac, c + frac, -1f, 1f)
    }

    /** Rotation metadata ke baad ka (upright) size. */
    private fun uprightSize(file: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            return if (rot == 90 || rot == 270) h to w else w to h
        } finally {
            r.release()
        }
    }

    // ---------- Transformer ----------

    private suspend fun runTransformer(
        ctx: Context,
        input: File,
        output: File,
        effects: List<Effect>,
        onProgress: (Int) -> Unit
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val holder = ProgressHolder()
        lateinit var transformer: Transformer

        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(this, 250)
            }
        }

        transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished = true
                    onProgress(100)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finished = true
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()

        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), effects))
            .build()

        transformer.start(item, output.absolutePath)
        handler.post(poll)
        cont.invokeOnCancellation {
            finished = true
            handler.post { transformer.cancel() }
        }
    }

    // ---------- Gallery ----------

    private fun saveToGallery(ctx: Context, file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DualCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
