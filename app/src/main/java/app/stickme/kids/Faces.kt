package app.stickme.kids

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What goes on each person's head. [button] is what the picker shows. */
sealed class FaceStyle(val button: String) {
    /** The white outline face from the whole-body model, as in the original demo video. */
    data object Lines : FaceStyle("✏️")

    /** A different random face for every person; picking it again rolls new faces. */
    data object Mix : FaceStyle("🎲")

    /** Drawn faces that open their mouth and eyes along with the real person. */
    class Live(button: String, val draw: (Canvas, FacePose) -> Unit) : FaceStyle(button)

    class Emoji(val emoji: String) : FaceStyle(emoji)
}

/**
 * Every face the picker offers. Kept outside [FaceStyle] on purpose: building this list inside
 * FaceStyle's own companion runs before [FaceStyle.Lines] and [FaceStyle.Mix] exist, leaving nulls in it.
 */
object FaceCatalog {
        private val live = listOf(
            FaceStyle.Live("🙂", LiveFaces::smiley),
            FaceStyle.Live("🤖", LiveFaces::robot),
            FaceStyle.Live("🐱", LiveFaces::cat),
            FaceStyle.Live("👽", LiveFaces::alien),
            FaceStyle.Live("👾", LiveFaces::monster),
        )

        private val emoji = listOf(
            "😎", "🤪", "🥳", "😂", "🤩", "😜", "😱", "😍", "🐶", "🐵", "🐸", "🐼", "🦁",
            "🐯", "🐷", "🐰", "🦄", "🐻", "🐨", "🐙", "🤡", "👻", "💀", "🎃",
        ).filter(EmojiImages::canShow).map { FaceStyle.Emoji(it) }

        val all: List<FaceStyle> = listOf(FaceStyle.Lines, FaceStyle.Mix) + live + emoji

        /** Faces handed out in [FaceStyle.Mix]: person ids are consecutive, so neighbours never share a face. */
        @Volatile private var mixPool: List<FaceStyle> = (live + emoji).shuffled()

        fun forMix(personId: Int): FaceStyle = mixPool.let { it[personId % it.size] }

        /** Hands every person a new face, never the one they had a moment ago. */
        fun rollNewMix() {
            val old = mixPool
            var next = old.shuffled()
            while (next.indices.any { next[it] == old[it] }) next = old.shuffled()
            mixPool = next
        }
}

/**
 * Where a face sits on screen. [angleDegrees] is the head tilt; openness values go from 0 (closed) to 1 (wide open).
 * "Left" and "right" are as seen on screen.
 */
