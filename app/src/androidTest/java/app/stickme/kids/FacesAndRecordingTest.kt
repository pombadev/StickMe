package app.stickme.kids

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Draws faces on a real photo (saved for a person to look at) and records a short video into the Gallery. */
@RunWith(AndroidJUnit4::class)
class FacesAndRecordingTest {

    companion object {
        private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var frame: SceneFrame

        @BeforeClass
        @JvmStatic
        fun findSkiers() {
            val photo = InstrumentationRegistry.getInstrumentation().context.assets
                .open("skiers.jpg").use { BitmapFactory.decodeStream(it) }
            val people = PoseEngine(appContext).use { PoseSmoother().smooth(it.findPeople(photo)) }
            frame = SceneFrame(people, photo, photo.width, photo.height, mirrored = false)
        }
    }

    @Test
    fun drawsEveryKindOfFace() {
        val outputFolder = File(appContext.getExternalFilesDir(null), "faces").apply { mkdirs() }
        val styles = FaceCatalog.all.filter { it !is FaceStyle.Emoji } + FaceCatalog.all.filterIsInstance<FaceStyle.Emoji>().take(2)
        for ((i, style) in styles.withIndex()) {
            val settings = SceneSettings().apply { face = style }
            val picture = Bitmap.createBitmap(frame.imageWidth, frame.imageHeight, Bitmap.Config.ARGB_8888)
            ScenePainter(settings).drawScene(Canvas(picture), picture.width, picture.height, frame, pictureAlpha = 150)
            File(outputFolder, "face_$i.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun rollingTheDiceGivesEveryPersonANewFace() {
        val ids = 1..6
        repeat(20) {
            val before = ids.map(FaceCatalog::forMix)
            FaceCatalog.rollNewMix()
            val after = ids.map(FaceCatalog::forMix)
            ids.forEachIndexed { i, id -> assertTrue("Person $id kept the same face", before[i] != after[i]) }
            assertTrue("Two people share a face", after.toSet().size == after.size)
        }
    }

    @Test
    fun recordsAVideoIntoTheGallery() {
        val recorder = VideoRecorder(appContext)
        val painter = ScenePainter(SceneSettings().apply { face = FaceStyle.Mix })
        recorder.start(720, 1280, withSound = false)
        repeat(45) {
            recorder.drawFrame { canvas -> painter.drawScene(canvas, recorder.width, recorder.height, frame, 150) }
            Thread.sleep(33)
        }
        val result = recorder.stopAndSave()
        assertTrue("Expected a saved video but got $result", result is RecordingResult.Saved)

        val uri = (result as RecordingResult.Saved).uri
        val durationMs = MediaMetadataRetriever().run {
            setDataSource(appContext, uri)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong().also { release() }
        }
        assertTrue("Video is only $durationMs ms long", durationMs in 1000..3000)
        appContext.contentResolver.delete(uri, null, null)
    }
}
