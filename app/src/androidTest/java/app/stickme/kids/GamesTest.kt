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
    private val settings = SceneSettings()
    private val fx = FunEngine(settings, GameKit(sounds, random = Random(3)))
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
    fun meteorsTakeAHeartWhenTheyHitAndCountTheOnesThatMiss() {
        val game = start<Meteors>(GameKind.METEORS)
        // Stand under the lowest meteor: it must hit.
        while (game.hearts == 3) {
            val target = game.meteorsNow().maxByOrNull { it.y(now) }
            step(standing(shift = (target?.x(now) ?: 240f) - 240f))
        }
        assertEquals(2, game.hearts)
        // Then stand still for a while: meteors that miss count as dodged.
        repeat(80) { step(standing(shift = -200f)) }
        assertTrue("dodged ${game.score}", game.score > 0)
    }

    @Test
    fun limboEndsWhenSomeoneStandsTallUnderTheBar() {
        val game = start<Limbo>(GameKind.LIMBO)
        repeat(80) { step(standing()) }
        assertEquals(0, game.score)
        assertEquals("You did it!", step(standing()).game?.card?.title)
    }

    @Test
    fun limboCountsLevelsWhileYouStayLowEnough() {
        val game = start<Limbo>(GameKind.LIMBO)
        repeat(3) { step(standing()) } // the game measures how tall everyone stands
        // Crouch 120 px: low enough for levels 1 and 2 (bar 45 and 85 px below the standing head), not level 3.
        repeat(250) { step(standing(lift = -120f)) }
        assertEquals(2, game.score)
    }

    @Test
    fun airPaintingDrawsWithARaisedFingerAndClapClears() {
        val game = start<AirPainting>(GameKind.PAINT)
        for (i in 0 until 10) step(pose(Body.RIGHT_INDEX_TIP to (300f + i * 8f to 200f), Body.RIGHT_WRIST to (290f + i * 8f to 230f)))
        assertTrue("painted ${game.paintedPoints}", game.paintedPoints >= 8)
        repeat(3) { step(pose(Body.LEFT_WRIST to (150f to 250f), Body.RIGHT_WRIST to (330f to 250f))) }
        step(pose(Body.LEFT_WRIST to (235f to 250f), Body.RIGHT_WRIST to (245f to 250f)))
        assertEquals(0, game.paintedPoints)
    }

    @Test
    fun workoutCountsJumpingJacksThenSquats() {
        val game = start<Workout>(GameKind.WORKOUT)
        assertEquals(Exercise.JUMPING_JACKS, game.currentExercise)
        val open = pose(9 to (190f to 100f), 10 to (290f to 100f), 15 to (150f to 520f), 16 to (330f to 520f))
        repeat(5) {
            step(open)
            step(standing())
        }
        assertEquals(5, game.score)

        while (game.currentExercise == Exercise.JUMPING_JACKS || game.isResting) step(standing())
        assertEquals(Exercise.SQUATS, game.currentExercise)
        val squat = pose(11 to (220f to 390f), 12 to (260f to 390f))
        repeat(4) {
            step(squat)
            step(standing())
        }
        assertEquals(9, game.score)
    }

    private fun balletPose(pose: BalletPose): Person = when (pose) {
        BalletPose.CROWN -> pose(9 to (235f to 90f), 10 to (250f to 90f))
        BalletPose.WINGS -> pose(9 to (60f to 200f), 10 to (420f to 200f))
        BalletPose.PLIE -> pose(11 to (220f to 360f), 12 to (260f to 360f), 13 to (170f to 410f), 14 to (310f to 410f))
        BalletPose.ARABESQUE -> pose(16 to (380f to 400f))
    }

    @Test
    fun everyBalletPoseIsRecognisedAndStandingIsNot() {
        for (pose in BalletPose.entries) {
            assertTrue("$pose not recognised", pose.matches(Body(balletPose(pose))))
            assertFalse("standing counted as $pose", pose.matches(Body(standing())))
        }
    }

    @Test
    fun balletClassGivesThreeStarsForAPerfectlyStillHoldAndOneForATwirl() {
        val game = start<BalletClass>(GameKind.BALLET)
        val asked = BalletPose.entries.first { it.title == step(standing()).game!!.card!!.title }
        repeat(25) { step(balletPose(asked)) }
        assertEquals(3, game.score)

        // TestPoses' standing figure has its left shoulder (5) on the picture's left, which is how
        // a person looks from behind. Swapping the shoulders makes them face the camera.
        val facingCamera = pose(5 to (270f to 190f), 6 to (210f to 190f))
        repeat(3) { step(facingCamera) }
        repeat(3) { step(standing()) } // turned around
        step(facingCamera)
        assertEquals("a twirl earns a bonus star", 4, game.score)
    }

    @Test
    fun drawsTheBallerinaLookForAPersonToLookAt() {
        settings.look = Look.BALLERINA
        var snapshot = step(standing())
        var dancer = standing()
        for (i in 0 until 12) {
            dancer = pose(9 to (150f + i * 6f to 120f + i * 12f), 10 to (330f - i * 8f to 140f + i * 6f))
            snapshot = step(dancer)
        }
        val folder = java.io.File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "games",
        ).apply { mkdirs() }
        val picture = android.graphics.Bitmap.createBitmap(720, 1280, android.graphics.Bitmap.Config.ARGB_8888)
        val frame = SceneFrame(listOf(dancer), null, 480, 640, mirrored = false, extras = snapshot)
        ScenePainter(settings).drawScene(android.graphics.Canvas(picture), 720, 1280, frame, 0, now)
        java.io.File(folder, "ballerina.png").outputStream().use {
            picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(snapshot.ribbons.isNotEmpty())
    }

    /** Saves a picture of every game in the middle of play, for a person to look at. */
    @Test
    fun drawsEveryGameForAPersonToLookAt() {
        val folder = java.io.File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "games",
        ).apply { mkdirs() }
        for (kind in GameKind.entries) {
            fx.games.requestStart(kind)
            var person = standing()
            var snapshot = step(person)
            repeat(30 + 45) { i ->
                person = when (kind) {
                    GameKind.PAINT -> pose(Body.RIGHT_INDEX_TIP to (300f + (i % 20) * 6f to 180f + (i % 7) * 10f))
                    GameKind.LIMBO -> if (i < 40) standing() else standing(lift = -120f)
                    else -> standing(shift = if (i % 2 == 0) 0f else 30f)
                }
                snapshot = step(person)
            }
            val picture = android.graphics.Bitmap.createBitmap(720, 1280, android.graphics.Bitmap.Config.ARGB_8888)
            val frame = SceneFrame(listOf(person), null, 480, 640, mirrored = false, extras = snapshot)
            ScenePainter(SceneSettings()).drawScene(android.graphics.Canvas(picture), 720, 1280, frame, 0, now)
            java.io.File(folder, "${kind.ordinal}_${kind.name}.png").outputStream().use {
                picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            fx.games.requestStop()
            step(standing())
        }
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
