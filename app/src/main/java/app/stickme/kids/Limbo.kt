package app.stickme.kids

import android.graphics.Color

/**
 * A glowing bar comes down; everyone ducks so their head stays under it. Each level the bar
 * stops lower. One head above the bar ends the game. 6 levels.
 */
class Limbo(kit: GameKit) : MiniGame(kit, GameKind.LIMBO) {

    override val howToPlay = "Duck under the bar!"

    private enum class Phase { LOWERING, HOLD, CHEER, OUT }

    private var phase = Phase.LOWERING
    private var phaseStart = 0L
    private var standingHeadY = Float.NaN // the tallest head, measured once, in image pixels
    private var unit = 0f
    private var barFrom = 0f
    private var barTo = 0f

    override fun onStart(t: Tick) {
        phaseStart = t.now
    }

    override fun play(t: Tick): Boolean {
        if (t.bodies.isEmpty()) {
            phaseStart += t.elapsed
            return true
        }
        if (standingHeadY.isNaN()) {
            val tallest = t.bodies.filter { it.headY != null }.minByOrNull { it.headY!! } ?: return true
            standingHeadY = tallest.headY!!
            unit = tallest.unit
            startLowering(t.now)
        }
        when (phase) {
            Phase.LOWERING -> if (t.now - phaseStart >= LOWER_MS) {
                phase = Phase.HOLD
                phaseStart = t.now
                show("Stay low!", t.now, Color.WHITE)
            }
            Phase.HOLD -> {
                val bonked = t.bodies.firstOrNull { body -> body.headY?.let { it < barTo } == true }
                if (bonked != null) {
                    bonked.aboveHead(0.2f)?.let { (x, y) -> t.fx.burst(x, y, 0.3f * bonked.unit, t.now, Color.YELLOW) }
                    show("Bonk! 😵", t.now, ORANGE)
                    kit.sounds.play(Sound.MISS)
                    endRound(t.now)
                    return true
                }
                if (t.now - phaseStart >= HOLD_MS) {
                    score++
                    show(CHEERS.random(kit.random), t.now, GREEN)
                    kit.sounds.play(Sound.YAY)
                    if (score >= LEVELS) {
                        endRound(t.now)
                        return true
                    }
                    phase = Phase.CHEER
                    phaseStart = t.now
                }
            }
            Phase.CHEER -> if (t.now - phaseStart >= CHEER_MS) startLowering(t.now)
            Phase.OUT -> return t.now - phaseStart < CHEER_MS // let the last word show before the results
        }
        return true
    }

    private fun endRound(now: Long) {
        phase = Phase.OUT
        phaseStart = now
    }

    private fun startLowering(now: Long) {
        barFrom = standingHeadY - 1.2f * unit
        barTo = standingHeadY + (0.35f + 0.3f * score) * unit
        phase = Phase.LOWERING
        phaseStart = now
    }

    override fun playScene(now: Long): GameScene {
        val ready = !standingHeadY.isNaN()
        val card = Card(
            "Limbo", "🎋",
            corner = "⭐ $score",
            subtitle = when {
                !ready -> "Stand in the picture!"
                phase == Phase.LOWERING -> "Level ${score + 1}: get ready to duck…"
                phase == Phase.HOLD -> "Stay under the bar! 🙇"
                phase == Phase.OUT -> ""
                else -> "Next level is lower!"
            },
            timeLeft = if (phase == Phase.HOLD) (1 - (now - phaseStart).toFloat() / HOLD_MS).coerceIn(0f, 1f) else null,
        )
        if (!ready) return GameScene(card, big)
        val from = barFrom
        val to = barTo
        val lowering = phase == Phase.LOWERING
        val start = phaseStart
        return GameScene(card, big, front = { c ->
            val progress = if (lowering) ((c.now - start).toFloat() / LOWER_MS).coerceIn(0f, 1f) else 1f
            drawBar(c, from + (to - from) * progress)
        })
    }

    private fun drawBar(c: GameCanvas, imageY: Float) {
        val y = c.fit.y(imageY)
        val thickness = c.width * 0.025f
        val stroke = c.tools.stroke
        stroke.strokeWidth = thickness * 3
        stroke.color = Color.argb(70, 0, 230, 118)
        c.canvas.drawLine(0f, y, c.width.toFloat(), y, stroke)
        stroke.strokeWidth = thickness
        // Candy stripes so the bar reads as a limbo pole.
        val stripe = c.width / 10f
        for (i in 0 until 10) {
            stroke.color = if (i % 2 == 0) Color.rgb(0, 230, 118) else Color.WHITE
            c.canvas.drawLine(i * stripe, y, (i + 1) * stripe, y, stroke)
        }
    }

    override fun resultText() = "🎋 $score"

    override fun resultSubtitle(newBest: Boolean) =
        (if (score >= LEVELS) "Limbo champion! " else "You reached level $score! ") +
            if (newBest) "New best! 🏆" else "Best: ${kit.scores.best(kind)}"

    private companion object {
        const val LEVELS = 6
        const val LOWER_MS = 2500L
        const val HOLD_MS = 2500L
        const val CHEER_MS = 1500L
    }
}
