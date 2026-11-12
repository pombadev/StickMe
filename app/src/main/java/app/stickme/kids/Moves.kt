package app.stickme.kids

import kotlin.math.hypot

/** Keeps one [T] per person (by [Person.id]) and forgets people who left the picture. */
class PerPerson<T>(private val make: () -> T) {
    private val items = HashMap<Int, T>()

    fun of(body: Body): T = items.getOrPut(body.person.id, make)

    fun forgetAllBut(bodies: List<Body>) {
        val here = bodies.mapTo(HashSet()) { it.person.id }
        items.keys.retainAll(here)
    }
}

/** Notices each jump by watching the hips rise above where they normally rest. */
class JumpDetector(private val riseToCount: Float = 0.25f) {
    private var restingHipY = Float.NaN
    private var inAir = false

    /** True on the frame a jump takes off. */
    fun update(body: Body): Boolean {
        if (!body.ok(11, 12)) return false
        val u = body.unit
        val hipY = (body.y(11) + body.y(12)) / 2
        if (restingHipY.isNaN()) restingHipY = hipY
        val rise = restingHipY - hipY
        var tookOff = false
        if (!inAir && rise > riseToCount * u) {
            inAir = true
            tookOff = true
        } else if (inAir && rise < 0.1f * u) {
            inAir = false
        }
        // Follow slow changes (walking closer, crouching) but not the jump itself.
        val follow = if (!inAir && rise < 0.15f * u) 0.2f else 0.03f
        restingHipY += (hipY - restingHipY) * follow
        return tookOff
    }
}

/** Notices a clap: hands far apart, then together within a moment. */
class ClapDetector {
    private var lastApart = 0L
    private var lastClap = 0L

    fun update(body: Body, now: Long): Boolean {
        if (!body.ok(Body.LEFT_WRIST, Body.RIGHT_WRIST)) return false
        val apart = body.dist(Body.LEFT_WRIST, Body.RIGHT_WRIST)
        if (apart > 1.0f * body.unit) {
            lastApart = now
        } else if (apart < 0.35f * body.unit && now - lastApart < 700 && now - lastClap > 800) {
            lastClap = now
            return true
        }
        return false
    }
}

/** How fast someone's body is moving, in torso-lengths per second. Used for freezing and holding still. */
class MovementMeter {
    private var last: FloatArray? = null
    private var lastScores: FloatArray? = null
    private var lastTime = 0L

    fun update(body: Body, now: Long): Float {
        val points = body.person.points
        val scores = body.person.scores
        val previous = last
        val previousScores = lastScores
        val seconds = (now - lastTime) / 1000f
        last = points
        lastScores = scores
        lastTime = now
        if (previous == null || previousScores == null || seconds <= 0f) return 0f
        var total = 0f
        var count = 0
        for (k in 0 until 17) {
            if (scores[k] < 0.3f || previousScores[k] < 0.3f) continue
            total += hypot(points[k * 2] - previous[k * 2], points[k * 2 + 1] - previous[k * 2 + 1])
            count++
        }
        if (count == 0) return 0f
        return total / count / body.unit / seconds
    }
}

/**
 * Where to put a number on someone's tummy, like a shirt number, in image coordinates (text baseline).
 * Unlike a spot above the head, this never runs into the game card at the top of the screen.
 */
fun Body.onTummy(): Pair<Float, Float>? {
    if (!ok(5, 6, 11, 12)) return null
    return (x(5) + x(6) + x(11) + x(12)) / 4 to (y(5) + y(6) + 3 * (y(11) + y(12))) / 8 + 0.15f * unit
}

/** Where to put a label above someone's head, in image coordinates. */
fun Body.aboveHead(lift: Float = 0.6f): Pair<Float, Float>? {
    val head = headY ?: return null
    val x = when {
        ok(0) -> x(0)
        ok(5, 6) -> (x(5) + x(6)) / 2
        else -> return null
    }
    return x to head - lift * unit
}
