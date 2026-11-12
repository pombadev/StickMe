package app.stickme.kids

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min
import kotlin.random.Random

/** Every game in the 🎯 menu. */
enum class GameKind(val emoji: String, val label: String) {
    COPY_ME("🎯", "Copy me"),
    BUBBLES("🫧", "Bubbles"),
    FREEZE("🧊", "Freeze dance"),
    JUMPS("🦘", "Jumps"),
    METEORS("☄️", "Meteors"),
    LIMBO("🎋", "Limbo"),
    PAINT("🖌️", "Air paint"),
}

/** Remembers each game's best score. */
interface ScoreBook {
    fun best(game: GameKind): Int

    /** Saves [score] if it beats the best so far; true when it did. */
    fun offer(game: GameKind, score: Int): Boolean
}

class MemoryScoreBook : ScoreBook {
    private val best = HashMap<GameKind, Int>()
    override fun best(game: GameKind) = best[game] ?: 0
    override fun offer(game: GameKind, score: Int): Boolean {
        if (score <= best(game)) return false
        best[game] = score
        return true
    }
}

/** What every game may use: sounds, the phone's voice, best scores and dice. */
class GameKit(
    val sounds: SoundBoard,
    val scores: ScoreBook = MemoryScoreBook(),
    val speak: (String) -> Unit = {},
    val random: Random = Random.Default,
)

