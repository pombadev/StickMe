package app.stickme.kids

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.min

/** A stick-figure drawing in a 100 x 100 box: a head and some lines (each line is x,y,x,y,...). */
class Pictogram(val headX: Float, val headY: Float, val lines: List<FloatArray>) {
    companion object {
        private val SPINE = floatArrayOf(50f, 24f, 50f, 58f)
        private val LEGS = floatArrayOf(40f, 94f, 50f, 58f, 60f, 94f)

        fun standing(arms: FloatArray, legs: FloatArray = LEGS) = Pictogram(50f, 15f, listOf(SPINE, arms, legs))
    }
}

/** Poses for the "Copy me!" game, each with a picture and a check on the real person. */
enum class PoseTarget(val title: String, val emoji: String, val picture: Pictogram, val matches: (Body) -> Boolean) {
    HANDS_UP(
        "Hands up!", "🙌",
        Pictogram.standing(floatArrayOf(34f, 3f, 50f, 30f, 66f, 3f)),
        { b ->
            val head = b.headY
            head != null && b.ok(9, 10) && b.y(9) < head - 0.1f * b.unit && b.y(10) < head - 0.1f * b.unit
        },
    ),
    STAR(
        "Star jump!", "⭐",
        Pictogram.standing(floatArrayOf(14f, 8f, 50f, 30f, 86f, 8f), floatArrayOf(20f, 94f, 50f, 58f, 80f, 94f)),
        { b ->
            val shoulders = b.shoulderY
            shoulders != null && b.ok(9, 10, 15, 16) && b.y(9) < shoulders && b.y(10) < shoulders &&
                abs(b.x(9) - b.x(10)) > 1.8f * b.unit && abs(b.x(15) - b.x(16)) > 0.8f * b.unit
        },
    ),
    AIRPLANE(
        "Airplane!", "✈️",
        Pictogram.standing(floatArrayOf(6f, 30f, 94f, 30f), floatArrayOf(44f, 94f, 50f, 58f, 56f, 94f)),
        { b ->
            b.ok(5, 6, 9, 10) && abs(b.y(9) - b.y(5)) < 0.4f * b.unit && abs(b.y(10) - b.y(6)) < 0.4f * b.unit &&
                abs(b.x(9) - b.x(10)) > 2.0f * b.unit
        },
    ),
    FLAMINGO(
        "Flamingo!", "🦩",
        Pictogram.standing(floatArrayOf(34f, 48f, 50f, 30f, 66f, 48f), floatArrayOf(50f, 94f, 50f, 58f, 68f, 70f, 54f, 80f)),
        { b -> b.ok(11, 12, 15, 16) && abs(b.y(15) - b.y(16)) > 0.35f * b.unit },
    ),
    HANDS_ON_HEAD(
        "Hands on head!", "🙆",
        Pictogram.standing(floatArrayOf(43f, 6f, 30f, 20f, 50f, 30f, 70f, 20f, 57f, 6f)),
        { b ->
            val shoulders = b.shoulderY
            shoulders != null && b.ok(0, 9, 10) && b.y(9) < shoulders && b.y(10) < shoulders &&
                b.dist(9, 0) < 0.9f * b.unit && b.dist(10, 0) < 0.9f * b.unit
        },
    ),
    TOUCH_TOES(
        "Touch your toes!", "🙇",
        Pictogram(
            76f, 60f,
            listOf(floatArrayOf(40f, 46f, 67f, 56f), floatArrayOf(58f, 92f, 67f, 56f, 72f, 92f), floatArrayOf(34f, 94f, 40f, 46f, 46f, 94f)),
        ),
        { b -> b.ok(9, 10, 13, 14) && min(b.y(9), b.y(10)) > (b.y(13) + b.y(14)) / 2 },
    ),
    SUPERHERO(
        "Superhero!", "🦸",
        Pictogram.standing(floatArrayOf(68f, 2f, 50f, 30f, 38f, 56f)),
        { b ->
            val head = b.headY
            val shoulders = b.shoulderY
            head != null && shoulders != null && b.ok(9, 10) && run {
                val leftUp = b.y(9) < head - 0.1f * b.unit
                val rightUp = b.y(10) < head - 0.1f * b.unit
                val leftDown = b.y(9) > shoulders + 0.3f * b.unit
                val rightDown = b.y(10) > shoulders + 0.3f * b.unit
                (leftUp && rightDown) || (rightUp && leftDown)
            }
        },
    ),
}

/** What the game card shows this moment. */
class GameHud(
    val title: String,
    val emoji: String,
    val picture: Pictogram?,
    val subtitle: String,
    val stars: Int?,
    val timeLeft: Float?,
    val holding: Boolean,
    val bigText: String?,
    val bigTextBorn: Long,
    val bigTextColor: Int,
    val bigTextStays: Boolean,
)

/**
 * "Copy me!": shows a pose, and the first person to hold it wins a star. Runs on the camera thread;
 * [requestStart] and [requestStop] can be called from anywhere.
 */
class PoseGame(private val sounds: SoundBoard) {

