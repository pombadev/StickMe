package app.stickme.kids

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Parts of the body a game needs to see. */
enum class Part(val label: String) {
    HEAD("head"), HANDS("hands"), FEET("feet");

    companion object {
        val ALL = setOf(HEAD, HANDS, FEET)
        val UPPER = setOf(HEAD, HANDS)
    }
}

/**
 * True when [part] of this body is seen and inside [frame] (image pixels). Kept forgiving on purpose,
 * so kids who are in view are not told to step back: a lower confidence than elsewhere is enough,
 * and any point of the head, or of each hand or foot, will do (an ankle is often blurry or hidden
 * while the toes and heel are seen).
 */
fun Body.sees(part: Part, frame: RectF): Boolean {
    fun inside(k: Int) = person.scores[k] > SEEN_SCORE && x(k) in frame.left..frame.right && y(k) in frame.top..frame.bottom
    fun any(vararg points: Int) = points.any(::inside)
    return when (part) {
        Part.HEAD -> any(0, 1, 2, 3, 4)
        Part.HANDS -> any(Body.LEFT_WRIST, 91, 99, 103) && any(Body.RIGHT_WRIST, 112, 120, 124)
        Part.FEET -> any(Body.LEFT_ANKLE, 17, 18, 19) && any(Body.RIGHT_ANKLE, 20, 21, 22)
    }
}

private const val SEEN_SCORE = 0.2f

/**
 * The "get ready" stage of every game: someone must be fully in view ("Step back!") before the countdown
 * starts. Everyone in view plays together, so there is no picking a player.
 */
class GameGate(
    private val gameName: String,
    private val needs: Set<Part>,
    private val needPeople: Int,
) {
    private var fitSince = 0L
    private var lastFitAt = 0L
    private var waitingSince = -1L
    private var skipped = false
    private var canSkip = false

    // What the last frame showed, kept for drawing the scene.
    private var bodies: List<Body> = emptyList()
    private var frame = RectF()
    private var checks: List<Pair<String, Boolean>> = emptyList()

    fun skip() { skipped = true }

    /** Returns true when the game may start. */
    fun update(t: Tick): Boolean {
        if (waitingSince < 0) waitingSince = t.now
        bodies = t.bodies
        frame = frameFor(t.visible)
        canSkip = t.now - waitingSince >= SKIP_AFTER_MS

        val best = bodies.maxByOrNull { b -> needs.count { b.sees(it, frame) } }
        checks = needs.sortedBy { it.ordinal }.map { part -> part.label to (best?.sees(part, frame) == true) }
        val enough = bodies.count { b -> needs.all { b.sees(it, frame) } } >= needPeople
        if (enough) {
            if (fitSince == 0L) fitSince = t.now
            lastFitAt = t.now
        } else if (t.now - lastFitAt > DROPOUT_MS) {
            fitSince = 0L // a frame or two of missed points does not start the wait again
        }
        return skipped || (fitSince != 0L && t.now - fitSince >= FIT_HOLD_MS)
    }

    /** The part of the picture everyone must fit into: just in from the edges, where points get unreliable. */
    private fun frameFor(visible: RectF): RectF {
        val w = visible.width()
        val h = visible.height()
        val portrait = h >= w
        return RectF(
            visible.left + w * 0.03f,
            visible.top + h * (if (portrait) 0.08f else 0.04f),
            visible.right - w * 0.03f,
            visible.bottom - h * 0.02f,
        )
    }

    fun scene(now: Long): GameScene {
        val nobody = bodies.isEmpty()
        val card = Card(
            title = if (nobody) "Step in!" else "Step back!",
            kicker = gameName,
            subtitle = if (nobody) "Stand where I can see you." else "I need to see all of you.",
            checks = checks.ifEmpty { needs.sortedBy { it.ordinal }.map { it.label to false } },
        )
        val box = RectF(frame)
        return GameScene(card, null, front = { c -> drawFrame(c, box) }, mode = SceneMode.FIT, canSkip = canSkip)
    }

    // ---- drawing -------------------------------------------------------------------------------

    private val chalk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Yellow chalk corners to fit inside, and a handwritten reminder. */
    private fun drawFrame(c: GameCanvas, box: RectF) {
        val canvas = c.canvas
        val u = c.width.coerceAtMost(c.height) / 390f
        val l = c.fit.x(box.left)
        val r = c.fit.x(box.right)
        val left = minOf(l, r)
        val right = maxOf(l, r)
        val top = c.fit.y(box.top)
        val bottom = c.fit.y(box.bottom)
        chalk.color = Paper.YELLOW
        chalk.strokeWidth = 5f * u
        val len = 34f * u
        path.reset()
        path.moveTo(left, top + len); path.lineTo(left, top); path.lineTo(left + len, top)
        path.moveTo(right - len, top); path.lineTo(right, top); path.lineTo(right, top + len)
        path.moveTo(right, bottom - len); path.lineTo(right, bottom); path.lineTo(right - len, bottom)
        path.moveTo(left + len, bottom); path.lineTo(left, bottom); path.lineTo(left, bottom - len)
        canvas.drawPath(path, chalk)
        c.tools.drawCaptionScreen(c, "fit inside the yellow corners", c.width / 2f, top + 40f * u, 22f * u, Paper.YELLOW, -2f)
    }

    private companion object {
        const val FIT_HOLD_MS = 1000L
        const val DROPOUT_MS = 500L
        const val SKIP_AFTER_MS = 15_000L
    }
}