class FacePose(
    val centerX: Float, val centerY: Float, val size: Float, val angleDegrees: Float,
    val mouthOpen: Float, val leftEyeOpen: Float, val rightEyeOpen: Float,
) {
    companion object {
        private const val FACE = 23
        private const val MIN_SCORE = 0.3f

        fun of(person: Person, toScreenX: (Float) -> Float, toScreenY: (Float) -> Float): FacePose? {
            fun ok(k: Int) = person.scores[k] > MIN_SCORE
            fun x(k: Int) = toScreenX(person.points[k * 2])
            fun y(k: Int) = toScreenY(person.points[k * 2 + 1])
            fun dist(a: Int, b: Int) = hypot(x(a) - x(b), y(a) - y(b))
            fun f(i: Int) = FACE + i

            val faceAnchors = listOf(0, 8, 16, 19, 24, 36, 39, 42, 45).map(::f)
            if (faceAnchors.all(::ok)) {
                val eyeAx = (f(36)..f(41)).map(::x).average().toFloat()
                val eyeAy = (f(36)..f(41)).map(::y).average().toFloat()
                val eyeBx = (f(42)..f(47)).map(::x).average().toFloat()
                val eyeBy = (f(42)..f(47)).map(::y).average().toFloat()
                val aIsLeft = eyeAx <= eyeBx
                val angle = if (aIsLeft) atan2(eyeBy - eyeAy, eyeBx - eyeAx) else atan2(eyeAy - eyeBy, eyeAx - eyeBx)

                val jawWidth = dist(f(0), f(16))
                val browX = (x(f(19)) + x(f(24))) / 2
                val browY = (y(f(19)) + y(f(24))) / 2
                // Halfway between brows and chin, nudged towards the top of the head to cover the forehead.
                val lift = jawWidth * 0.22f
                val centerX = (browX + x(f(8))) / 2 + sin(angle) * lift
                val centerY = (browY + y(f(8))) / 2 - cos(angle) * lift

                val mouth = if (ok(f(62)) && ok(f(66))) dist(f(62), f(66)) / jawWidth / 0.18f else 0f
                fun eyeOpen(corner1: Int, corner2: Int, top1: Int, bottom1: Int, top2: Int, bottom2: Int): Float {
                    val ratio = (dist(f(top1), f(bottom1)) + dist(f(top2), f(bottom2))) / (2 * dist(f(corner1), f(corner2)))
                    return ((ratio - 0.12f) / 0.14f).coerceIn(0.1f, 1f)
                }
                val eyeA = eyeOpen(36, 39, 37, 41, 38, 40)
                val eyeB = eyeOpen(42, 45, 43, 47, 44, 46)
                return FacePose(
                    centerX, centerY, jawWidth * 1.6f, Math.toDegrees(angle.toDouble()).toFloat(),
                    mouth.coerceIn(0f, 1f),
                    leftEyeOpen = if (aIsLeft) eyeA else eyeB,
                    rightEyeOpen = if (aIsLeft) eyeB else eyeA,
                )
            }

            // Face too small or turned away: fall back to the body's nose, eyes and ears.
            if (!ok(0) || !ok(1) || !ok(2)) return null
            val (left, right) = if (x(1) <= x(2)) 1 to 2 else 2 to 1
            val angle = atan2(y(right) - y(left), x(right) - x(left))
            val headWidth = if (ok(3) && ok(4)) dist(3, 4) else dist(1, 2) * 2.5f
            val size = headWidth * 1.5f
            return FacePose(
                x(0) + sin(angle) * size * 0.08f, y(0) - cos(angle) * size * 0.08f, size,
                Math.toDegrees(angle.toDouble()).toFloat(), 0f, 1f, 1f,
            )
        }
    }
}

object FaceDrawing {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, style: FaceStyle, face: FacePose) {
        canvas.save()
        canvas.translate(face.centerX, face.centerY)
        canvas.rotate(face.angleDegrees)
        when (style) {
            is FaceStyle.Live -> {
                // Live faces are drawn on a 100 x 100 box centred on the face.
                val scale = face.size / 100f
                canvas.scale(scale, scale)
                style.draw(canvas, face)
            }
            is FaceStyle.Emoji -> {
                val half = face.size * 0.6f * (1 + 0.15f * face.mouthOpen)
                canvas.drawBitmap(EmojiImages.get(style.emoji), null, RectF(-half, -half, half, half), bitmapPaint)
            }
            FaceStyle.Lines, FaceStyle.Mix -> Unit
        }
        canvas.restore()
    }
}

/** Emoji pre-drawn into pictures once, because very large emoji text draws unreliably on some phones. */
object EmojiImages {
    private const val SIZE = 192
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = SIZE * 0.82f
        textAlign = Paint.Align.CENTER
    }

    fun canShow(emoji: String): Boolean = textPaint.hasGlyph(emoji)

    fun get(emoji: String): Bitmap = cache.getOrPut(emoji) {
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
            synchronized(textPaint) {
                val metrics = textPaint.fontMetrics
                val baseline = SIZE / 2f - (metrics.ascent + metrics.descent) / 2
                Canvas(bitmap).drawText(emoji, SIZE / 2f, baseline, textPaint)
            }
        }
    }
}

