package app.stickme.kids

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the move detection and the "Copy me!" game with made-up poses of one person
 * standing in a 480 x 640 picture (torso length 130 px), and saves pictures of the effects.
 */
@RunWith(AndroidJUnit4::class)
class FunTest {

    private val sounds = SoundBoard().apply { muted = true }
    private val settings = SceneSettings()
    private val fx = FunEngine(settings, GameKit(sounds, random = kotlin.random.Random(1)))
    private var now = 10_000L

    @After
    fun release() = sounds.close()

    private fun step(pose: Person, ms: Long = 100): FunSnapshot = fx.update(listOf(pose), now, 480, 640).also { now += ms }

    @Test
    fun standingStillDoesNothing() {
        repeat(30) { assertTrue(step(standing()).pops.isEmpty()) }
        PoseTarget.entries.forEach { assertFalse("Standing counted as $it", it.matches(Body(standing()))) }
    }

    @Test
    fun handsUpThrowsConfettiOnce() {
        repeat(5) { step(standing()) }
        val cheer = step(pose(9 to (215f to 60f), 10 to (265f to 60f)))
        assertEquals(listOf("YAY!"), cheer.pops.map { it.text })
        assertTrue(cheer.particles.size >= 50)
        val stillUp = step(pose(9 to (215f to 60f), 10 to (265f to 60f)))
        assertEquals("Holding hands up should not cheer again", 1, stillUp.pops.size)
    }

    @Test
    fun clappingMakesFireworks() {
        repeat(3) { step(pose(9 to (150f to 250f), 10 to (330f to 250f))) }
        val clap = step(pose(9 to (235f to 250f), 10 to (245f to 250f)))
        assertEquals(listOf("CLAP!"), clap.pops.map { it.text })
    }

    @Test
    fun jumpingGoesBoing() {
        repeat(10) { step(standing()) }
        val jump = step(standing(lift = 65f))
        assertEquals(listOf("BOING!"), jump.pops.map { it.text })
    }

    @Test
    fun everyGamePoseIsRecognised() {
        for (target in PoseTarget.entries) {
            assertTrue("$target not recognised", target.matches(Body(poseFor(target))))
        }
    }

    @Test
    fun aKidWhoCopiesEveryPoseWinsEveryStar() {
        val changes = mutableListOf<GameKind?>()
        fx.games.onChanged = { changes += it }
        fx.games.requestStart(GameKind.COPY_ME)
        var lastCard: Card? = null
        var nextPose = standing()
        repeat(1200) {
            val card = step(nextPose).game?.card ?: return@repeat
            lastCard = card
            val target = PoseTarget.entries.firstOrNull { it.title == card.title }
            nextPose = target?.let(::poseFor) ?: standing()
        }
        assertTrue(lastCard!!.subtitle, lastCard!!.subtitle.startsWith("You got 7 of 7 poses"))
        assertEquals(listOf(GameKind.COPY_ME, null), changes)
    }

    @Test
    fun drawsEffectsForAPersonToLookAt() {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "fun").apply { mkdirs() }
        fun save(name: String, person: Person, snapshot: FunSnapshot, look: Look = Look.NEON) {
            settings.look = look
            val picture = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            val frame = SceneFrame(listOf(person), null, 480, 640, mirrored = false, extras = snapshot)
            ScenePainter(settings).drawScene(Canvas(picture), 720, 1280, frame, 0, now + 150)
            File(folder, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        repeat(5) { step(standing()) }
        val up = pose(9 to (215f to 60f), 10 to (265f to 60f))
        save("1_hands_up", up, step(up))

        repeat(3) { step(pose(9 to (150f to 250f), 10 to (330f to 250f))) }
        val clap = pose(9 to (235f to 250f), 10 to (245f to 250f))
        save("2_clap", clap, step(clap))

        settings.look = Look.SPARKLE
        var snapshot = step(standing())
        for (i in 0..8) {
            val wave = pose(9 to (120f + i * 30f to 120f + (i % 3) * 30f), 10 to (360f - i * 25f to 200f))
            snapshot = step(wave)
            if (i == 8) save("3_sparkle", wave, snapshot, Look.SPARKLE)
        }

        settings.look = Look.ECHO
        for (i in 0..10) {
            val dance = standing(shift = i * 14f)
            snapshot = step(dance)
            if (i == 10) save("4_echo", dance, snapshot, Look.ECHO)
        }

        settings.look = Look.NEON
        fx.games.requestStart(GameKind.COPY_ME)
        repeat(40) { snapshot = step(standing()) }
        val target = PoseTarget.entries.first { it.title == snapshot.game!!.card!!.title }
        save("5_game_round", standing(), snapshot)
        repeat(7) { snapshot = step(poseFor(target)) }
        save("6_game_win", poseFor(target), snapshot)
    }

    private fun poseFor(target: PoseTarget): Person = when (target) {
        PoseTarget.HANDS_UP -> pose(9 to (215f to 60f), 10 to (265f to 60f))
        PoseTarget.STAR -> pose(9 to (120f to 110f), 10 to (360f to 110f), 15 to (170f to 520f), 16 to (310f to 520f))
        PoseTarget.AIRPLANE -> pose(7 to (130f to 192f), 8 to (350f to 192f), 9 to (60f to 195f), 10 to (420f to 195f))
        PoseTarget.FLAMINGO -> pose(14 to (285f to 380f), 16 to (264f to 440f))
        PoseTarget.HANDS_ON_HEAD -> pose(9 to (225f to 150f), 10 to (255f to 150f))
        PoseTarget.TOUCH_TOES -> pose(9 to (220f to 460f), 10 to (260f to 460f))
        PoseTarget.SUPERHERO -> pose(9 to (215f to 60f))
    }

    private fun pose(vararg changes: Pair<Int, Pair<Float, Float>>): Person =
        standing().let { person ->
            val points = person.points.copyOf()
            for ((k, xy) in changes) {
                points[k * 2] = xy.first
                points[k * 2 + 1] = xy.second
            }
            Person(person.box, points, person.scores, id = 1)
        }

    private fun standing(lift: Float = 0f, shift: Float = 0f): Person {
        val body = listOf(
            240f to 140f, 232f to 132f, 248f to 132f, 224f to 136f, 256f to 136f,
            210f to 190f, 270f to 190f, 200f to 250f, 280f to 250f, 195f to 305f, 285f to 305f,
            220f to 320f, 260f to 320f, 218f to 420f, 262f to 420f, 216f to 520f, 264f to 520f,
        )
        val points = FloatArray(Skeleton.POINT_COUNT * 2) { i -> if (i % 2 == 0) 240f + shift else 140f - lift }
        val scores = FloatArray(Skeleton.POINT_COUNT)
        body.forEachIndexed { k, (x, y) ->
            points[k * 2] = x + shift
            points[k * 2 + 1] = y - lift
            scores[k] = 0.9f
        }
        return Person(RectF(180f + shift, 110f - lift, 300f + shift, 540f - lift), points, scores, id = 1)
    }
}
