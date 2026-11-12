package app.stickme.kids

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private fun Random.nextFloat(from: Float, until: Float) = from + nextFloat() * (until - from)

/** Maps upright camera-image pixels to a drawing area the way the camera preview does (fill and crop). */
class ImageFit(private val width: Int, height: Int, imageWidth: Int, imageHeight: Int, private val mirrored: Boolean) {
    val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
    val offsetX = (width - imageWidth * scale) / 2
    val offsetY = (height - imageHeight * scale) / 2

    fun x(imageX: Float): Float {
        val screenX = imageX * scale + offsetX
        return if (mirrored) width - screenX else screenX
    }

    fun y(imageY: Float): Float = imageY * scale + offsetY
}

/** Easy questions about one person's pose. Distances are in image pixels; [unit] is their torso length. */
class Body(val person: Person) {
    fun ok(vararg points: Int) = points.all { person.scores[it] > 0.3f }
    fun x(k: Int) = person.points[k * 2]
    fun y(k: Int) = person.points[k * 2 + 1]
    fun dist(a: Int, b: Int) = hypot(x(a) - x(b), y(a) - y(b))

    val unit: Float = if (ok(5, 6, 11, 12)) {
        hypot((x(5) + x(6) - x(11) - x(12)) / 2, (y(5) + y(6) - y(11) - y(12)) / 2)
    } else {
        person.box.height() * 0.3f
    }.coerceAtLeast(10f)

    /** Height of the face, or a guess from the shoulders. Smaller y is higher up. */
    val headY: Float? = when {
        ok(0) -> y(0)
        ok(5, 6) -> min(y(5), y(6)) - 0.7f * unit
        else -> null
    }

    val shoulderY: Float? = if (ok(5, 6)) (y(5) + y(6)) / 2 else null

    companion object {
        const val NOSE = 0
        const val LEFT_WRIST = 9
        const val RIGHT_WRIST = 10
        const val LEFT_ANKLE = 15
        const val RIGHT_ANKLE = 16
        const val LEFT_INDEX_TIP = 99
        const val RIGHT_INDEX_TIP = 120
    }
}

enum class ParticleShape { CONFETTI, STAR, SPARK }

/** A bit of confetti or a star. Moves on its own from [born] on, so it can be drawn at any moment. */
class Particle(
    val x: Float, val y: Float, val vx: Float, val vy: Float, val gravity: Float,
    val born: Long, val lifeMs: Long, val size: Float, val color: Int, val shape: ParticleShape, val spin: Float,
) {
    fun isAlive(now: Long) = now - born in 0 until lifeMs
}

/** A comic word like "BOING!" that bounces in above someone. */
class TextPop(val text: String, val x: Float, val y: Float, val size: Float, val color: Int, val born: Long) {
    fun isAlive(now: Long) = now - born in 0 until LIFE_MS

    companion object {
        const val LIFE_MS = 1100L
    }
}

/** Everything fun about one frame, frozen so the screen and the video recorder can both draw it safely. */
class FunSnapshot(
    val particles: List<Particle>,
    val pops: List<TextPop>,
    val echoes: List<List<Person>>,
    val game: GameScene?,
    val ribbons: List<Ribbon> = emptyList(),
) {
    fun isAnimating(now: Long) = game != null || particles.any { it.isAlive(now) } || pops.any { it.isAlive(now) }

    companion object {
        val EMPTY = FunSnapshot(emptyList(), emptyList(), emptyList(), null)
    }
}

/**
 * Watches people move and answers with effects: hands up → confetti, clap → fireworks, jump → stars.
 * Also makes sparkle trails, remembers recent poses for the echo look, and runs the games
 * (move reactions pause during a game so they don't get in its way).
 * Use from the camera thread only.
 */
class FunEngine(private val settings: SceneSettings, kit: GameKit) {

    private val sounds = kit.sounds
    val games = GameHost(kit)

    private val particles = ArrayList<Particle>()
    private val pops = ArrayList<TextPop>()
    private val echoes = ArrayDeque<List<Person>>()
    private val ribbons = RibbonTrails()
    private val trackers = HashMap<Int, MoveTracker>()
    private val random = Random.Default

    private class MoveTracker {
        var lastSeen = 0L
        var handsWereUp = false
        var lastHandsUp = 0L
        var lastWristsApart = 0L
        var lastClap = 0L
        var hipRestingY = Float.NaN
        var lastJump = 0L
        val trailPoints = HashMap<Int, Pair<Float, Float>>()
    }

    private var lastUpdate = 0L