/** Faces drawn in a 100 x 100 box centred on (0, 0), reacting to the person's mouth and eyes. */
private object LiveFaces {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private fun Canvas.oval(cx: Float, cy: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        drawOval(rect, fill)
    }

    private fun outline(color: Int, width: Float): Paint = stroke.apply { this.color = color; strokeWidth = width }

    @Synchronized
    fun smiley(canvas: Canvas, face: FacePose) = with(canvas) {
        val ink = Color.rgb(70, 45, 0)
        oval(0f, 0f, 100f, 100f, Color.rgb(255, 217, 59))
        drawCircle(0f, 0f, 50f, outline(ink, 3f))
        oval(-30f, 14f, 16f, 10f, Color.argb(120, 255, 110, 110))
        oval(30f, 14f, 16f, 10f, Color.argb(120, 255, 110, 110))
        oval(-17f, -12f, 10f, maxOf(3f, 18f * face.leftEyeOpen), ink)
        oval(17f, -12f, 10f, maxOf(3f, 18f * face.rightEyeOpen), ink)
        if (face.mouthOpen < 0.15f) {
            rect.set(-24f, -4f, 24f, 30f)
            drawArc(rect, 25f, 130f, false, outline(ink, 4f))
        } else {
            val bottom = 18f + 32f * face.mouthOpen
            oval(0f, (12f + bottom) / 2, 36f, bottom - 12f, Color.rgb(110, 25, 10))
            oval(0f, bottom - 6f, 18f, 10f, Color.rgb(255, 105, 130))
        }
    }

    @Synchronized
    fun robot(canvas: Canvas, face: FacePose) = with(canvas) {
        val ink = Color.rgb(38, 50, 56)
        drawLine(0f, -44f, 0f, -60f, outline(ink, 3f))
        oval(0f, -63f, 12f, 12f, Color.rgb(255, 23, 68))
        fill.color = Color.rgb(120, 144, 156)
        drawRect(-53f, -12f, -44f, 12f, fill)
        drawRect(44f, -12f, 53f, 12f, fill)
        fill.color = Color.rgb(176, 190, 197)
        rect.set(-45f, -45f, 45f, 48f)
        drawRoundRect(rect, 12f, 12f, fill)
        drawRoundRect(rect, 12f, 12f, outline(ink, 3f))
        fill.color = Color.rgb(0, 229, 255)
        val leftH = maxOf(3f, 16f * face.leftEyeOpen)
        val rightH = maxOf(3f, 16f * face.rightEyeOpen)
        drawRect(-27f, -12f - leftH / 2, -9f, -12f + leftH / 2, fill)
        drawRect(9f, -12f - rightH / 2, 27f, -12f + rightH / 2, fill)
        val mouthBottom = 22f + 20f * face.mouthOpen
        fill.color = ink
        rect.set(-24f, 14f, 24f, mouthBottom)
        drawRoundRect(rect, 4f, 4f, fill)
        val teeth = outline(Color.WHITE, 2f)
        for (x in listOf(-12f, 0f, 12f)) drawLine(x, 15f, x, mouthBottom - 1f, teeth)
    }

