package app.stickme.kids

import android.graphics.Canvas
import android.graphics.Color
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
 * The "get ready" stage of every game. Solo games with several people in view first ask for hands up:
 * whoever holds them up while the yellow line fills is the player. Then everyone who plays must be
 * fully in view ("Step back!") before the countdown starts.
 */
class GameGate(
    private val kit: GameKit,
    private val gameName: String,
    private val solo: Boolean,
    private val needs: Set<Part>,
    private val needPeople: Int,
) {
    private enum class Phase { PICK, FIT }

    var player: Int? = null
        private set

    private var phase = Phase.FIT
    private var candidate: Int? = null
    private var candidateSince = 0L
    private var pickProgress = 0f
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

        if (solo && player == null) {
            when {
                bodies.size == 1 -> player = bodies[0].person.id
                bodies.size >= 2 -> pick(t)
            }
        }
        phase = if (solo && player == null && bodies.size >= 2) Phase.PICK else Phase.FIT
        if (phase == Phase.PICK) return false

        val subjects = if (solo) bodies.filter { it.person.id == player } else bodies
        val best = subjects.maxByOrNull { b -> needs.count { b.sees(it, frame) } }
        checks = needs.sortedBy { it.ordinal }.map { part -> part.label to (best?.sees(part, frame) == true) }
        val enough = subjects.count { b -> needs.all { b.sees(it, frame) } } >= needPeople
        if (enough) {
            if (fitSince == 0L) fitSince = t.now
        } else {
            fitSince = 0L
        }
        return skipped || (fitSince != 0L && t.now - fitSince >= FIT_HOLD_MS)
    }

    private fun pick(t: Tick) {
        val raised = bodies.filter { PoseTarget.HANDS_UP.matches(it) }.maxByOrNull { it.person.box.height() }?.person?.id
        if (raised != candidate) {
            candidate = raised
            candidateSince = t.now
        }
        pickProgress = if (candidate == null) 0f else ((t.now - candidateSince) / PICK_HOLD_MS.toFloat()).coerceIn(0f, 1f)
        if (candidate != null && pickProgress >= 1f) {
            player = candidate
            kit.sounds.play(Sound.TADA)
        }
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
        if (phase == Phase.PICK) return pickScene()
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

    private fun pickScene(): GameScene {
        val card = Card(
            title = "Hands up to start!",
            kicker = gameName,
            subtitle = "Keep them up while the yellow line fills.",
        )
        val shown = bodies
        val chosen = candidate
        val progress = pickProgress
        return GameScene(card, null, front = { c -> drawPick(c, shown, chosen, progress) }, mode = SceneMode.PICK, canSkip = false)
    }

    // ---- drawing -------------------------------------------------------------------------------

    private val chalk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val arc = RectF()

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

    private fun drawPick(c: GameCanvas, people: List<Body>, chosen: Int?, progress: Float) {
        val canvas = c.canvas
        val u = c.width.coerceAtMost(c.height) / 390f
        for (body in people) {
            val head = body.headY ?: continue
            val x = if (body.ok(0)) body.x(0) else (body.x(5) + body.x(6)) / 2
            val sx = c.fit.x(x)
            val sy = c.fit.y(head)
            val reach = body.unit * c.fit.scale * 1.5f
            val isChosen = body.person.id == chosen
            if (isChosen) {
                // The yellow line: a half circle over their head that fills as they keep their hands up.
                arc.set(sx - reach, sy - reach - reach * 0.2f, sx + reach, sy + reach - reach * 0.2f)
                chalk.strokeWidth = 9f * u
                chalk.color = Color.argb(60, 255, 226, 122)
                canvas.drawArc(arc, 180f, 180f, false, chalk)
                chalk.color = Paper.YELLOW
                canvas.drawArc(arc, 180f, 180f * progress, false, chalk)
                drawCrown(canvas, sx, arc.top - 6f * u, body.unit * c.fit.scale * 0.8f)
                c.tools.drawCaption(c, "you're playing!", x, footY(body), 22f * u, Paper.INK, -4f, sticker = true)
            } else if (chosen != null) {
                c.tools.drawCaption(c, "watching", x, footY(body), 21f * u, Paper.CHALK_DIM, 3f)
            }
        }
    }

    private fun footY(body: Body) = minOf(body.person.box.bottom + body.unit * 0.3f, body.person.box.bottom + 80f)

    private fun drawCrown(canvas: Canvas, cx: Float, bottom: Float, width: Float) {
        val s = width / 68f
        canvas.save()
        canvas.translate(cx - 34f * s, bottom - 44f * s)
        canvas.rotate(-8f, 34f * s, 22f * s)
        canvas.scale(s, s)
        path.set(androidx.core.graphics.PathParser.createPathFromPathData("M4 40L2 10l16 14L34 2l16 22L66 10l-2 30z") ?: return)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paper.YELLOW }
        canvas.drawPath(path, fill)
        chalk.color = Paper.PAPER_LIGHT
        chalk.strokeWidth = 3.5f
        canvas.drawPath(path, chalk)
        fill.color = Paper.RED
        canvas.drawCircle(34f, 28f, 4f, fill)
        canvas.restore()
    }

    private companion object {
        const val PICK_HOLD_MS = 1500L
        const val FIT_HOLD_MS = 1000L
        const val SKIP_AFTER_MS = 15_000L
    }
}
