package app.stickme.kids

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stickme.kids.TestPoses.pose
import app.stickme.kids.TestPoses.standing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Plays each game with made-up people and checks it reacts the way a kid would expect. */
@RunWith(AndroidJUnit4::class)
class GamesTest {

    private val sounds = SoundBoard().apply { muted = true }
    private val fx = FunEngine(SceneSettings(), GameKit(sounds, random = Random(3)))
    private var now = 10_000L

    @After
    fun release() = sounds.close()

    private fun step(vararg people: Person, ms: Long = 100): FunSnapshot =
        fx.update(people.toList(), now, 480, 640).also { now += ms }

    /** Starts [kind] and waits out the 3-2-1 countdown. */
    private inline fun <reified G : MiniGame> start(kind: GameKind): G {
        fx.games.requestStart(kind)
        repeat(30) { step(standing()) }
        return fx.games.activeGame as G
    }

    @Test
    fun bubblePopScoresWhenAHandTouchesABubble() {
        val game = start<BubblePop>(GameKind.BUBBLES)
        while (game.bubblesNow().isEmpty()) step(standing())
        val bubble = game.bubblesNow().first()
        val hand = pose(Body.LEFT_WRIST to (bubble.x(now) to bubble.y(now)))
        step(hand)
        assertTrue("score was ${game.score}", game.score in 1..3)
        assertTrue(game.bubblesNow().none { it === bubble })
    }

    @Test
    fun freezeDanceCatchesTheKidWhoKeepsMoving() {
        val game = start<FreezeDance>(GameKind.FREEZE)
        var wiggle = 0f
        while (game.isDancing) {
            wiggle = 40f - wiggle
            step(standing(id = 1, shift = wiggle), standing(id = 2, shift = -170f))
        }
        val pops = HashSet<TextPop>() // each word stays on screen for several frames; count it once
        repeat(40) { // 4 seconds of freeze: kid 1 keeps dancing, kid 2 stands still
            wiggle = 40f - wiggle
            pops += step(standing(id = 1, shift = wiggle), standing(id = 2, shift = -170f)).pops
        }
        assertEquals(1, pops.count { it.text == "Caught! 😜" })
        assertEquals("kid 2 froze, so the round counts", 1, game.score)
    }

    @Test
    fun jumpChallengeCountsEveryJumpWithoutTheBoingReaction() {
        val game = start<JumpChallenge>(GameKind.JUMPS)
        val pops = mutableListOf<String>()
        repeat(5) {
            repeat(3) { pops += step(standing()).pops.map { it.text } }
            repeat(2) { pops += step(standing(lift = 50f)).pops.map { it.text } }
        }
        repeat(3) { step(standing()) }
        assertEquals(5, game.score)
        assertFalse("move reactions should pause during games", "BOING!" in pops)
    }

    @Test
    fun stoppingAGameEndsItRightAway() {
        start<JumpChallenge>(GameKind.JUMPS)
        fx.games.requestStop()
        val snapshot = step(standing())
        assertEquals(null, snapshot.game)
        assertFalse(fx.games.isRunning)
    }
}
