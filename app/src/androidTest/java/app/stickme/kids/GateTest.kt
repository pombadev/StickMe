package app.stickme.kids

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stickme.kids.TestPoses.pose
import app.stickme.kids.TestPoses.standing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** The get-ready stage, the results screen's numbers, and that the app's colours match its resources. */
@RunWith(AndroidJUnit4::class)
class GateTest {

    private val sounds = SoundBoard().apply { muted = true }
    private val fx = FunEngine(SceneSettings(), GameKit(sounds, random = Random(5)))
    private var now = 10_000L

    @After
    fun release() = sounds.close()

    private fun step(vararg people: Person, ms: Long = 100): FunSnapshot =
        fx.update(people.toList(), now, 480, 640).also { now += ms }

    private val feetOutOfView = pose(15 to (216f to 636f), 16 to (264f to 636f))
    private val handsUp = { id: Int, shift: Float -> pose(9 to (215f to 60f), 10 to (265f to 60f), id = id, shift = shift) }

    @Test
    fun theGameWaitsUntilTheWholeBodyIsInView() {
        fx.games.requestStart(GameKind.JUMPS)
        repeat(30) { step(feetOutOfView) }
        val waiting = step(feetOutOfView).game!!
        assertEquals(SceneMode.FIT, waiting.mode)
        assertEquals("Step back!", waiting.card?.title)
        assertEquals(listOf("head" to true, "hands" to true, "feet" to false), waiting.card?.checks)
        assertFalse(fx.games.activeGame!!.isPlaying)

        var guard = 0
        while (fx.games.activeGame?.isPlaying != true && guard++ < 100) step(standing())
        assertTrue("the game should start once everyone is in view", fx.games.activeGame!!.isPlaying)
    }

    @Test
    fun gamesThatOnlyNeedHandsAndHeadIgnoreTheFeet() {
        fx.games.requestStart(GameKind.BUBBLES)
        var guard = 0
        while (fx.games.activeGame?.isPlaying != true && guard++ < 100) step(feetOutOfView)
        assertTrue(fx.games.activeGame!!.isPlaying)
    }

    @Test
    fun anEmptyPictureAsksSomeoneToStepIn() {
        fx.games.requestStart(GameKind.JUMPS)
        val scene = step().game!!
        assertEquals("Step in!", scene.card?.title)
    }

    @Test
    fun playAnywayAppearsAfterAWhileAndStartsTheGame() {
        fx.games.requestStart(GameKind.JUMPS)
        assertFalse(step(feetOutOfView).game!!.canSkip)
        repeat(160) { step(feetOutOfView) }
        assertTrue(step(feetOutOfView).game!!.canSkip)
        fx.games.requestSkipGate()
        var guard = 0
        while (fx.games.activeGame?.isPlaying != true && guard++ < 100) step(feetOutOfView)
        assertTrue(fx.games.activeGame!!.isPlaying)
    }

    @Test
    fun withTwoPeopleEveryGameStartsWithoutPickingAPlayer() {
        for (kind in GameKind.entries) {
            fx.games.requestStart(kind)
            var guard = 0
            while (fx.games.activeGame?.isPlaying != true && guard++ < 150) step(standing(id = 1, shift = 80f), standing(id = 2, shift = -140f))
            assertTrue("$kind should start with two people in view", fx.games.activeGame!!.isPlaying)
            fx.games.requestStop()
            step()
        }
    }

    @Test
    fun whenOnlyOneOfTwoPeopleStaysUnderTheLimboBarItStillCounts() {
        fx.games.requestStart(GameKind.LIMBO)
        var guard = 0
        while (fx.games.activeGame?.isPlaying != true && guard++ < 150) step(standing(id = 1, shift = 80f), standing(id = 2, shift = -140f))
        val game = fx.games.activeGame as Limbo
        repeat(3) { step(standing(id = 1, shift = 80f), standing(id = 2, shift = -140f)) }
        // Person 1 keeps standing tall; person 2 crouches low enough for two levels.
        repeat(250) { step(standing(id = 1, shift = 80f), standing(id = 2, lift = -120f, shift = -140f)) }
        assertEquals(2, game.score)
    }

    @Test
    fun anyoneInViewCanAirPaint() {
        fx.games.requestStart(GameKind.PAINT)
        var guard = 0
        while (fx.games.activeGame?.isPlaying != true && guard++ < 150) step(standing(id = 1, shift = 80f), standing(id = 2, shift = -140f))
        val game = fx.games.activeGame as AirPainting
        repeat(10) { i -> // only person 1 paints
            step(pose(Body.RIGHT_INDEX_TIP to (330f + i * 8f to 200f), Body.RIGHT_WRIST to (320f to 230f), id = 1, shift = 80f), standing(id = 2, shift = -140f))
        }
        assertTrue(game.paintedPoints >= 8)
    }

    @Test
    fun resultsRememberTheBestScoreAcrossGames() {
        fun playOneJumpGame(jumps: Int): ResultInfo {
            fx.games.requestStart(GameKind.JUMPS)
            var guard = 0
            while (fx.games.activeGame?.isPlaying != true && guard++ < 200) step(standing())
            repeat(jumps) {
                repeat(3) { step(standing()) }
                repeat(2) { step(standing(lift = 50f)) }
            }
            guard = 0
            var scene: GameScene? = null
            while (scene?.mode != SceneMode.RESULT && guard++ < 400) scene = step(standing()).game
            return scene!!.result!!
        }

        val first = playOneJumpGame(2)
        assertEquals(2, first.score)
        assertTrue(first.newBest)
        assertEquals(0, first.previousBest)
        assertEquals("jumps", first.unit)

        val second = playOneJumpGame(1)
        assertEquals(1, second.score)
        assertFalse(second.newBest)
        assertEquals(2, second.previousBest)
    }

    @Test
    fun theCodesColoursMatchTheResourceColours() {
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        assertEquals(res.getColor(R.color.chalkboard, null), Paper.CHALKBOARD)
        assertEquals(res.getColor(R.color.chalk, null), Paper.CHALK)
        assertEquals(res.getColor(R.color.paper, null), Paper.PAPER)
        assertEquals(res.getColor(R.color.ink, null), Paper.INK)
        assertEquals(res.getColor(R.color.yellow, null), Paper.YELLOW)
        assertEquals(res.getColor(R.color.pink, null), Paper.PINK)
        assertEquals(res.getColor(R.color.mint, null), Paper.MINT)
        assertEquals(res.getColor(R.color.blue, null), Paper.BLUE)
        assertEquals(res.getColor(R.color.peach, null), Paper.PEACH)
        assertEquals(res.getColor(R.color.red, null), Paper.RED)
        assertEquals(res.getColor(R.color.cardboard, null), Paper.CARDBOARD)
    }
}
