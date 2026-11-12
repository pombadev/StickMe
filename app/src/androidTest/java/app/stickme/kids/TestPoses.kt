package app.stickme.kids

import android.graphics.RectF

/** Made-up people standing in a 480 x 640 picture, torso 130 px long, for driving games in tests. */
object TestPoses {

    private val STANDING = listOf(
        240f to 140f, 232f to 132f, 248f to 132f, 224f to 136f, 256f to 136f,
        210f to 190f, 270f to 190f, 200f to 250f, 280f to 250f, 195f to 305f, 285f to 305f,
        220f to 320f, 260f to 320f, 218f to 420f, 262f to 420f, 216f to 520f, 264f to 520f,
    )

    /** Standing still; [lift] moves the whole body up (a jump), [shift] moves it right. */
    fun standing(id: Int = 1, lift: Float = 0f, shift: Float = 0f): Person {
        val points = FloatArray(Skeleton.POINT_COUNT * 2) { i -> if (i % 2 == 0) 240f + shift else 140f - lift }
        val scores = FloatArray(Skeleton.POINT_COUNT)
        STANDING.forEachIndexed { k, (x, y) ->
            points[k * 2] = x + shift
            points[k * 2 + 1] = y - lift
            scores[k] = 0.9f
        }
        return Person(RectF(180f + shift, 110f - lift, 300f + shift, 540f - lift), points, scores, id)
    }

    /** Standing, with some points moved (point index to image x, y). */
    fun pose(vararg moves: Pair<Int, Pair<Float, Float>>, id: Int = 1, shift: Float = 0f): Person {
        val person = standing(id, shift = shift)
        val points = person.points.copyOf()
        val scores = person.scores.copyOf()
        for ((k, xy) in moves) {
            points[k * 2] = xy.first + shift
            points[k * 2 + 1] = xy.second
            scores[k] = 0.9f
        }
        return Person(person.box, points, scores, id)
    }
}
