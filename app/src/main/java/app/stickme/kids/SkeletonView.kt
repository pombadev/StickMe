package app.stickme.kids

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** Shows the stick figures on screen, on top of (or instead of) the camera preview. */
class SkeletonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var settings = SceneSettings()
        set(value) { field = value; painter = ScenePainter(value) }

    private var painter = ScenePainter(settings)

    @Volatile private var frame = SceneFrame.EMPTY

    /** Called from the camera thread with the newest result. */
    fun show(newFrame: SceneFrame) {
        frame = newFrame
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val current = frame
        val now = SystemClock.uptimeMillis()
        painter.drawPeople(canvas, width, height, current, now)
        // Keep redrawing between camera frames while something moves on its own.
        val rainbowMoving = settings.look == Look.RAINBOW && current.people.isNotEmpty()
        if (rainbowMoving || current.extras.isAnimating(now)) postInvalidateOnAnimation()
    }
}