    /** [visible] is the part of the picture shown on screen, in image pixels (everything, if not given). */
    fun update(people: List<Person>, now: Long, imageWidth: Int, imageHeight: Int, visible: android.graphics.RectF? = null): FunSnapshot {
        val elapsed = if (lastUpdate == 0L) 0L else now - lastUpdate
        lastUpdate = now
        particles.removeAll { !it.isAlive(now) }
        pops.removeAll { !it.isAlive(now) }

        echoes.addFirst(people)
        while (echoes.size > ECHO_FRAMES) echoes.removeLast()

        val bodies = people.map(::Body)
        for (body in bodies) {
            val tracker = trackers.getOrPut(body.person.id) { MoveTracker() }
            tracker.lastSeen = now
            if (!games.isRunning) reactToMoves(body, tracker, now)
            if (settings.look == Look.SPARKLE) leaveSparkles(body, tracker, now) else tracker.trailPoints.clear()
        }
        trackers.values.removeAll { now - it.lastSeen > 2000 }

        val game = games.update(Tick(bodies, now, elapsed, this, imageWidth, imageHeight, visible ?: android.graphics.RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())))

        if (particles.size > MAX_PARTICLES) particles.subList(0, particles.size - MAX_PARTICLES).clear()
        if (settings.look == Look.BALLERINA) ribbons.update(bodies) else ribbons.clear()
        return FunSnapshot(particles.toList(), pops.toList(), echoes.toList(), game, ribbons.snapshot())
    }

    private fun reactToMoves(body: Body, tracker: MoveTracker, now: Long) {
        val u = body.unit
        val headY = body.headY

        if (body.ok(Body.LEFT_WRIST, Body.RIGHT_WRIST) && headY != null) {
            val handsUp = body.y(Body.LEFT_WRIST) < headY - 0.1f * u && body.y(Body.RIGHT_WRIST) < headY - 0.1f * u
            if (handsUp && !tracker.handsWereUp && now - tracker.lastHandsUp > 1500) {
                tracker.lastHandsUp = now
                val x = (body.x(Body.LEFT_WRIST) + body.x(Body.RIGHT_WRIST)) / 2
                confettiBurst(x, headY - 0.6f * u, u, now)
                pop("YAY!", x, headY - 0.9f * u, 0.55f * u, now)
                sounds.play(Sound.YAY)
            }
            tracker.handsWereUp = handsUp
        }

        if (body.ok(Body.LEFT_WRIST, Body.RIGHT_WRIST)) {
            val apart = body.dist(Body.LEFT_WRIST, Body.RIGHT_WRIST)
            if (apart > 1.0f * u) {
                tracker.lastWristsApart = now
            } else if (apart < 0.35f * u && now - tracker.lastWristsApart < 700 && now - tracker.lastClap > 800) {
                tracker.lastClap = now
                val x = (body.x(Body.LEFT_WRIST) + body.x(Body.RIGHT_WRIST)) / 2
                val y = (body.y(Body.LEFT_WRIST) + body.y(Body.RIGHT_WRIST)) / 2
                fireworks(x, y, u, now)
                pop("CLAP!", x, y - 0.5f * u, 0.55f * u, now)
                sounds.play(Sound.POP)
            }
        }

        if (body.ok(11, 12, Body.LEFT_ANKLE, Body.RIGHT_ANKLE)) {
            val hipY = (body.y(11) + body.y(12)) / 2
            if (tracker.hipRestingY.isNaN()) tracker.hipRestingY = hipY
            val rise = tracker.hipRestingY - hipY
            if (rise > 0.4f * u && now - tracker.lastJump > 1200) {
                tracker.lastJump = now
                val feetX = (body.x(Body.LEFT_ANKLE) + body.x(Body.RIGHT_ANKLE)) / 2
                val feetY = max(body.y(Body.LEFT_ANKLE), body.y(Body.RIGHT_ANKLE))
                starBurst(feetX, feetY, u, now)
                pop("BOING!", body.x(0).takeIf { body.ok(0) } ?: feetX, (headY ?: hipY) - 0.7f * u, 0.55f * u, now)
                sounds.play(Sound.BOING)
            }
            // Follow slow changes (walking closer, squatting) but not the jump itself.
            val follow = if (rise < 0.15f * u) 0.2f else 0.03f
            tracker.hipRestingY += (hipY - tracker.hipRestingY) * follow
        }
    }

    private fun leaveSparkles(body: Body, tracker: MoveTracker, now: Long) {
        val u = body.unit
        val hueBase = (now / 8 % 360).toFloat()
        val sources = listOf(
            Body.LEFT_WRIST, Body.RIGHT_WRIST, Body.LEFT_INDEX_TIP, Body.RIGHT_INDEX_TIP,
            Body.LEFT_ANKLE, Body.RIGHT_ANKLE,
        )
        for ((n, k) in sources.withIndex()) {
            if (!body.ok(k)) {
                tracker.trailPoints.remove(k)
                continue
            }
            val x = body.x(k)
            val y = body.y(k)
            val (px, py) = tracker.trailPoints[k] ?: (x to y)
            tracker.trailPoints[k] = x to y
            // More sparkles the faster the hand moves, spread along the path it took.
            val count = (1 + (hypot(x - px, y - py) / (0.12f * u)).toInt()).coerceAtMost(7)
            repeat(count) { i ->
                val along = (i + random.nextFloat()) / count
                particles += Particle(
                    x = px + (x - px) * along + random.nextFloat(-0.05f, 0.05f) * u,
                    y = py + (y - py) * along + random.nextFloat(-0.05f, 0.05f) * u,
                    vx = random.nextFloat(-0.3f, 0.3f) * u,
                    vy = random.nextFloat(-0.4f, 0.1f) * u,
                    gravity = 0.6f * u,
                    born = now,
                    lifeMs = random.nextLong(500, 900),
                    size = random.nextFloat(0.05f, 0.09f) * u,
                    color = Color.HSVToColor(floatArrayOf((hueBase + n * 50 + i * 12) % 360, 0.7f, 1f)),
                    shape = if (k == Body.LEFT_WRIST || k == Body.RIGHT_WRIST) ParticleShape.STAR else ParticleShape.SPARK,
                    spin = random.nextFloat(-360f, 360f),
                )
            }
        }
    }

    private fun confettiBurst(x: Float, y: Float, u: Float, now: Long) = repeat(60) {
        particles += Particle(
            x, y,
            vx = random.nextFloat(-2.2f, 2.2f) * u, vy = random.nextFloat(-3.2f, -0.8f) * u, gravity = 4f * u,
            born = now, lifeMs = random.nextLong(1200, 1800), size = random.nextFloat(0.08f, 0.14f) * u,
            color = brightColor(), shape = ParticleShape.CONFETTI, spin = random.nextFloat(-720f, 720f),
        )
    }

    private fun fireworks(x: Float, y: Float, u: Float, now: Long) = repeat(40) { i ->
        val angle = 2 * PI.toFloat() * i / 40 + random.nextFloat(-0.08f, 0.08f)
        val speed = random.nextFloat(1.8f, 3f) * u
        particles += Particle(
            x, y, vx = cos(angle) * speed, vy = sin(angle) * speed, gravity = 1f * u,
            born = now, lifeMs = random.nextLong(700, 1000), size = random.nextFloat(0.06f, 0.1f) * u,
            color = brightColor(), shape = if (i % 3 == 0) ParticleShape.STAR else ParticleShape.SPARK, spin = 0f,
        )
    }

    private fun starBurst(x: Float, y: Float, u: Float, now: Long) = repeat(22) {
        particles += Particle(
            x, y, vx = random.nextFloat(-2.4f, 2.4f) * u, vy = random.nextFloat(-1.8f, -0.4f) * u, gravity = 3f * u,
            born = now, lifeMs = random.nextLong(800, 1200), size = random.nextFloat(0.12f, 0.2f) * u,
            color = Color.rgb(255, 214 + random.nextInt(40), random.nextInt(80)), shape = ParticleShape.STAR,
            spin = random.nextFloat(-360f, 360f),
        )
    }

    /** Confetti falling over the whole picture, for winning a round of the game. */
    fun confettiRain(now: Long, imageWidth: Int, imageHeight: Int) = repeat(110) {
        val w = imageWidth.toFloat()
        val h = imageHeight.toFloat()
        particles += Particle(
            x = random.nextFloat() * w, y = -random.nextFloat() * 0.2f * h,
            vx = random.nextFloat(-0.1f, 0.1f) * w, vy = random.nextFloat(0.1f, 0.4f) * h, gravity = 0.35f * h,
            born = now, lifeMs = random.nextLong(2000, 2800), size = random.nextFloat(0.015f, 0.03f) * w,
            color = brightColor(), shape = if (random.nextInt(4) == 0) ParticleShape.STAR else ParticleShape.CONFETTI,
            spin = random.nextFloat(-720f, 720f),
        )
    }

    /** A comic word bouncing in at (x, y), [size] tall, all in image pixels. */
    fun pop(text: String, x: Float, y: Float, size: Float, now: Long) {
        pops += TextPop(text, x, y, size, brightColor(), now)
    }

    /** A small splash of sparks, for popping a bubble or a meteor hit. [size] is in image pixels. */
    fun burst(x: Float, y: Float, size: Float, now: Long, color: Int) = repeat(24) { i ->
        val angle = 2 * PI.toFloat() * i / 24 + random.nextFloat(-0.1f, 0.1f)
        val speed = random.nextFloat(2.5f, 4.5f) * size
        particles += Particle(
            x, y, vx = cos(angle) * speed, vy = sin(angle) * speed, gravity = 2f * size,
            born = now, lifeMs = random.nextLong(400, 700), size = random.nextFloat(0.12f, 0.22f) * size,
            color = if (i % 4 == 0) Color.WHITE else color, shape = ParticleShape.SPARK, spin = 0f,
        )
    }

    private fun brightColor() = Color.HSVToColor(floatArrayOf(random.nextFloat() * 360, 0.75f, 1f))

    private companion object {
        const val ECHO_FRAMES = 10
        const val MAX_PARTICLES = 900
    }
}

