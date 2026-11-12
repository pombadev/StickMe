package app.stickme.kids

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class RecordingResult {
    class Saved(val uri: Uri) : RecordingResult()
    data object TooShort : RecordingResult()
    class Failed(val reason: String) : RecordingResult()
}

/**
 * Records the frames we draw ourselves (not the raw camera), so the video shows exactly what the kid sees:
 * stick figures, faces and, if switched on, the camera picture. Finished videos go to Movies/StickMe.
 */
class VideoRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var file: File? = null
    private var framesDrawn = 0
    private var startedAt = 0L

    var width = 0
        private set
    var height = 0
        private set

    @Volatile var isRecording = false
        private set

    val secondsSoFar: Long get() = if (isRecording) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0

    @Synchronized
    fun start(width: Int, height: Int, withSound: Boolean) {
        check(!isRecording) { "Already recording" }
        val output = File(context.cacheDir, "recording-${System.currentTimeMillis()}.mp4")
        @Suppress("DEPRECATION")
        val newRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            newRecorder.apply {
                if (withSound) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(output.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (withSound) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44_100)
                    setAudioEncodingBitRate(128_000)
                }
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6_000_000)
                prepare()
                start()
            }
        } catch (e: Exception) {
            newRecorder.release()
            output.delete()
            throw e
        }
        recorder = newRecorder
        surface = newRecorder.surface
        file = output
        this.width = width
        this.height = height
        framesDrawn = 0
        startedAt = SystemClock.elapsedRealtime()
        isRecording = true
    }

    @Synchronized
    fun drawFrame(draw: (Canvas) -> Unit) {
        val target = surface ?: return
        val canvas = target.lockHardwareCanvas()
        try {
            draw(canvas)
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
        framesDrawn++
    }

    /** Stops and copies the video into the Gallery. Slow-ish: call it off the main thread. */
    @Synchronized
    fun stopAndSave(): RecordingResult {
        val activeRecorder = recorder ?: return RecordingResult.Failed("Not recording")
        val output = file!!
        isRecording = false
        recorder = null
        surface = null
        file = null

        val stoppedCleanly = try {
            activeRecorder.stop()
            true
        } catch (e: RuntimeException) {
            false // MediaRecorder throws here when the video has no frames yet.
        } finally {
            activeRecorder.release()
        }

        return try {
            when {
                !stoppedCleanly || framesDrawn < 2 -> RecordingResult.TooShort
                else -> RecordingResult.Saved(copyToGallery(output))
            }
        } catch (e: Exception) {
            RecordingResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            output.delete()
        }
    }

    private fun copyToGallery(video: File): Uri {
        val name = "StickMe_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val details = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StickMe")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, details)
                ?: error("The Gallery refused the video")
            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "Could not write to the Gallery" }
                video.inputStream().use { it.copyTo(out) }
            }
            details.clear()
            details.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, details, null, null)
            return uri
        }

        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StickMe")
        folder.mkdirs()
        val target = File(folder, name)
        video.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf("video/mp4"), null)
        return Uri.fromFile(target)
    }
}
