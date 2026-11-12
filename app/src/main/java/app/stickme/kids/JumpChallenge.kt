package app.stickme.kids

import android.graphics.Color

/** Jump as many times as you can in 30 seconds. Everyone gets their own counter; the score is the total. */
class JumpChallenge(kit: GameKit) : MiniGame(kit, GameKind.JUMPS) {

    override val howToPlay = "Jump as many times as you can!"

    private class Counter(val x: Float, val y: Float, val count: Int, val size: Float)

    private val detectors = PerPerson { JumpDetector() }
    private val jumps = HashMap<Int, Int>()
    private var counters: List<Counter> = emptyList()
    private var playStart = 0L

    override fun onStart(t: Tick) {
        playStart = t.now
    }

    override fun play(t: Tick): Boolean {
        if (t.bodies.isEmpty()) playStart += t.elapsed
        detectors.forgetAllBut(t.bodies)
        for (body in t.bodies) {
            if (detectors.of(body).update(body)) {
                val id = body.person.id
                jumps[id] = (jumps[id] ?: 0) + 1
                score++
                kit.sounds.play(Sound.DING)
            }
        }
        counters = t.bodies.mapNotNull { body ->
            val (x, y) = body.aboveHead(0.7f) ?: return@mapNotNull null
            Counter(x, y, jumps[body.person.id] ?: 0, 0.6f * body.unit)
        }
        return t.now - playStart < GAME_MS
    }

    override fun playScene(now: Long): GameScene {
        val shown = counters
        val card = Card(
            "Jump Challenge", "🦘", corner = "🦘 $score",
            timeLeft = (1 - (now - playStart).toFloat() / GAME_MS).coerceIn(0f, 1f),
        )
        return GameScene(card, big, front = { c ->
            shown.forEach { c.tools.drawLabel(c, it.count.toString(), it.x, it.y, it.size, Color.rgb(255, 235, 59)) }
        })
    }

    override fun resultText() = "🦘 $score"

    override fun resultSubtitle(newBest: Boolean) =
        "$score jumps! " + if (newBest) "New best! 🏆" else "Best: ${kit.scores.best(kind)}"

    private companion object {
        const val GAME_MS = 30_000L
    }
}
