package app.stickme.kids

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock

/** Colour styles the 🎨 button cycles through. */
enum class Look { NEON, RAINBOW, SPARKLE, ECHO }

/** Choices the kid makes with the buttons, read by the screen, the video recorder and the effects. */
class SceneSettings {
    @Volatile var look = Look.NEON
    @Volatile var face: FaceStyle = FaceStyle.Lines
}

/** One analysed camera frame: the people found, the (upright) picture they were found in, and the fun on top. */
class SceneFrame(
    val people: List<Person>,
    val picture: Bitmap?,
    val imageWidth: Int,
    val imageHeight: Int,
    val mirrored: Boolean,
    val extras: FunSnapshot = FunSnapshot.EMPTY,
) {
    companion object {
        val EMPTY = SceneFrame(emptyList(), null, 1, 1, false)
    }
}

/**
 * Draws stick figures, faces, effects and the game card onto any canvas.
 * Not thread-safe: give each drawing thread its own painter.
 */
class ScenePainter(private val settings: SceneSettings) {

    private val minScore = 0.3f

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(glow)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pictureMatrix = Matrix()
    private val effects = EffectsPainter()
    private val gameCard = GameHudPainter(effects)

    /** Full scene for a video frame: black, then the camera picture at [pictureAlpha] (0 hides it), then the rest. */
    fun drawScene(
        canvas: Canvas, width: Int, height: Int, frame: SceneFrame, pictureAlpha: Int,
        now: Long = SystemClock.uptimeMillis(),
    ) {
        canvas.drawColor(Color.BLACK)
        val picture = frame.picture
        if (picture != null && pictureAlpha > 0) {
            val fit = fitFor(width, height, frame)
            pictureMatrix.reset()
            pictureMatrix.postScale(fit.scale, fit.scale)
            pictureMatrix.postTranslate(fit.offsetX, fit.offsetY)
            if (frame.mirrored) pictureMatrix.postScale(-1f, 1f, width / 2f, 0f)
            picturePaint.alpha = pictureAlpha
            canvas.drawBitmap(picture, pictureMatrix, picturePaint)
        }
        drawPeople(canvas, width, height, frame, now)
    }

    fun drawPeople(canvas: Canvas, width: Int, height: Int, frame: SceneFrame, now: Long = SystemClock.uptimeMillis()) {
        val fit = fitFor(width, height, frame)
        val look = settings.look
        val rainbowShift = (now / 12 % 360).toFloat()

        val game = frame.extras.game
        game?.let { gameCard.drawCard(canvas, width, height, it) }
        if (look == Look.ECHO) drawEchoes(canvas, fit, frame.extras.echoes, rainbowShift)

        frame.people.forEachIndexed { personIndex, person ->
            val chosenFace = settings.face
            val faceStyle = if (chosenFace == FaceStyle.Mix) FaceCatalog.forMix(person.id) else chosenFace
            val face = if (faceStyle == FaceStyle.Lines) null else FacePose.of(person, fit::x, fit::y)
            val bodyWidth = bodyWidth(person, fit)
            val personHue = (rainbowShift + personIndex * 90) % 360

            drawBones(canvas, person, fit, bodyWidth, alpha = 255, skipHead = face != null) { bone ->
                when (look) {
                    Look.RAINBOW -> Color.HSVToColor(floatArrayOf((personHue + bone.from * 3) % 360, 0.85f, 1f))
                    Look.NEON, Look.SPARKLE, Look.ECHO -> Skeleton.neonColor(bone)
                }
            }

            for (k in 0 until Skeleton.POINT_COUNT) {
                if (!Skeleton.isJoint(k) || person.scores[k] < minScore) continue
                if (face != null && k <= 4) continue
                val radius = if (k < 23) bodyWidth * 0.9f else bodyWidth * 0.4f
                canvas.drawCircle(fit.x(person.points[k * 2]), fit.y(person.points[k * 2 + 1]), radius, dot)
            }

            if (face != null) FaceDrawing.draw(canvas, faceStyle, face)
        }

        effects.drawParticles(canvas, fit, frame.extras.particles, now)
        effects.drawPops(canvas, fit, frame.extras.pops, now)
        game?.let { gameCard.drawBigText(canvas, width, height, it, now) }
    }

    /** Rainbow ghosts of where people were a moment ago, oldest faintest. */
    private fun drawEchoes(canvas: Canvas, fit: ImageFit, echoes: List<List<Person>>, rainbowShift: Float) {
        for (age in echoes.indices.reversed()) {
            if (age == 0 || age % 2 == 1) continue
            val alpha = (150 * (1 - age / echoes.size.toFloat())).toInt()
            val hue = (rainbowShift + age * 36) % 360
            val color = Color.HSVToColor(floatArrayOf(hue, 0.8f, 1f))
            for (person in echoes[age]) {
                drawBones(canvas, person, fit, bodyWidth(person, fit) * 0.8f, alpha, skipHead = true, skipFingers = true) { color }
            }
        }
    }

    private inline fun drawBones(
        canvas: Canvas, person: Person, fit: ImageFit, bodyWidth: Float, alpha: Int,
        skipHead: Boolean, skipFingers: Boolean = false, colorOf: (Skeleton.Bone) -> Int,
    ) {
        for (bone in Skeleton.bones) {
            if (skipHead && (bone.part == Skeleton.Part.FACE || bone.part == Skeleton.Part.HEAD)) continue
            if (skipFingers && bone.part == Skeleton.Part.FINGER) continue
            if (person.scores[bone.from] < minScore || person.scores[bone.to] < minScore) continue
            val color = colorOf(bone)
            val thin = bone.part == Skeleton.Part.FACE || bone.part == Skeleton.Part.FINGER
            val strokeWidth = if (thin) bodyWidth * 0.45f else bodyWidth
            val x1 = fit.x(person.points[bone.from * 2])
            val y1 = fit.y(person.points[bone.from * 2 + 1])
            val x2 = fit.x(person.points[bone.to * 2])
            val y2 = fit.y(person.points[bone.to * 2 + 1])

            glow.color = color
            glow.alpha = alpha * 70 / 255
            glow.strokeWidth = strokeWidth * 3.2f
            canvas.drawLine(x1, y1, x2, y2, glow)
            line.color = color
            line.alpha = alpha
            line.strokeWidth = strokeWidth
            canvas.drawLine(x1, y1, x2, y2, line)
        }
    }

    private fun bodyWidth(person: Person, fit: ImageFit) = (person.box.height() * fit.scale * 0.012f).coerceIn(3f, 16f)

    private fun fitFor(width: Int, height: Int, frame: SceneFrame) =
        ImageFit(width, height, frame.imageWidth, frame.imageHeight, frame.mirrored)
}
