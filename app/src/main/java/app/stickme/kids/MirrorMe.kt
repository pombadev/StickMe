package app.stickme.kids

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Two players: the leader (👑) makes a pose, the other copies it. The app scores how alike the
 * two poses are (up to 3 stars). The leader swaps every round. 6 rounds; needs exactly 2 people.
 */
class MirrorMe(kit: GameKit) : MiniGame(kit, GameKind.MIRROR) {

    override val howToPlay = "2 players: the leader makes a pose, the other copies!"

    private enum class Phase { POSE, COPY, SCORE }

    private class Crown(val x: Float, val y: Float, val size: Float)

    private var phase = Phase.POSE
    private var phaseStart = 0L
    private var round = 0
    private var crown: Crown? = null
    private var waitingForTwo = false

    override fun onStart(t: Tick) = startRound(t.now)

    override fun play(t: Tick): Boolean {
        val players = t.bodies.sortedBy { it.centerX() }
        waitingForTwo = players.size != 2
        if (waitingForTwo) {
            phaseStart += t.elapsed
            crown = null
            return true
        }
        val leader = players[round % 2]
        val copier = players[1 - round % 2]
        crown = leader.aboveHead(0.55f)?.let { (x, y) -> Crown(x, y, 0.5f * leader.unit) }

        when (phase) {
            Phase.POSE -> if (t.now - phaseStart >= POSE_MS) {
                phase = Phase.COPY
                phaseStart = t.now
                show("Copy it! 🪞", t.now, Color.WHITE)
                kit.sounds.play(Sound.GO)
            }
            Phase.COPY -> if (t.now - phaseStart >= COPY_MS) {
                val match = similarity(leader, copier)
                val stars = when {
                    match >= 85 -> 3
                    match >= 70 -> 2
                    match >= 50 -> 1
                    else -> 0
                }
                score += stars
                show("$match% " + if (stars > 0) "⭐".repeat(stars) else "😅", t.now, if (stars > 0) GREEN else ORANGE)
                kit.sounds.play(if (stars > 0) Sound.TADA else Sound.MISS)
                if (stars == 3) t.fx.confettiRain(t.now, t.imageWidth, t.imageHeight)
                phase = Phase.SCORE
                phaseStart = t.now
            }
            Phase.SCORE -> if (t.now - phaseStart >= SCORE_MS) {
                round++
                if (round >= ROUNDS) return false
                startRound(t.now)
            }
        }
        return true
    }

    private fun startRound(now: Long) {
        phase = Phase.POSE
        phaseStart = now
        if (round > 0) {
            show("New leader! 👑", now, Color.rgb(255, 213, 79))
            kit.sounds.play(Sound.TICK)
        }
    }

    override fun playScene(now: Long): GameScene {
        val card = when {
            waitingForTwo -> Card("Mirror Me", "👯", subtitle = "Need exactly 2 people in the picture!")
            phase == Phase.POSE -> Card(
                "Leader 👑: make a pose!", "🪞", corner = "⭐ $score",
                subtitle = "Round ${round + 1} of $ROUNDS",
                timeLeft = (1 - (now - phaseStart).toFloat() / POSE_MS).coerceIn(0f, 1f),
            )
            phase == Phase.COPY -> Card(
                "Copy the leader!", "🪞", corner = "⭐ $score",
                timeLeft = (1 - (now - phaseStart).toFloat() / COPY_MS).coerceIn(0f, 1f),
                highlight = true,
            )
            else -> Card("Mirror Me", "🪞", corner = "⭐ $score", subtitle = "Round ${round + 1} of $ROUNDS")
        }
        val shownCrown = crown
        return GameScene(card, big, front = { c ->
            shownCrown?.let { c.tools.drawLabel(c, "👑", it.x, it.y, it.size, Color.WHITE) }
        })
    }

    override fun resultSubtitle(newBest: Boolean) =
        "Team score: $score stars! " + if (newBest) "New best! 🏆" else "Best: ${kit.scores.best(kind)}"

    companion object {
        private const val ROUNDS = 6
        private const val POSE_MS = 5000L
        private const val COPY_MS = 4000L
        private const val SCORE_MS = 2200L

        /** Arm and leg pieces compared between the two players. */
        private val LIMBS = listOf(5 to 7, 7 to 9, 6 to 8, 8 to 10, 11 to 13, 13 to 15, 12 to 14, 14 to 16)

        /** Each limb with its partner on the other side of the body, for comparing mirror images. */
        private val OTHER_SIDE = listOf(2, 3, 0, 1, 6, 7, 4, 5)

        private fun Body.centerX() = if (ok(5, 6)) (x(5) + x(6)) / 2 else person.box.centerX()

        /**
         * How alike two poses are, 0-100%, by comparing the direction of each arm and leg piece.
         * Copying like a mirror (raising the opposite arm) counts as well as copying exactly.
         */
        internal fun similarity(a: Body, b: Body): Int {
            fun angle(body: Body, limb: Int): Float? {
                val (from, to) = LIMBS[limb]
                if (!body.ok(from, to)) return null
                return atan2(body.y(to) - body.y(from), body.x(to) - body.x(from))
            }
            fun alike(x: Float, y: Float): Float {
                var diff = abs(x - y) % (2 * PI.toFloat())
                if (diff > PI) diff = 2 * PI.toFloat() - diff
                return (1 + cos(diff)) / 2
            }
            fun compare(mirrored: Boolean): Float? {
                var total = 0f
                var weights = 0f
                var counted = 0
                for (limb in LIMBS.indices) {
                    val first = angle(a, limb) ?: continue
                    val second = (if (mirrored) angle(b, OTHER_SIDE[limb])?.let { PI.toFloat() - it } else angle(b, limb)) ?: continue
                    // Kids pose mostly with their arms, and two standing kids' legs always match, so arms count double.
                    val weight = if (limb < 4) 2f else 1f
                    total += alike(first, second) * weight
                    weights += weight
                    counted++
                }
                return if (counted < 4) null else total / weights
            }
            val best = listOfNotNull(compare(false), compare(true)).maxOrNull() ?: return 0
            // Map so that random poses land low and close copies land high.
            return ((best - 0.5f) / 0.5f * 100).coerceIn(0f, 100f).roundToInt()
        }
    }
}