    private enum class Phase { IDLE, COUNTDOWN, ROUND, CHEER, FINISHED }

    @Volatile private var startRequested = false
    @Volatile private var stopRequested = false
    @Volatile var isRunning = false
        private set

    /** Called on the camera thread when a game starts or ends. */
    var onRunningChanged: ((Boolean) -> Unit)? = null

    private var phase = Phase.IDLE
    private var phaseStart = 0L
    private var lastUpdate = 0L
    private var poses: List<PoseTarget> = emptyList()
    private var round = 0
    private var score = 0
    private var target: PoseTarget? = null
    private var holdStart: Long? = null
    private var lastRoundWon = false
    private var countdownShown = 0

    private var bigText: String? = null
    private var bigTextBorn = 0L
    private var bigTextColor = Color.WHITE

    fun requestStart() { startRequested = true }

    fun requestStop() { stopRequested = true }

    fun update(bodies: List<Body>, now: Long, fx: FunEngine, imageWidth: Int, imageHeight: Int) {
        val elapsed = if (lastUpdate == 0L) 0L else now - lastUpdate
        lastUpdate = now

        if (stopRequested) {
            stopRequested = false
            startRequested = false
            enter(Phase.IDLE, now)
            return
        }
        if (startRequested) {
            startRequested = false
            poses = PoseTarget.entries.shuffled()
            round = 0
            score = 0
            target = null
            countdownShown = 0
            enter(Phase.COUNTDOWN, now)
        }

        when (phase) {
            Phase.IDLE -> Unit
            Phase.COUNTDOWN -> {
                val left = 3 - ((now - phaseStart) / COUNT_MS).toInt()
                if (left <= 0) {
                    startRound(now)
                } else if (left != countdownShown) {
                    countdownShown = left
                    showBig(left.toString(), now, Color.rgb(255, 235, 59))
                    sounds.play(Sound.TICK)
                }
            }
            Phase.ROUND -> {
                val goal = target ?: return
                if (bodies.isEmpty()) phaseStart += elapsed // nobody in the picture: pause the clock
                if (bodies.any(goal.matches)) {
                    val since = holdStart ?: now.also { holdStart = it }
                    if (now - since >= HOLD_MS) {
                        score++
                        lastRoundWon = true
                        showBig(CHEERS.random(), now, Color.rgb(118, 255, 3))
                        fx.confettiRain(now, imageWidth, imageHeight)
                        sounds.play(Sound.TADA)
                        enter(Phase.CHEER, now)
                    }
                } else {
                    holdStart = null
                }
                if (phase == Phase.ROUND && now - phaseStart > ROUND_MS) {
                    lastRoundWon = false
                    showBig("Next one!", now, Color.rgb(255, 152, 0))
                    sounds.play(Sound.MISS)
                    enter(Phase.CHEER, now)
                }
            }
            Phase.CHEER -> if (now - phaseStart > CHEER_MS) {
                if (round >= poses.size) finish(now, fx, imageWidth, imageHeight) else startRound(now)
            }
            Phase.FINISHED -> if (now - phaseStart > FINISHED_MS) enter(Phase.IDLE, now)
        }
    }

    fun hud(now: Long): GameHud? {
        fun card(
            title: String, emoji: String, picture: Pictogram?, subtitle: String = "",
            stars: Int? = null, timeLeft: Float? = null, holding: Boolean = false,
        ) = GameHud(
            title, emoji, picture, subtitle, stars, timeLeft, holding,
            bigText, bigTextBorn, bigTextColor, bigTextStays = phase == Phase.FINISHED,
        )
        val goal = target
        return when (phase) {
            Phase.IDLE -> null
            Phase.COUNTDOWN -> card("Get ready!", "🎯", null, "Copy the poses!")
            Phase.ROUND -> card(
                goal!!.title, goal.emoji, goal.picture,
                subtitle = if (holdStart != null) "Hold it… 🤩" else "",
                stars = score,
                timeLeft = (1 - (now - phaseStart).toFloat() / ROUND_MS).coerceIn(0f, 1f),
                holding = holdStart != null,
            )
            Phase.CHEER -> card(goal!!.title, goal.emoji, goal.picture, stars = score, holding = lastRoundWon)
            Phase.FINISHED -> card("You did it!", "🏆", null, "You got $score of ${poses.size} poses")
        }
    }

    private fun startRound(now: Long) {
        target = poses[round]
        round++
        holdStart = null
        showBig("GO!", now, Color.WHITE)
        sounds.play(Sound.GO)
        enter(Phase.ROUND, now)
    }

    private fun finish(now: Long, fx: FunEngine, imageWidth: Int, imageHeight: Int) {
        showBig("⭐ $score", now, Color.rgb(255, 235, 59))
        fx.confettiRain(now, imageWidth, imageHeight)
        fx.confettiRain(now + 600, imageWidth, imageHeight)
        sounds.play(Sound.WIN)
        enter(Phase.FINISHED, now)
    }

    private fun showBig(text: String, now: Long, color: Int) {
        bigText = text
        bigTextBorn = now
        bigTextColor = color
    }

