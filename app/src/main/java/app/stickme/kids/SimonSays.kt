package app.stickme.kids

/**
 * The phone says "Simon says: hands up!" and everyone does it. Sometimes it leaves out
 * "Simon says" — then doing it costs a heart. 10 rounds, 3 hearts.
 */
class SimonSays(kit: GameKit) : MiniGame(kit, GameKind.SIMON) {

    override val howToPlay = "Only move when Simon says!"
    override val solo = true
    override val scoreUnit = "right"

    private var round = 0
    private var command = PoseTarget.HANDS_UP
    private var simonSaid = true
    private var roundStart = 0L
    private var holdStart: Long? = null
    private var cheeringUntil: Long? = null
    var hearts = HEARTS
        private set

    internal val currentCommand get() = command
    internal val isSimonSaying get() = simonSaid
    internal val isCheering get() = cheeringUntil != null

    override fun onStart(t: Tick) = startRound(t)

    override fun play(t: Tick): Boolean {
        cheeringUntil?.let { until ->
            if (t.now < until) return true
            cheeringUntil = null
            if (hearts <= 0 || round + 1 >= ROUNDS) return false
            round++
            startRound(t)
        }
        if (t.bodies.isEmpty()) roundStart += t.elapsed

        if (t.bodies.any(command.matches)) {
            val since = holdStart ?: t.now.also { holdStart = it }
            if (t.now - since >= HOLD_MS) {
                if (simonSaid) {
                    win("Well done! ⭐", t)
                } else {
                    hearts--
                    show("Simon didn't say! 😜", t.now, ORANGE)
                    kit.sounds.play(Sound.MISS)
                    cheeringUntil = t.now + CHEER_MS
                }
                return true
            }
        } else {
            holdStart = null
        }

        if (t.now - roundStart >= ROUND_MS) {
            if (simonSaid) {
                show("Too slow! 🐢", t.now, ORANGE)
                kit.sounds.play(Sound.MISS)
                cheeringUntil = t.now + CHEER_MS
            } else {
                win("You didn't fall for it! ⭐", t)
            }
        }
        return true
    }

    private fun win(text: String, t: Tick) {
        score++
        show(text, t.now, GREEN)
        kit.sounds.play(Sound.TADA)
        cheeringUntil = t.now + CHEER_MS
    }

    private fun startRound(t: Tick) {
        roundStart = t.now
        holdStart = null
        val choices = COMMANDS.filter { it != command }
        // The first two rounds are always real, so kids learn the game before the tricks start.
        simonSaid = round < 2 || kit.random.nextFloat() < 0.7f
        // A trick must not be a pose someone is already in (for example still holding the last one).
        val fair = if (simonSaid) choices else choices.filter { pose -> t.bodies.none(pose.matches) }
        command = (fair.ifEmpty { choices }).random(kit.random)
        if (!simonSaid && fair.isEmpty()) simonSaid = true
        val words = command.title.removeSuffix("!").lowercase()
        kit.speak(if (simonSaid) "Simon says, $words!" else "$words!")
    }

    override fun playScene(now: Long): GameScene {
        val cheering = cheeringUntil != null
        val hearts = "❤️".repeat(hearts.coerceAtLeast(0))
        val card = Card(
            command.title, command.picture,
            tally = score, unit = scoreUnit,
            subtitle = (if (simonSaid) "🗣️ Simon says!" else "🤔 Did Simon say it?") + "   $hearts",
            timeLeft = if (cheering) null else (1 - (now - roundStart).toFloat() / ROUND_MS).coerceIn(0f, 1f),
            highlight = holdStart != null && !cheering,
        )
        return GameScene(card, big)
    }

    private companion object {
        const val ROUNDS = 10
        const val HEARTS = 3
        const val ROUND_MS = 4500L
        const val HOLD_MS = 400L
        const val CHEER_MS = 1600L
        val COMMANDS = listOf(
            PoseTarget.HANDS_UP, PoseTarget.HANDS_ON_HEAD, PoseTarget.AIRPLANE, PoseTarget.TOUCH_TOES,
            PoseTarget.FLAMINGO, PoseTarget.SUPERHERO, PoseTarget.STAR,
        )
    }
}
