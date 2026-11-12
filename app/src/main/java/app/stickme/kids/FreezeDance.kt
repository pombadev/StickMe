package app.stickme.kids

import android.graphics.Color

/**
 * Music plays and everyone dances; when it stops, everyone freezes. Anyone who moves during
 * the freeze gets caught. A star for every freeze where someone stayed still. 6 rounds.
 */
class FreezeDance(kit: GameKit) : MiniGame(kit, GameKind.FREEZE) {

    override val howToPlay = "Dance! When the music stops, FREEZE!"
    override val scoreUnit = "freezes"

    private val meters = PerPerson { MovementMeter() }
    private val caught = HashSet<Int>()
    private var round = 0
    private var dancing = true
    private var stageEnd = 0L
    private var freezeStart = 0L

    internal val isDancing get() = dancing

    override fun onStart(t: Tick) = startDancing(t.now)

    override fun play(t: Tick): Boolean {
        if (t.bodies.isEmpty()) stageEnd += t.elapsed // nobody in the picture: wait for them
        meters.forgetAllBut(t.bodies)
        for (body in t.bodies) {
            val speed = meters.of(body).update(body, t.now)
            val id = body.person.id
            val settled = t.now - freezeStart > FREEZE_GRACE_MS
            if (!dancing && settled && speed > MOVING_SPEED && id !in caught) {
                caught += id
                body.aboveHead()?.let { (x, y) -> t.fx.pop("Caught! 😜", x, y, 0.45f * body.unit, t.now) }
                kit.sounds.play(Sound.MISS)
            }
        }
        if (t.now < stageEnd) return true
        if (dancing) {
            startFreezing(t.now)
            return true
        }
        val someoneFroze = t.bodies.any { it.person.id !in caught }
        if (someoneFroze) {
            score++
            show("Great freeze! ⭐", t.now, Color.rgb(128, 222, 234))
            kit.sounds.play(Sound.YAY)
        }
        round++
        if (round >= ROUNDS) return false
        startDancing(t.now)
        return true
    }

    private fun startDancing(now: Long) {
        dancing = true
        stageEnd = now + 4000 + kit.random.nextInt(4000)
        show("DANCE! 💃", now, Color.rgb(255, 64, 129))
        kit.sounds.startLoop(Sound.DANCE_LOOP)
    }

    private fun startFreezing(now: Long) {
        dancing = false
        freezeStart = now
        stageEnd = now + FREEZE_MS
        caught.clear()
        kit.sounds.stopLoop()
        kit.sounds.play(Sound.FREEZE)
        show("FREEZE! 🧊", now, Color.rgb(128, 222, 234))
    }

    override fun onStop() = kit.sounds.stopLoop()

    override fun playScene(now: Long): GameScene {
        val card = Card(
            "Freeze Dance", 
            tally = score, unit = scoreUnit,
            subtitle = if (dancing) "Dance, dance, dance!" else "Don't move a bit!",
            highlight = !dancing,
        )
        return GameScene(card, big, hint = if (dancing) "keep dancing!" else "hold still...")
    }

    private companion object {
        const val ROUNDS = 6
        const val FREEZE_MS = 3500L
        const val FREEZE_GRACE_MS = 600L // time to stop moving after the music ends

        /** Torso-lengths per second; above this counts as moving. A guess, to tune with real kids. */
        const val MOVING_SPEED = 0.6f
    }
}