/** One camera frame as a game sees it. [elapsed] is the time since the previous frame. */
class Tick(
    val bodies: List<Body>,
    val now: Long,
    val elapsed: Long,
    val fx: FunEngine,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** The card at the top of the screen. [corner] is short text on the right, such as the score. */
class Card(
    val title: String,
    val emoji: String,
    val picture: Pictogram? = null,
    val corner: String? = null,
    val subtitle: String = "",
    val timeLeft: Float? = null,
    val highlight: Boolean = false,
)

/** A big bouncing word in the middle of the screen, like a countdown number or "GREAT!". */
class BigText(val text: String, val born: Long, val color: Int, val stays: Boolean = false)

/** Where and when a game is being drawn, plus the paints to draw with. */
class GameCanvas(val canvas: Canvas, val width: Int, val height: Int, val fit: ImageFit, val now: Long, val tools: GameTools)

/**
 * A game frozen at one moment, safe to draw from both the screen and the video recorder.
 * [behind] draws under the stick figures, [front] over them.
 */
class GameScene(
    val card: Card?,
    val big: BigText?,
    private val behind: ((GameCanvas) -> Unit)? = null,
    private val front: ((GameCanvas) -> Unit)? = null,
) {
    fun drawBehind(c: GameCanvas) {
        card?.let { c.tools.drawCard(c, it) }
        behind?.invoke(c)
    }

    fun drawFront(c: GameCanvas) {
        front?.invoke(c)
        big?.let { c.tools.drawBigText(c, it) }
    }
}

/**
 * Shared flow for every game: 3-2-1 countdown, play, then a results screen with confetti
 * and the best score. Games fill in [play] and [playScene]. Camera thread only.
 */
abstract class MiniGame(protected val kit: GameKit, val kind: GameKind) {

    private enum class Stage { COUNTDOWN, PLAY, RESULT, DONE }

    private var stage = Stage.COUNTDOWN
    private var stageStart = -1L
    private var countdownShown = 0
    private var resultCard: Card? = null

    protected var big: BigText? = null
    var score = 0
        protected set

    /** One short sentence shown during the countdown. */
    protected abstract val howToPlay: String

    /** Plays one frame. Returns false when the game is over. */
    protected abstract fun play(t: Tick): Boolean

    protected abstract fun playScene(now: Long): GameScene

    protected open fun onStart(t: Tick) {}

    /** Called once when the game ends or is stopped early, for example to stop music. */
    protected open fun onStop() {}

    protected open fun resultText(): String = "⭐ $score"

    protected open fun resultSubtitle(newBest: Boolean): String =
        if (newBest) "New best! 🏆" else "Best: ${kit.scores.best(kind)}"

    /** Returns false once the results have been shown long enough. */
    fun update(t: Tick): Boolean {
        if (stageStart < 0) stageStart = t.now
        when (stage) {
            Stage.COUNTDOWN -> {
                val left = 3 - ((t.now - stageStart) / COUNT_MS).toInt()
                if (left <= 0) {
                    stage = Stage.PLAY
                    stageStart = t.now
                    show("GO!", t.now, Color.WHITE)
                    kit.sounds.play(Sound.GO)
                    onStart(t)
                } else if (left != countdownShown) {
                    countdownShown = left
                    show(left.toString(), t.now, YELLOW)
                    kit.sounds.play(Sound.TICK)
                }
            }
            Stage.PLAY -> if (!play(t)) finish(t)
            Stage.RESULT -> if (t.now - stageStart > RESULT_MS) stage = Stage.DONE
            Stage.DONE -> Unit
        }
        return stage != Stage.DONE
    }

    fun scene(now: Long): GameScene = when (stage) {
        Stage.COUNTDOWN -> GameScene(Card(kind.label, kind.emoji, subtitle = howToPlay), big)
        Stage.PLAY -> playScene(now)
        Stage.RESULT, Stage.DONE -> GameScene(resultCard, big)
    }

    fun stop() {
        if (stage == Stage.PLAY) onStop()
        stage = Stage.DONE
    }

    private fun finish(t: Tick) {
        onStop()
        stage = Stage.RESULT
        stageStart = t.now
        val newBest = kit.scores.offer(kind, score)
        resultCard = Card("You did it!", "🏆", subtitle = resultSubtitle(newBest))
        show(resultText(), t.now, YELLOW, stays = true)
        t.fx.confettiRain(t.now, t.imageWidth, t.imageHeight)
        t.fx.confettiRain(t.now + 600, t.imageWidth, t.imageHeight)
        kit.sounds.play(Sound.WIN)
    }

    protected fun show(text: String, now: Long, color: Int, stays: Boolean = false) {
        big = BigText(text, now, color, stays)
    }

    companion object {
        const val COUNT_MS = 900L
        const val RESULT_MS = 5500L
        val YELLOW = Color.rgb(255, 235, 59)
        val GREEN = Color.rgb(118, 255, 3)
        val ORANGE = Color.rgb(255, 152, 0)
        val CHEERS = listOf("GREAT!", "AWESOME!", "WOW!", "SUPER!", "YES!", "AMAZING!")
    }
}

/**
 * Starts, runs and stops the chosen game. Camera thread, except [requestStart] and [requestStop],
 * which any thread may call.
 */
class GameHost(private val kit: GameKit) {

    @Volatile private var requested: GameKind? = null
    @Volatile private var stopRequested = false
    @Volatile var current: GameKind? = null
        private set

    val isRunning get() = current != null

    /** Called on the camera thread with the game that started, or null when it ended. */
    var onChanged: ((GameKind?) -> Unit)? = null

    private var game: MiniGame? = null

    internal val activeGame: MiniGame? get() = game

    fun requestStart(kind: GameKind) { requested = kind }

    fun requestStop() { stopRequested = true }

    fun update(t: Tick): GameScene? {
        if (stopRequested) {
            stopRequested = false
            requested = null
            end()
        }
        requested?.let { kind ->
            requested = null
            end()
            game = create(kind)
            current = kind
            onChanged?.invoke(kind)
        }
        val running = game ?: return null
        if (!running.update(t)) {
            end()
            return null
        }
        return running.scene(t.now)
    }

    private fun end() {
        game?.stop()
        game = null
        if (current != null) {
            current = null
            onChanged?.invoke(null)
        }
    }

    private fun create(kind: GameKind): MiniGame = when (kind) {
        GameKind.COPY_ME -> CopyMeGame(kit)
        GameKind.BUBBLES -> BubblePop(kit)
        GameKind.FREEZE -> FreezeDance(kit)
        GameKind.JUMPS -> JumpChallenge(kit)
        GameKind.METEORS -> Meteors(kit)
        GameKind.LIMBO -> Limbo(kit)
        GameKind.PAINT -> AirPainting(kit)
    }
}

/** Paints and drawing helpers for games. One per drawing thread. */
class GameTools(val effects: EffectsPainter) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 40, 40, 40) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val figure = Paint(stroke)
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MiniGame.YELLOW }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** The card with what to do. Drawn under the stick figures so it never hides anyone. */
    fun drawCard(c: GameCanvas, info: Card) {
        val canvas = c.canvas
        val s = min(c.width, c.height).toFloat()
        val cardWidth = min(c.width * 0.9f, s * 1.3f)
        val left = (c.width - cardWidth) / 2
        val right = left + cardWidth
        val top = c.height * 0.09f // just below the small buttons in the top corner
        val bottom = top + s * 0.2f
        val highlight = if (info.highlight) MiniGame.GREEN else Color.WHITE

        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, card)
        if (info.highlight) {
            border.color = highlight
            border.strokeWidth = s * 0.012f
            canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, border)
        }

        val box = s * 0.14f
        val boxLeft = left + s * 0.04f
        val boxTop = top + s * 0.03f
        info.picture?.let { drawPictogram(canvas, it, boxLeft, boxTop, box, highlight) }
            ?: effects.drawComicText(canvas, info.emoji, boxLeft + box / 2, boxTop + box * 0.8f, box * 0.85f, Color.WHITE)

        val textLeft = boxLeft + box + s * 0.05f
        var textRight = right - s * 0.04f
        info.corner?.let { corner ->
            small.textSize = s * 0.05f
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(corner, textRight, top + s * 0.09f, small)
            textRight -= small.measureText(corner) + s * 0.03f
            small.textAlign = Paint.Align.LEFT
        }
        title.textSize = s * 0.08f
        val tooWide = title.measureText(info.title) / (textRight - textLeft)
        if (tooWide > 1f) title.textSize /= tooWide
        canvas.drawText(info.title, textLeft, top + s * 0.095f, title)
        if (info.subtitle.isNotEmpty()) {
            small.textSize = s * 0.045f
            val subtitleTooWide = small.measureText(info.subtitle) / (right - s * 0.04f - textLeft)
            if (subtitleTooWide > 1f) small.textSize /= subtitleTooWide
            canvas.drawText(info.subtitle, textLeft, top + s * 0.145f, small)
        }

        info.timeLeft?.let { left ->
            val barTop = top + s * 0.16f
            rect.set(textLeft, barTop, right - s * 0.04f, barTop + s * 0.016f)
            bar.color = Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(rect, s * 0.01f, s * 0.01f, bar)
            bar.color = Color.HSVToColor(floatArrayOf(120f * left, 0.85f, 1f)) // green → red as time runs out
            rect.right = textLeft + (right - s * 0.04f - textLeft) * left
            canvas.drawRoundRect(rect, s * 0.01f, s * 0.01f, bar)
        }
    }

    /** Countdown numbers and cheers in the middle of the screen, drawn on top of everything. */
    fun drawBigText(c: GameCanvas, text: BigText) {
        val s = min(c.width, c.height).toFloat()
        val life = ((c.now - text.born) / BIG_TEXT_MS.toFloat()).coerceAtLeast(0f)
        if (life >= 1f && !text.stays) return
        val size = s * (if (text.text.length <= 2) 0.34f else 0.15f) * EffectsPainter.bounce(min(life, 1f))
        val alpha = if (text.stays) 1f else EffectsPainter.fadeOut(life)
        val tooWide = (s * 0.15f * text.text.length * 0.6f) / (c.width * 0.9f)
        val fitted = if (text.text.length > 2 && tooWide > 1f) size / tooWide else size
        effects.drawComicText(c.canvas, text.text, c.width / 2f, c.height * 0.52f, fitted, text.color, alpha)
    }

    /** A number or word floating above someone's head, in image coordinates. */
    fun drawLabel(c: GameCanvas, text: String, imageX: Float, imageY: Float, imageSize: Float, color: Int) {
        val size = imageSize * c.fit.scale
        val y = maxOf(c.fit.y(imageY), size * 1.1f)
        effects.drawComicText(c.canvas, text, c.fit.x(imageX), y, size, color)
    }

    fun drawPictogram(canvas: Canvas, picture: Pictogram, left: Float, top: Float, size: Float, color: Int) {
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
