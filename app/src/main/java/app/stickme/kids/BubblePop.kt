package app.stickme.kids

import android.graphics.Color
import kotlin.math.hypot
import kotlin.math.sin

/** Bubbles float up; pop them with your hands. Golden bubbles are worth 3. One minute. */
class BubblePop(kit: GameKit) : MiniGame(kit, GameKind.BUBBLES) {

    override val howToPlay = "Pop the bubbles with your hands!"

    /** Moves on its own from [born], so it can be drawn at any moment. Image coordinates. */
    class Bubble(
        private val startX: Float, private val startY: Float, val radius: Float,
        private val speed: Float, private val sway: Float, val golden: Boolean, private val born: Long,
    ) {
        fun x(now: Long) = startX + sin((now - born) / 500f + sway) * radius * 0.6f
        fun y(now: Long) = startY - speed * (now - born) / 1000f
    }

    private val bubbles = ArrayList<Bubble>()
    private var playStart = 0L
    private var nextBubbleAt = 0L

    internal fun bubblesNow(): List<Bubble> = bubbles.toList()

    override fun onStart(t: Tick) {
        playStart = t.now
        nextBubbleAt = t.now
    }

    override fun play(t: Tick): Boolean {
        val w = t.imageWidth.toFloat()
        val h = t.imageHeight.toFloat()
        if (t.bodies.isEmpty()) {
            playStart += t.elapsed // nobody in the picture: pause the clock and the bubbles
        } else if (t.now >= nextBubbleAt) {
            val radius = w * kit.random.nextFloat().let { 0.06f + it * 0.03f }
            bubbles += Bubble(
                startX = radius + kit.random.nextFloat() * (w - 2 * radius),
                startY = h + radius,
                radius = radius,
                speed = h / (3.5f + kit.random.nextFloat() * 2f),
                sway = kit.random.nextFloat() * 6f,
                golden = kit.random.nextInt(10) == 0,
                born = t.now,
            )
            nextBubbleAt = t.now + 350 + kit.random.nextInt(400)
        }

        val hands = t.bodies.flatMap { body ->
            HANDS.filter { body.ok(it) }.map { Triple(body.x(it), body.y(it), body.unit) }
        }
        val iterator = bubbles.iterator()
        while (iterator.hasNext()) {
            val bubble = iterator.next()
            val x = bubble.x(t.now)
            val y = bubble.y(t.now)
            if (y < -bubble.radius) {
                iterator.remove()
                continue
            }
            val touched = hands.any { (hx, hy, u) -> hypot(hx - x, hy - y) < bubble.radius + 0.12f * u }
            if (touched) {
                iterator.remove()
                val points = if (bubble.golden) 3 else 1
                score += points
                t.fx.burst(x, y, bubble.radius, t.now, if (bubble.golden) YELLOW else Color.rgb(130, 220, 255))
                t.fx.pop("+$points", x, y - bubble.radius, bubble.radius * 1.2f, t.now)
                kit.sounds.play(Sound.POP)
            }
        }
        return t.now - playStart < GAME_MS
    }

    override fun playScene(now: Long): GameScene {
        val floating = bubbles.toList()
        val card = Card(
            "Bubble Pop", "🫧", corner = "⭐ $score",
            timeLeft = (1 - (now - playStart).toFloat() / GAME_MS).coerceIn(0f, 1f),
        )
        return GameScene(card, big, front = { c -> floating.forEach { drawBubble(c, it) } })
    }

    private fun drawBubble(c: GameCanvas, bubble: Bubble) {
        val x = c.fit.x(bubble.x(c.now))
        val y = c.fit.y(bubble.y(c.now))
        val r = bubble.radius * c.fit.scale
        val fill = c.tools.fill
        val ring = c.tools.stroke
        fill.color = if (bubble.golden) Color.argb(150, 255, 214, 0) else Color.argb(45, 150, 220, 255)
        c.canvas.drawCircle(x, y, r, fill)
        ring.color = if (bubble.golden) Color.rgb(255, 235, 59) else Color.argb(210, 220, 245, 255)
        ring.strokeWidth = r * 0.08f
        c.canvas.drawCircle(x, y, r, ring)
        fill.color = Color.argb(200, 255, 255, 255)
        c.canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.14f, fill)
    }

    private companion object {
        const val GAME_MS = 60_000L
        val HANDS = intArrayOf(Body.LEFT_WRIST, Body.RIGHT_WRIST, Body.LEFT_INDEX_TIP, Body.RIGHT_INDEX_TIP)
    }
}
