package app.stickme.kids

import android.graphics.RectF

/**
 * Blends each person's points with where that same person was last frame, so the
 * stick figures glide instead of shaking. People are matched by how much their boxes overlap.
 */
class PoseSmoother(private val keepFromLastFrame: Float = 0.45f) {

    private var previous: List<Person> = emptyList()
    private var nextId = 1

    fun smooth(current: List<Person>): List<Person> {
        val unclaimed = previous.toMutableList()
        val result = current.map { person ->
            val match = unclaimed.maxByOrNull { overlap(it.box, person.box) }
                ?.takeIf { overlap(it.box, person.box) > 0.3f }
                ?: return@map Person(person.box, person.points, person.scores, nextId++)
            unclaimed.remove(match)
            blend(match, person)
        }
        previous = result
        return result
    }

    fun reset() {
        previous = emptyList()
    }

    private fun blend(old: Person, new: Person): Person {
        val keep = keepFromLastFrame
        val points = FloatArray(new.points.size) { i -> old.points[i] * keep + new.points[i] * (1 - keep) }
        val scores = FloatArray(new.scores.size) { i -> old.scores[i] * keep + new.scores[i] * (1 - keep) }
        return Person(new.box, points, scores, old.id)
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val shared = w * h
        return shared / (a.width() * a.height() + b.width() * b.height() - shared)
    }
}
