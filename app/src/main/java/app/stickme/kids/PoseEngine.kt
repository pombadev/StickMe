package app.stickme.kids

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * One person: [points] holds x,y pairs in upright-image pixels, [scores] one confidence per point.
 * [id] stays the same for the same person across frames once [PoseSmoother] has seen them.
 */
class Person(val box: RectF, val points: FloatArray, val scores: FloatArray, val id: Int = 0)

/** Whole-body (133 point) RTMPose models shipped with the app. All share the same input and output layout. */
enum class PoseModel(val asset: String, val label: String) {
    TINY("wholebody_pose_tiny.onnx", "🐇  Fast tracking"),
    MEDIUM("wholebody_pose_medium.onnx", "🐢  Detailed tracking"),
}

/**
 * Two-step RTMPose pipeline: RTMDet-nano finds people, then the chosen whole-body pose model
 * runs once on all found people as a single batch.
 */
class PoseEngine(context: Context, val model: PoseModel = PoseModel.TINY) : AutoCloseable {

    private companion object {
        const val DET_SIZE = 320
        const val DET_MIN_SCORE = 0.5f
        const val PERSON_LABEL = 0L
        const val MAX_PEOPLE = 4
        const val POSE_W = 192
        const val POSE_H = 256
        const val BOX_PADDING = 1.25f
        const val SIMCC_SPLIT = 2f
        const val SIMCC_X = (POSE_W * SIMCC_SPLIT).toInt()
        const val SIMCC_Y = (POSE_H * SIMCC_SPLIT).toInt()

        val DET_MEAN_BGR = floatArrayOf(103.53f, 116.28f, 123.675f)
        val DET_STD_BGR = floatArrayOf(57.375f, 57.12f, 58.395f)
        val POSE_MEAN_RGB = floatArrayOf(123.675f, 116.28f, 103.53f)
        val POSE_STD_RGB = floatArrayOf(58.395f, 57.12f, 57.375f)
    }

    private val env = OrtEnvironment.getEnvironment()
    private val detector = env.createSession(context.readAsset("person_detector.onnx"), sessionOptions())
    private val pose = env.createSession(context.readAsset(model.asset), sessionOptions())

    private val detBitmap = Bitmap.createBitmap(DET_SIZE, DET_SIZE, Bitmap.Config.ARGB_8888)
    private val detCanvas = Canvas(detBitmap)
    private val detPixels = IntArray(DET_SIZE * DET_SIZE)
    private val detInput = directFloats(3 * DET_SIZE * DET_SIZE)

    private val cropBitmap = Bitmap.createBitmap(POSE_W, POSE_H, Bitmap.Config.ARGB_8888)
    private val cropCanvas = Canvas(cropBitmap)
    private val cropPixels = IntArray(POSE_W * POSE_H)
    private val poseInput = directFloats(MAX_PEOPLE * 3 * POSE_W * POSE_H)

    private val smoothPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    fun findPeople(image: Bitmap): List<Person> {
        val boxes = detectPeople(image)
        if (boxes.isEmpty()) return emptyList()
        return estimatePoses(image, boxes)
    }

    private fun detectPeople(image: Bitmap): List<RectF> {
        val scale = min(DET_SIZE.toFloat() / image.width, DET_SIZE.toFloat() / image.height)
        detCanvas.drawColor(Color.rgb(114, 114, 114))
        matrix.setScale(scale, scale)
        detCanvas.drawBitmap(image, matrix, smoothPaint)
        detBitmap.getPixels(detPixels, 0, DET_SIZE, 0, 0, DET_SIZE, DET_SIZE)
        writePlanes(detPixels, detInput, 0, bgr = true, DET_MEAN_BGR, DET_STD_BGR)

        val shape = longArrayOf(1, 3, DET_SIZE.toLong(), DET_SIZE.toLong())
        OnnxTensor.createTensor(env, detInput.rewind() as FloatBuffer, shape).use { input ->
            detector.run(mapOf("input" to input)).use { result ->
                val dets = (result[0] as OnnxTensor).floatBuffer
                val labels = (result[1] as OnnxTensor).longBuffer
                val found = mutableListOf<Pair<RectF, Float>>()
                for (i in 0 until labels.remaining()) {
                    val score = dets[i * 5 + 4]
                    if (labels[i] != PERSON_LABEL || score < DET_MIN_SCORE) continue
                    val box = RectF(
                        dets[i * 5] / scale, dets[i * 5 + 1] / scale,
                        dets[i * 5 + 2] / scale, dets[i * 5 + 3] / scale,
                    )
                    if (box.width() > 8 && box.height() > 8) found += box to score
                }
                return found.sortedByDescending { it.second }.take(MAX_PEOPLE).map { it.first }
            }
        }
    }

