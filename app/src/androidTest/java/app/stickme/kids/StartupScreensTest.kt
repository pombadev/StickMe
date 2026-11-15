package app.stickme.kids

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stickme.kids.TestPoses.pose
import app.stickme.kids.TestPoses.standing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** Saves pictures of the start-up screens (waking up, step back, results) for a person to look at. */
@RunWith(AndroidJUnit4::class)
class StartupScreensTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.getExternalFilesDir(null), "startup").apply { mkdirs() }
    private val sounds = SoundBoard().apply { muted = true }
    private val settings = SceneSettings()
    private val fx = FunEngine(settings, GameKit(sounds, random = Random(2)))
    private var now = 10_000L

    @After
    fun release() = sounds.close()

    private fun save(name: String, bitmap: Bitmap) =
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** The part of the 480 x 640 picture that shows on a 780 x 1688 screen (the picture is cropped to fill it). */
    private val visible: android.graphics.RectF = run {
        val scale = maxOf(780 / 480f, 1688 / 640f)
        val w = 780 / scale
        val h = 1688 / scale
        android.graphics.RectF((480 - w) / 2, (640 - h) / 2, (480 + w) / 2, (640 + h) / 2)
    }

    private fun step(vararg people: Person) = fx.update(people.toList(), now, 480, 640, visible).also { now += 100 }

    private fun draw(name: String, snapshot: FunSnapshot, people: List<Person>) {
        val picture = Bitmap.createBitmap(780, 1688, Bitmap.Config.ARGB_8888)
        val frame = SceneFrame(people, null, 480, 640, mirrored = false, extras = snapshot)
        ScenePainter(settings).drawScene(Canvas(picture), 780, 1688, frame, 0, now)
        save(name, picture)
    }

    @Test
    fun drawsTheWakingUpScreen() {
        Fonts.init(context)
        val art = ChalkArtView(context).apply { scene = ChalkArtView.Scene.WAKE }
        art.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY))
        art.layout(0, 0, 780, 1688)
        val picture = Bitmap.createBitmap(780, 1688, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        Chalkboard.draw(canvas, 780, 1688)
        art.draw(canvas)
        save("A_wake", picture)
    }

    @Test
    fun drawsStepBackWithAChecklist() {
        val feetOut = pose(15 to (216f to 636f), 16 to (264f to 636f))
        fx.games.requestStart(GameKind.JUMPS)
        step(feetOut)
        val scene = step(feetOut)
        assertEquals(SceneMode.FIT, scene.game?.mode)
        draw("D_step_back", scene, listOf(feetOut))
    }

    /** Draws the real results layout at phone size. */
    private fun drawResults(name: String, info: ResultInfo, width: Int = 780, height: Int = 1688) {
        Fonts.init(context)
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_StickMe)
        val host = android.widget.FrameLayout(themed)
        android.view.LayoutInflater.from(themed).inflate(R.layout.overlays, host, true)
        val screen = host.findViewById<View>(R.id.resultsScreen)
        host.findViewById<View>(R.id.wakeScreen).visibility = View.GONE
        ResultsScreen(screen, {}, {}).show(info)
        screen.animate().cancel()
        screen.alpha = 1f
        screen.visibility = View.VISIBLE
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(picture))
        save(name, picture)
    }

    @Test
    fun drawsTheResultsScreens() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            drawResults("R_new_best", ResultInfo(GameKind.FREEZE, 12, "freezes", newBest = true, previousBest = 9))
            drawResults("R_not_best", ResultInfo(GameKind.JUMPS, 7, "jumps", newBest = false, previousBest = 15))
        }
    }

    /** The real play screen while the app is still looking for someone. */
    @Test
    fun drawsTheLookingForAFriendState() {
        Fonts.init(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = android.view.ContextThemeWrapper(context, R.style.Theme_StickMe)
            val host = android.widget.FrameLayout(themed)
            host.background = ChalkboardDrawable()
            android.view.LayoutInflater.from(themed).inflate(R.layout.activity_main, host, true)
            host.findViewById<View>(R.id.wakeScreen).visibility = View.GONE
            host.findViewById<View>(R.id.looking).visibility = View.VISIBLE
            host.findViewById<android.widget.TextView>(R.id.lookingDots).text = "•  •"
            host.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 780, 1688)
            val picture = Bitmap.createBitmap(780, 1688, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(picture))
            save("L_looking", picture)
        }
    }
}
