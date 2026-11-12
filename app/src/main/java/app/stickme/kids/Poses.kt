package app.stickme.kids

import kotlin.math.abs
import kotlin.math.min

/** A stick-figure drawing in a 100 x 100 box: a head and some lines (each line is x,y,x,y,...). */
class Pictogram(val headX: Float, val headY: Float, val lines: List<FloatArray>) {
    companion object {
        private val SPINE = floatArrayOf(50f, 24f, 50f, 58f)
        private val LEGS = floatArrayOf(40f, 94f, 50f, 58f, 60f, 94f)

        fun standing(arms: FloatArray, legs: FloatArray = LEGS) = Pictogram(50f, 15f, listOf(SPINE, arms, legs))
    }
}

/** Poses for the "Copy me!" game, each with a picture and a check on the real person. */
enum class PoseTarget(val title: String, val emoji: String, val picture: Pictogram, val matches: (Body) -> Boolean) {
    HANDS_UP(
        "Hands up!", "🙌",
        Pictogram.standing(floatArrayOf(34f, 3f, 50f, 30f, 66f, 3f)),
        { b ->
            val head = b.headY
            head != null && b.ok(9, 10) && b.y(9) < head - 0.1f * b.unit && b.y(10) < head - 0.1f * b.unit
        },
    ),
    STAR(
        "Star jump!", "⭐",
        Pictogram.standing(floatArrayOf(14f, 8f, 50f, 30f, 86f, 8f), floatArrayOf(20f, 94f, 50f, 58f, 80f, 94f)),
        { b ->
            val shoulders = b.shoulderY
            shoulders != null && b.ok(9, 10, 15, 16) && b.y(9) < shoulders && b.y(10) < shoulders &&
                abs(b.x(9) - b.x(10)) > 1.8f * b.unit && abs(b.x(15) - b.x(16)) > 0.8f * b.unit
        },
    ),
    AIRPLANE(
        "Airplane!", "✈️",
        Pictogram.standing(floatArrayOf(6f, 30f, 94f, 30f), floatArrayOf(44f, 94f, 50f, 58f, 56f, 94f)),
        { b ->
            b.ok(5, 6, 9, 10) && abs(b.y(9) - b.y(5)) < 0.4f * b.unit && abs(b.y(10) - b.y(6)) < 0.4f * b.unit &&
                abs(b.x(9) - b.x(10)) > 2.0f * b.unit
        },
    ),
    FLAMINGO(
        "Flamingo!", "🦩",
        Pictogram.standing(floatArrayOf(34f, 48f, 50f, 30f, 66f, 48f), floatArrayOf(50f, 94f, 50f, 58f, 68f, 70f, 54f, 80f)),
        { b -> b.ok(11, 12, 15, 16) && abs(b.y(15) - b.y(16)) > 0.35f * b.unit },
    ),
    HANDS_ON_HEAD(
        "Hands on head!", "🙆",
        Pictogram.standing(floatArrayOf(43f, 6f, 30f, 20f, 50f, 30f, 70f, 20f, 57f, 6f)),
        { b ->
            val shoulders = b.shoulderY
            shoulders != null && b.ok(0, 9, 10) && b.y(9) < shoulders && b.y(10) < shoulders &&
                b.dist(9, 0) < 0.9f * b.unit && b.dist(10, 0) < 0.9f * b.unit
        },
    ),
    TOUCH_TOES(
        "Touch your toes!", "🙇",
        Pictogram(
            76f, 60f,
            listOf(floatArrayOf(40f, 46f, 67f, 56f), floatArrayOf(58f, 92f, 67f, 56f, 72f, 92f), floatArrayOf(34f, 94f, 40f, 46f, 46f, 94f)),
        ),
        { b -> b.ok(9, 10, 13, 14) && min(b.y(9), b.y(10)) > (b.y(13) + b.y(14)) / 2 },
    ),
    SUPERHERO(
        "Superhero!", "🦸",
        Pictogram.standing(floatArrayOf(68f, 2f, 50f, 30f, 38f, 56f)),
        { b ->
            val head = b.headY
            val shoulders = b.shoulderY
            head != null && shoulders != null && b.ok(9, 10) && run {
                val leftUp = b.y(9) < head - 0.1f * b.unit
                val rightUp = b.y(10) < head - 0.1f * b.unit
                val leftDown = b.y(9) > shoulders + 0.3f * b.unit
                val rightDown = b.y(10) > shoulders + 0.3f * b.unit
                (leftUp && rightDown) || (rightUp && leftDown)
            }
        },
    ),
}
