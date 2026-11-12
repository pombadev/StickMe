package app.stickme.kids

import android.graphics.Color
import kotlin.math.hypot

/** Meteors fall faster and faster; dodge them. Three hearts, shared by everyone. One minute. */
class Meteors(kit: GameKit) : MiniGame(kit, GameKind.METEORS) {

    override val howToPlay = "Dodge the falling meteors!"
    override val scoreUnit = "dodged"

    /** Falls on its own from [born], so it can be drawn at any moment. Image coordinates. */
    class Meteor(
        private val startX: Float, private val startY: Float, val radius: Float,
        val speed: Float, val drift: Float, private val born: Long,
    ) {
        fun x(now: Long) = startX + drift * (now - born) / 1000f
        fun y(now: Long) = startY + speed * (now - born) / 1000f
    }

    private val meteors = ArrayList<Meteor>()
    private var playStart = 0L
    private var nextMeteorAt = 0L
    private var safeUntil = 0L
    var hearts = HEARTS
        private set

    internal fun meteorsNow(): List<Meteor> = meteors.toList()

    override fun onStart(t: Tick) {
        playStart = t.now
        nextMeteorAt = t.now + 500
    }

    override fun play(t: Tick): Boolean {
        val w = t.imageWidth.toFloat()
        val h = t.imageHeight.toFloat()
        val progress = ((t.now - playStart).toFloat() / GAME_MS).coerceIn(0f, 1f) // 0 → 1 over the game
        if (t.bodies.isEmpty()) {
            playStart += t.elapsed
        } else if (t.now >= nextMeteorAt) {
            val radius = w * (0.05f + kit.random.nextFloat() * 0.03f)
            meteors += Meteor(
                startX = radius + kit.random.nextFloat() * (w - 2 * radius),
                startY = -radius,
                radius = radius,
                speed = h / (2.6f - 1.0f * progress),
                drift = (kit.random.nextFloat() - 0.5f) * w * 0.15f,
                born = t.now,
            )
            nextMeteorAt = t.now + (1100 - 600 * progress).toLong()
        }

        val targets = t.bodies.flatMap { body ->
            BODY_PARTS.filter { body.ok(it) }.map { Triple(body.x(it), body.y(it), body.unit) }
        }
        val iterator = meteors.iterator()
        while (iterator.hasNext()) {
            val meteor = iterator.next()
            val x = meteor.x(t.now)
            val y = meteor.y(t.now)
            if (y > h + meteor.radius) {
                iterator.remove()
                score++
                continue
            }
            val hit = targets.any { (px, py, u) -> hypot(px - x, py - y) < meteor.radius + 0.12f * u }
            if (hit && t.now >= safeUntil) {
                iterator.remove()
                hearts--
                safeUntil = t.now + SAFE_AFTER_HIT_MS
                t.fx.burst(x, y, meteor.radius, t.now, Color.rgb(255, 120, 0))
                t.fx.pop("Ouch!", x, y - meteor.radius, meteor.radius * 1.3f, t.now)
                kit.sounds.play(Sound.MISS)
                if (hearts <= 0) return false
            }
        }
        return t.now - playStart < GAME_MS
    }

    override fun playScene(now: Long): GameScene {
        val falling = meteors.toList()
        val card = Card(
            "Meteors",
            subtitle = "❤️".repeat(hearts.coerceAtLeast(0)),
            tally = score, unit = scoreUnit,
            timeLeft = (1 - (now - playStart).toFloat() / GAME_MS).coerceIn(0f, 1f),
            highlight = now < safeUntil,
        )
        return GameScene(card, big, front = { c -> falling.forEach { drawMeteor(c, it) } })
    }

    private fun drawMeteor(c: GameCanvas, meteor: Meteor) {
        val fill = c.tools.fill
        val r = meteor.radius * c.fit.scale
        // A fiery tail: fading circles back along the path it came from.
        for (i in 4 downTo 1) {
            val back = c.now - i * 45L
            fill.color = Color.argb(60 - i * 10, 255, 160 - i * 20, 0)
            c.canvas.drawCircle(c.fit.x(meteor.x(back)), c.fit.y(meteor.y(back)), r * (1f - i * 0.12f), fill)
        }
        val x = c.fit.x(meteor.x(c.now))
        val y = c.fit.y(meteor.y(c.now))
        fill.color = Color.rgb(230, 74, 25)
        c.canvas.drawCircle(x, y, r, fill)
        fill.color = Color.rgb(255, 193, 7)
        c.canvas.drawCircle(x - r * 0.15f, y + r * 0.1f, r * 0.55f, fill)
        fill.color = Color.rgb(121, 85, 72)
        c.canvas.drawCircle(x + r * 0.35f, y - r * 0.3f, r * 0.18f, fill)
    }

    private companion object {
        const val HEARTS = 3
        const val GAME_MS = 60_000L
        const val SAFE_AFTER_HIT_MS = 1200L
        val BODY_PARTS = intArrayOf(0, 5, 6, 9, 10, 11, 12, 13, 14)
    }
}
