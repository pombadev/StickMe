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

/** True when [part] of this body is seen clearly and inside [frame] (image pixels). */
fun Body.sees(part: Part, frame: RectF): Boolean {
    fun inside(k: Int) = ok(k) && x(k) in frame.left..frame.right && y(k) in frame.top..frame.bottom
    return when (part) {
        Part.HEAD -> inside(0)
        Part.HANDS -> inside(Body.LEFT_WRIST) && inside(Body.RIGHT_WRIST)
        Part.FEET -> inside(Body.LEFT_ANKLE) && inside(Body.RIGHT_ANKLE)
    }
}

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
        } else {
            fitSince = 0L
        }
        return skipped || (fitSince != 0L && t.now - fitSince >= FIT_HOLD_MS)
    }

    /** The part of the picture everyone must fit into: not behind the card at the top, not at the very edge. */
    private fun frameFor(visible: RectF): RectF {
        val w = visible.width()
        val h = visible.height()
        val portrait = h >= w
        return RectF(
            visible.left + w * 0.06f,
            visible.top + h * (if (portrait) 0.20f else 0.08f),
            visible.right - w * 0.06f,
            visible.bottom - h * 0.04f,
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
        const val SKIP_AFTER_MS = 15_000L
    }
}
