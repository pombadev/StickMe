package app.stickme.kids

import android.graphics.Color

/**
 * Layout of the 133 COCO-WholeBody points returned by the RTMPose whole-body model:
 *   0-16 body, 17-22 feet, 23-90 face (68 points), 91-111 left hand, 112-132 right hand.
 */
object Skeleton {
    const val POINT_COUNT = 133
    private const val FACE = 23
    private const val LEFT_HAND = 91
    private const val RIGHT_HAND = 112

    enum class Part { LEFT, RIGHT, TORSO, HIPS, HEAD, FACE, FINGER }

    class Bone(val from: Int, val to: Int, val part: Part, val shade: Int = 0)

    val bones: List<Bone> = buildList {
        fun chain(points: List<Int>, part: Part, shade: Int = 0) =
            points.zipWithNext { a, b -> add(Bone(a, b, part, shade)) }

        chain(listOf(5, 7, 9), Part.LEFT)
        chain(listOf(11, 13, 15), Part.LEFT)
        chain(listOf(6, 8, 10), Part.RIGHT)
        chain(listOf(12, 14, 16), Part.RIGHT)
        add(Bone(5, 6, Part.TORSO))
        add(Bone(5, 11, Part.TORSO))
        add(Bone(6, 12, Part.TORSO))
        add(Bone(11, 12, Part.HIPS))
        chain(listOf(3, 1, 0, 2, 4), Part.HEAD)

        listOf(17, 18, 19).forEach { add(Bone(15, it, Part.LEFT)) }
        listOf(20, 21, 22).forEach { add(Bone(16, it, Part.RIGHT)) }

        fun face(vararg indices: Int, closed: Boolean = false) {
            val points = indices.map { FACE + it }
            chain(if (closed) points + points.first() else points, Part.FACE)
        }
        face(*(0..16).toList().toIntArray())
        face(*(17..21).toList().toIntArray())
        face(*(22..26).toList().toIntArray())
        face(*(27..30).toList().toIntArray())
        face(*(31..35).toList().toIntArray())
        face(*(36..41).toList().toIntArray(), closed = true)
        face(*(42..47).toList().toIntArray(), closed = true)
        face(*(48..59).toList().toIntArray(), closed = true)
        face(*(60..67).toList().toIntArray(), closed = true)

        for (hand in listOf(LEFT_HAND, RIGHT_HAND)) {
            for (finger in 0 until 5) {
                val joints = (1..4).map { hand + finger * 4 + it }
                chain(listOf(hand) + joints, Part.FINGER, shade = finger)
            }
        }
    }

    /** Points that get a dot drawn on them (body, feet and hand joints; the face reads better as lines). */
    fun isJoint(index: Int) = index < FACE || index >= LEFT_HAND

    private val fingerColors = intArrayOf(
        Paper.PINK, Paper.PEACH, Paper.YELLOW, Paper.MINT, Paper.BLUE,
    )

    /** The chalk colours: mint down the left, blue down the right, a peach body and a pink belt. */
    fun neonColor(bone: Bone): Int = when (bone.part) {
        Part.LEFT, Part.HEAD -> Paper.MINT
        Part.RIGHT -> Paper.BLUE
        Part.TORSO -> Paper.PEACH
        Part.HIPS -> Paper.PINK
        Part.FACE -> Paper.CHALK
        Part.FINGER -> fingerColors[bone.shade]
    }
}
