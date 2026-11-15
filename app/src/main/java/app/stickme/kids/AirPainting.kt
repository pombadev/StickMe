package app.stickme.kids

import android.graphics.Color
import android.graphics.Path
import kotlin.math.hypot

/**
 * Draw in the air: a raised index finger paints glowing lines. Clap to wipe the picture clean.
 * No timer and no score; it goes on until ⏹.
 */
class AirPainting(kit: GameKit) : MiniGame(kit, GameKind.PAINT) {

    override val howToPlay = "Draw with your finger! Clap to clear."
    override val needs = Part.UPPER
    override val scoreUnit = "points"

    /** One line of paint. Points are x,y pairs in image pixels. */
    private class Stroke(val color: Int, val width: Float) {
        val points = ArrayList<Float>()
        var lastTouch = 0L
        fun lastX() = points[points.size - 2]
        fun lastY() = points[points.size - 1]
    }

    private class FrozenStroke(val color: Int, val width: Float, val points: FloatArray)

    private val strokes = ArrayList<Stroke>()
    private val activeStrokes = HashMap<Int, Stroke>() // per person and hand
    private val clapDetectors = PerPerson { ClapDetector() }
    private var colorTurn = 0

    internal val paintedPoints get() = strokes.sumOf { it.points.size / 2 }

    override fun play(t: Tick): Boolean {
        clapDetectors.forgetAllBut(t.bodies)
        for (body in t.bodies) {
            if (clapDetectors.of(body).update(body, t.now)) {
                wipe(body, t)
                continue
            }
            for ((side, finger, wrist) in HANDS) {
                val k = if (body.ok(finger)) finger else if (body.ok(wrist)) wrist else continue
                if (!handIsUp(body, k)) continue
                paint(body, side, body.x(k), body.y(k), t.now)
            }
        }
        var total = strokes.sumOf { it.points.size }
        while (total > MAX_POINTS * 2 && strokes.size > 1) total -= strokes.removeAt(0).points.size
        return true
    }

    /** Only paint while the hand is lifted well above the hips, so resting hands don't scribble. */
    private fun handIsUp(body: Body, k: Int): Boolean {
        val hips = if (body.ok(11, 12)) (body.y(11) + body.y(12)) / 2 else return true
        return body.y(k) < hips - 0.25f * body.unit // clearly lifted: hands hanging at the sides must not scribble
    }

    private fun paint(body: Body, side: Int, x: Float, y: Float, now: Long) {
        val key = body.person.id * 2 + side
        val u = body.unit
        var stroke = activeStrokes[key]
        val jumped = stroke != null && hypot(x - stroke.lastX(), y - stroke.lastY()) > 0.8f * u
        if (stroke == null || now - stroke.lastTouch > BREAK_MS || jumped) {
            stroke = Stroke(Color.HSVToColor(floatArrayOf((colorTurn++ * 47f) % 360, 0.8f, 1f)), 0.07f * u)
            stroke.points += x
            stroke.points += y
            strokes += stroke
            activeStrokes[key] = stroke
        } else if (hypot(x - stroke.lastX(), y - stroke.lastY()) > 0.02f * u) {
            stroke.points += x
            stroke.points += y
        }
        stroke.lastTouch = now
    }

    private fun wipe(body: Body, t: Tick) {
        if (strokes.isEmpty()) return
        strokes.clear()
        activeStrokes.clear()
        body.aboveHead()?.let { (x, y) -> t.fx.burst(x, y, 0.5f * body.unit, t.now, Color.WHITE) }
        show("All clean! ✨", t.now, Color.WHITE)
        kit.sounds.play(Sound.POP)
    }

    override fun playScene(now: Long): GameScene {
        val picture = strokes.map { FrozenStroke(it.color, it.width, it.points.toFloatArray()) }
        val card = Card("Air Painting", subtitle = "Lift a finger to paint · clap to clear")
        return GameScene(card, big, behind = { c -> picture.forEach { drawStroke(c, it) } })
    }

    private fun drawStroke(c: GameCanvas, stroke: FrozenStroke) {
        val pen = c.tools.stroke
        val width = stroke.width * c.fit.scale
        if (stroke.points.size < 4) {
            c.tools.fill.color = stroke.color
            c.canvas.drawCircle(c.fit.x(stroke.points[0]), c.fit.y(stroke.points[1]), width / 2, c.tools.fill)
            return
        }
        val path = Path()
        path.moveTo(c.fit.x(stroke.points[0]), c.fit.y(stroke.points[1]))
        for (i in 2 until stroke.points.size step 2) path.lineTo(c.fit.x(stroke.points[i]), c.fit.y(stroke.points[i + 1]))
        pen.color = stroke.color
        pen.alpha = 70
        pen.strokeWidth = width * 3
        c.canvas.drawPath(path, pen)
        pen.alpha = 255
        pen.strokeWidth = width
        c.canvas.drawPath(path, pen)
    }

    private companion object {
        const val BREAK_MS = 400L
        const val MAX_POINTS = 4000
        /** (side, index fingertip, wrist) for both hands. */
        val HANDS = listOf(
            Triple(0, Body.LEFT_INDEX_TIP, Body.LEFT_WRIST),
            Triple(1, Body.RIGHT_INDEX_TIP, Body.RIGHT_WRIST),
        )
    }
}