    private fun enter(next: Phase, now: Long) {
        phase = next
        phaseStart = now
        if (next == Phase.IDLE) {
            target = null
            bigText = null
        }
        val running = next != Phase.IDLE
        if (running != isRunning) {
            isRunning = running
            onRunningChanged?.invoke(running)
        }
    }

    private companion object {
        const val COUNT_MS = 900L
        const val ROUND_MS = 9000L
        const val HOLD_MS = 500L
        const val CHEER_MS = 1600L
        const val FINISHED_MS = 6000L
        val CHEERS = listOf("GREAT!", "AWESOME!", "WOW!", "SUPER!", "YES!", "AMAZING!")
    }
}

/** Draws the game card at the top and big words in the middle. One per drawing thread. */
class GameHudPainter(private val effects: EffectsPainter) {
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 40, 40, 40) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val figure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 235, 59) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** The card with the pose to copy. Drawn under the stick figures so it never hides anyone. */
    fun drawCard(canvas: Canvas, width: Int, height: Int, hud: GameHud) {
        val s = min(width, height).toFloat()
        val cardWidth = min(width * 0.9f, s * 1.3f)
        val left = (width - cardWidth) / 2
        val right = left + cardWidth
        val top = height * 0.09f // just below the small buttons in the top corner
        val bottom = top + s * 0.2f
        val highlight = if (hud.holding) Color.rgb(118, 255, 3) else Color.WHITE

        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, card)
        if (hud.holding) {
            border.color = highlight
            border.strokeWidth = s * 0.012f
            canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, border)
        }

        val box = s * 0.14f
        val boxLeft = left + s * 0.04f
        val boxTop = top + s * 0.03f
        hud.picture?.let { drawPictogram(canvas, it, boxLeft, boxTop, box, highlight) }
            ?: effects.drawComicText(canvas, hud.emoji, boxLeft + box / 2, boxTop + box * 0.8f, box * 0.85f, Color.WHITE)

        val textLeft = boxLeft + box + s * 0.05f
        var textRight = right - s * 0.04f
        hud.stars?.let { stars ->
            subtitle.textSize = s * 0.05f
            subtitle.textAlign = Paint.Align.RIGHT
            canvas.drawText("⭐ $stars", textRight, top + s * 0.09f, subtitle)
            textRight -= subtitle.measureText("⭐ $stars") + s * 0.03f
            subtitle.textAlign = Paint.Align.LEFT
        }
        title.textSize = s * 0.08f
        val tooWide = title.measureText(hud.title) / (textRight - textLeft)
        if (tooWide > 1f) title.textSize /= tooWide
        canvas.drawText(hud.title, textLeft, top + s * 0.095f, title)
        if (hud.subtitle.isNotEmpty()) {
            subtitle.textSize = s * 0.045f
            canvas.drawText(hud.subtitle, textLeft, top + s * 0.145f, subtitle)
        }

        hud.timeLeft?.let { left ->
            val barTop = top + s * 0.16f
            val barBottom = barTop + s * 0.016f
            bar.color = Color.argb(70, 255, 255, 255)
            rect.set(textLeft, barTop, right - s * 0.04f, barBottom)
            canvas.drawRoundRect(rect, s * 0.01f, s * 0.01f, bar)
            bar.color = Color.HSVToColor(floatArrayOf(120f * left, 0.85f, 1f)) // green → red as time runs out
            rect.right = textLeft + (right - s * 0.04f - textLeft) * left
            canvas.drawRoundRect(rect, s * 0.01f, s * 0.01f, bar)
        }
    }

    /** Countdown numbers and cheers in the middle of the screen, drawn on top of everything. */
    fun drawBigText(canvas: Canvas, width: Int, height: Int, hud: GameHud, now: Long) {
        val s = min(width, height).toFloat()
        val text = hud.bigText ?: return
        val life = ((now - hud.bigTextBorn) / BIG_TEXT_MS.toFloat()).coerceAtLeast(0f)
        if (life >= 1f && !hud.bigTextStays) return
        val size = s * (if (text.length <= 2) 0.34f else 0.15f) * EffectsPainter.bounce(min(life, 1f))
        val alpha = if (hud.bigTextStays) 1f else EffectsPainter.fadeOut(life)
        effects.drawComicText(canvas, text, width / 2f, height * 0.52f, size, hud.bigTextColor, alpha)
    }

    private fun drawPictogram(canvas: Canvas, picture: Pictogram, left: Float, top: Float, size: Float, color: Int) {
        val unit = size / 100f
        figure.color = color
        figure.strokeWidth = 7f * unit
        for (line in picture.lines) {
            for (i in 0 until line.size - 2 step 2) {
                canvas.drawLine(
                    left + line[i] * unit, top + line[i + 1] * unit,
                    left + line[i + 2] * unit, top + line[i + 3] * unit, figure,
                )
            }
        }
        head.color = color
        canvas.drawCircle(left + picture.headX * unit, top + picture.headY * unit, 8f * unit, head)
    }

    private companion object {
        const val BIG_TEXT_MS = 1100L
    }
}