/** Draws particles and comic words. One per drawing thread (its paints are not shared). */
class EffectsPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun drawParticles(canvas: Canvas, fit: ImageFit, particles: List<Particle>, now: Long) {
        for (p in particles) {
            if (!p.isAlive(now)) continue
            val t = (now - p.born) / 1000f
            val life = (now - p.born).toFloat() / p.lifeMs
            val x = fit.x(p.x + p.vx * t)
            val y = fit.y(p.y + p.vy * t + 0.5f * p.gravity * t * t)
            val size = p.size * fit.scale
            fill.color = p.color
            fill.alpha = ((1 - life * life) * 255).toInt()
            when (p.shape) {
                ParticleShape.CONFETTI -> {
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate(p.spin * t)
                    canvas.drawRect(-size / 2, -size / 4, size / 2, size / 4, fill)
                    canvas.restore()
                }
                ParticleShape.STAR -> {
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate(p.spin * t)
                    canvas.scale(size, size)
                    canvas.drawPath(STAR, fill)
                    canvas.restore()
                }
                ParticleShape.SPARK -> {
                    val alpha = fill.alpha
                    fill.alpha = alpha / 4
                    canvas.drawCircle(x, y, size, fill)
                    fill.alpha = alpha
                    canvas.drawCircle(x, y, size * 0.4f, fill)
                }
            }
        }
    }

    fun drawPops(canvas: Canvas, fit: ImageFit, pops: List<TextPop>, now: Long) {
        for (pop in pops) {
            if (!pop.isAlive(now)) continue
            val life = (now - pop.born).toFloat() / TextPop.LIFE_MS
            val size = pop.size * fit.scale * bounce(life)
            // Keep the word on screen even when the person's hands reach the top edge.
            val y = maxOf(fit.y(pop.y - pop.size * 0.8f * life), size * 1.1f)
            drawComicText(canvas, pop.text, fit.x(pop.x), y, size, pop.color, alpha = fadeOut(life))
        }
    }

    /** Big bold word with a black outline, like in a comic. */
    fun drawComicText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, alpha: Float = 1f) {
        words.textSize = size
        words.color = color
        words.alpha = (alpha * 255).toInt()
        val plainLetters = text.all { it.code < 128 } // outlines look odd around emoji
        if (plainLetters) {
            outline.textSize = size
            outline.strokeWidth = size * 0.14f
            outline.alpha = words.alpha
            canvas.drawText(text, x, y, outline)
        }
        canvas.drawText(text, x, y, words)
    }

    companion object {
        /** Grows past full size, then settles: a cartoon "boing". */
        fun bounce(life: Float): Float = when {
            life < 0.15f -> 0.3f + life / 0.15f * 0.95f
            life < 0.3f -> 1.25f - (life - 0.15f) / 0.15f * 0.25f
            else -> 1f
        }

        fun fadeOut(life: Float): Float = if (life < 0.7f) 1f else (1 - (life - 0.7f) / 0.3f).coerceIn(0f, 1f)

        /** Five-pointed star, 1 unit across. */
        val STAR = Path().apply {
            for (i in 0 until 10) {
                val radius = if (i % 2 == 0) 0.5f else 0.22f
                val angle = PI.toFloat() * i / 5 - PI.toFloat() / 2
                val px = cos(angle) * radius
                val py = sin(angle) * radius
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }
}
