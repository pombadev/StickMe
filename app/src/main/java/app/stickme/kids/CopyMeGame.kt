package app.stickme.kids

/** Shows a pose; the first person to hold it for a moment wins a star. 7 poses per game. */
class CopyMeGame(kit: GameKit) : MiniGame(kit, GameKind.COPY_ME) {

    override val howToPlay = "Copy the poses!"

    private val poses = PoseTarget.entries.shuffled(kit.random)
    private var round = -1
    private var roundStart = 0L
    private var holdStart: Long? = null
    private var cheeringUntil: Long? = null
    private var lastRoundWon = false

    override fun onStart(t: Tick) = nextRound(t.now)

    override fun play(t: Tick): Boolean {
        cheeringUntil?.let { until ->
            if (t.now < until) return true
            cheeringUntil = null
            if (round + 1 >= poses.size) return false
            nextRound(t.now)
        }
        val goal = poses[round]
        if (t.bodies.isEmpty()) roundStart += t.elapsed // nobody in the picture: pause the clock
        if (t.bodies.any(goal.matches)) {
            val since = holdStart ?: t.now.also { holdStart = it }
            if (t.now - since >= HOLD_MS) {
                score++
                lastRoundWon = true
                show(CHEERS.random(kit.random), t.now, GREEN)
                t.fx.confettiRain(t.now, t.imageWidth, t.imageHeight)
                kit.sounds.play(Sound.TADA)
                cheeringUntil = t.now + CHEER_MS
            }
        } else {
            holdStart = null
        }
        if (cheeringUntil == null && t.now - roundStart > ROUND_MS) {
            lastRoundWon = false
            show("Next one!", t.now, ORANGE)
            kit.sounds.play(Sound.MISS)
            cheeringUntil = t.now + CHEER_MS
        }
        return true
    }

    private fun nextRound(now: Long) {
        round++
        roundStart = now
        holdStart = null
        if (round > 0) {
            show("GO!", now, android.graphics.Color.WHITE)
            kit.sounds.play(Sound.GO)
        }
    }

    override fun playScene(now: Long): GameScene {
        val goal = poses[round]
        val cheering = cheeringUntil != null
        val card = Card(
            goal.title, goal.emoji, goal.picture,
            corner = "⭐ $score",
            subtitle = if (holdStart != null && !cheering) "Hold it… 🤩" else "",
            timeLeft = if (cheering) null else (1 - (now - roundStart).toFloat() / ROUND_MS).coerceIn(0f, 1f),
            highlight = if (cheering) lastRoundWon else holdStart != null,
        )
        return GameScene(card, big)
    }

    override fun resultSubtitle(newBest: Boolean) =
        "You got $score of ${poses.size} poses" + if (newBest) " 🏆" else ""

    private companion object {
        const val ROUND_MS = 9000L
        const val HOLD_MS = 500L
        const val CHEER_MS = 1600L
    }
}