    @Synchronized
    fun cat(canvas: Canvas, face: FacePose) = with(canvas) {
        val fur = Color.rgb(255, 152, 0)
        val ink = Color.rgb(80, 40, 0)
        for (side in listOf(-1f, 1f)) {
            path.reset()
            path.moveTo(side * 46f, -14f)
            path.lineTo(side * 40f, -62f)
            path.lineTo(side * 10f, -44f)
            path.close()
            fill.color = fur
            drawPath(path, fill)
            drawPath(path, outline(ink, 3f))
            path.reset()
            path.moveTo(side * 38f, -24f)
            path.lineTo(side * 36f, -50f)
            path.lineTo(side * 18f, -40f)
            path.close()
            fill.color = Color.rgb(255, 170, 190)
            drawPath(path, fill)
        }
        oval(0f, 0f, 96f, 92f, fur)
        drawOval(RectF(-48f, -46f, 48f, 46f), outline(ink, 3f))
        for ((x, open) in listOf(-17f to face.leftEyeOpen, 17f to face.rightEyeOpen)) {
            oval(x, -10f, 16f, maxOf(3f, 20f * open), Color.rgb(118, 255, 3))
            oval(x, -10f, 4f, maxOf(2f, 16f * open), Color.BLACK)
        }
        path.reset()
        path.moveTo(-6f, 6f)
        path.lineTo(6f, 6f)
        path.lineTo(0f, 12f)
        path.close()
        fill.color = Color.rgb(255, 105, 150)
        drawPath(path, fill)
        if (face.mouthOpen > 0.15f) oval(0f, 22f, 16f, 6f + 22f * face.mouthOpen, Color.rgb(110, 20, 30))
        val line = outline(ink, 2f)
        path.reset()
        path.moveTo(-10f, 16f)
        path.quadTo(-5f, 22f, 0f, 12f)
        path.quadTo(5f, 22f, 10f, 16f)
        drawPath(path, line)
        val whisker = outline(ink, 1.5f)
        for (side in listOf(-1f, 1f)) {
            drawLine(side * 18f, 10f, side * 50f, 4f, whisker)
            drawLine(side * 18f, 14f, side * 50f, 16f, whisker)
        }
    }

    @Synchronized
    fun alien(canvas: Canvas, face: FacePose) = with(canvas) {
        val skin = Color.rgb(118, 255, 3)
        oval(0f, -4f, 82f, 108f, skin)
        drawOval(RectF(-41f, -58f, 41f, 50f), outline(Color.rgb(27, 94, 32), 3f))
        for ((side, open) in listOf(-1f to face.leftEyeOpen, 1f to face.rightEyeOpen)) {
            save()
            translate(side * 17f, -2f)
            rotate(side * 28f)
            oval(0f, 0f, 30f, maxOf(4f, 18f * maxOf(open, 0.25f)), Color.BLACK)
            oval(-5f, -3f, 6f, 4f, Color.argb(200, 255, 255, 255))
            restore()
        }
        oval(0f, 32f + 6f * face.mouthOpen, 12f, 4f + 16f * face.mouthOpen, Color.rgb(27, 60, 20))
    }

    @Synchronized
    fun monster(canvas: Canvas, face: FacePose) = with(canvas) {
        val ink = Color.rgb(49, 27, 146)
        fill.color = Color.WHITE
        for (side in listOf(-1f, 1f)) {
            path.reset()
            path.moveTo(side * 22f, -40f)
            path.lineTo(side * 34f, -66f)
            path.lineTo(side * 38f, -36f)
            path.close()
            drawPath(path, fill)
        }
        oval(0f, 0f, 100f, 100f, Color.rgb(179, 136, 255))
        drawCircle(0f, 0f, 50f, outline(ink, 3f))
        val open = (face.leftEyeOpen + face.rightEyeOpen) / 2
        oval(0f, -16f, 38f, maxOf(6f, 38f * open), Color.WHITE)
        oval(0f, -16f, 18f, maxOf(4f, 18f * open), Color.rgb(0, 200, 83))
        oval(0f, -16f, 8f, maxOf(2f, 8f * open), Color.BLACK)
        val bottom = 22f + 26f * face.mouthOpen
        fill.color = Color.rgb(74, 0, 19)
        rect.set(-28f, 10f, 28f, bottom)
        drawRoundRect(rect, 10f, 10f, fill)
        fill.color = Color.WHITE
        var x = -26f
        while (x < 24f) {
            path.reset()
            path.moveTo(x, 10f)
            path.lineTo(x + 6f, 19f)
            path.lineTo(x + 12f, 10f)
            path.close()
            drawPath(path, fill)
            x += 13f
        }
    }
}
