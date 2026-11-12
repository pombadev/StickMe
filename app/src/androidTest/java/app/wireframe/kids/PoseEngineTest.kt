package app.wireframe.kids

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/**
 * Runs the real models on a photo of five skiers and checks the body points against
 * the ones the Python reference pipeline produced for the same photo.
 */
@RunWith(AndroidJUnit4::class)
class PoseEngineTest {

    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    // The reference comes from the medium model; the tiny one lands ~3.4px away from it in Python.
    @Test
    fun mediumModelMatchesThePythonReference() = checkAgainstReference(PoseModel.MEDIUM, allowedTypicalMiss = 6f)

    @Test
    fun tinyModelStaysCloseToTheMediumReference() = checkAgainstReference(PoseModel.TINY, allowedTypicalMiss = 8f)

    private fun checkAgainstReference(model: PoseModel, allowedTypicalMiss: Float) {
        val photo = testAssets.open("skiers.jpg").use { BitmapFactory.decodeStream(it) }
        val expected = testAssets.open("skiers_expected.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { line -> line.split(" ").map { pair -> pair.split(",").map(String::toFloat) } }

        PoseEngine(appContext, model).use { engine ->
            engine.findPeople(photo) // first run includes one-time setup; time the second
            val started = System.nanoTime()
            val people = engine.findPeople(photo)
            Log.i("PoseEngineTest", "$model: 4-person frame took ${(System.nanoTime() - started) / 1_000_000} ms")

            assertEquals("The app keeps the 4 most certain people", 4, people.size)
            val misses = mutableListOf<Float>()
            for (person in people) {
                val reference = expected.minBy { hypot(person.points[0] - it[0][0], person.points[1] - it[0][1]) }
                for (k in 0 until 17) {
                    misses += hypot(person.points[k * 2] - reference[k][0], person.points[k * 2 + 1] - reference[k][1])
                }
            }
            val typicalMiss = misses.sorted()[misses.size / 2]
            Log.i("PoseEngineTest", "$model: typical miss ${typicalMiss}px, worst ${misses.max()}px")
            assertTrue("$model body points drift ${typicalMiss}px from the reference", typicalMiss < allowedTypicalMiss)
        }
    }
}