    private class Crop(val centerX: Float, val centerY: Float, val pixelW: Float, val pixelH: Float)

    private fun estimatePoses(image: Bitmap, boxes: List<RectF>): List<Person> {
        val plane = POSE_W * POSE_H
        val crops = boxes.mapIndexed { i, box ->
            val crop = cropFor(box)
            matrix.reset()
            matrix.postTranslate(-crop.centerX, -crop.centerY)
            matrix.postScale(1f / crop.pixelW, 1f / crop.pixelH)
            matrix.postTranslate(POSE_W / 2f, POSE_H / 2f)
            cropCanvas.drawColor(Color.BLACK)
            cropCanvas.drawBitmap(image, matrix, smoothPaint)
            cropBitmap.getPixels(cropPixels, 0, POSE_W, 0, 0, POSE_W, POSE_H)
            writePlanes(cropPixels, poseInput, i * 3 * plane, bgr = false, POSE_MEAN_RGB, POSE_STD_RGB)
            crop
        }

        poseInput.rewind()
        poseInput.limit(boxes.size * 3 * plane)
        val shape = longArrayOf(boxes.size.toLong(), 3, POSE_H.toLong(), POSE_W.toLong())
        try {
            OnnxTensor.createTensor(env, poseInput, shape).use { input ->
                pose.run(mapOf("input" to input)).use { result ->
                    val simX = (result[0] as OnnxTensor).floatBuffer
                    val simY = (result[1] as OnnxTensor).floatBuffer
                    return boxes.indices.map { p -> decodePerson(boxes[p], crops[p], simX, simY, p) }
                }
            }
        } finally {
            poseInput.clear()
        }
    }

    /** Grow the box by [BOX_PADDING] and widen/heighten it to the model's 3:4 shape, as mmpose does. */
    private fun cropFor(box: RectF): Crop {
        var w = box.width() * BOX_PADDING
        var h = box.height() * BOX_PADDING
        if (w / h > POSE_W.toFloat() / POSE_H) h = w * POSE_H / POSE_W else w = h * POSE_W / POSE_H
        return Crop(box.centerX(), box.centerY(), w / POSE_W, h / POSE_H)
    }

    private fun decodePerson(box: RectF, crop: Crop, simX: FloatBuffer, simY: FloatBuffer, person: Int): Person {
        val count = Skeleton.POINT_COUNT
        val points = FloatArray(count * 2)
        val scores = FloatArray(count)
        for (k in 0 until count) {
            val (bestX, maxX) = argMax(simX, (person * count + k) * SIMCC_X, SIMCC_X)
            val (bestY, maxY) = argMax(simY, (person * count + k) * SIMCC_Y, SIMCC_Y)
            points[k * 2] = crop.centerX + (bestX / SIMCC_SPLIT - POSE_W / 2f) * crop.pixelW
            points[k * 2 + 1] = crop.centerY + (bestY / SIMCC_SPLIT - POSE_H / 2f) * crop.pixelH
            scores[k] = min(maxX, maxY)
        }
        return Person(box, points, scores)
    }

    private fun argMax(values: FloatBuffer, start: Int, length: Int): Pair<Int, Float> {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until length) {
            val v = values[start + i]
            if (v > bestValue) { bestValue = v; best = i }
        }
        return best to bestValue
    }

    /** Converts packed ARGB pixels into three normalized colour planes (NCHW layout). */
    private fun writePlanes(
        pixels: IntArray, out: FloatBuffer, offset: Int, bgr: Boolean, mean: FloatArray, std: FloatArray,
    ) {
        val plane = pixels.size
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            val first = if (bgr) b else r
            val third = if (bgr) r else b
            out.put(offset + i, (first - mean[0]) / std[0])
            out.put(offset + plane + i, (g - mean[1]) / std[1])
            out.put(offset + 2 * plane + i, (third - mean[2]) / std[2])
        }
    }

    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(4)
    }

    override fun close() {
        detector.close()
        pose.close()
    }
}

private fun Context.readAsset(name: String): ByteArray = assets.open(name).use { it.readBytes() }

private fun directFloats(count: Int): FloatBuffer =
    ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
