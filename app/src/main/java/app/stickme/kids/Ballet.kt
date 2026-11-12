package app.stickme.kids

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Ballet positions for Ballet Class, each with a picture and a check on the real person. */
enum class BalletPose(val title: String, val emoji: String, val picture: Pictogram, val matches: (Body) -> Boolean) {
    CROWN(
        "Ballerina crown", "👑",
        Pictogram.standing(floatArrayOf(50f, 30f, 32f, 18f, 40f, 3f, 60f, 3f, 68f, 18f, 50f, 30f)),
        { b ->
            val head = b.headY
            head != null && b.ok(9, 10) && b.y(9) < head && b.y(10) < head && b.dist(9, 10) < 0.6f * b.unit
        },
    ),
    WINGS(
        "Arms like wings", "🕊️",
        Pictogram.standing(floatArrayOf(8f, 40f, 26f, 33f, 50f, 30f, 74f, 33f, 92f, 40f)),
        { b ->
            b.ok(5, 6, 9, 10) && abs(b.y(9) - b.y(5)) < 0.5f * b.unit && abs(b.y(10) - b.y(6)) < 0.5f * b.unit &&
                abs(b.x(9) - b.x(10)) > 1.8f * b.unit
        },
    ),
    PLIE(
        "Plié", "🦵",
        Pictogram(
            50f, 22f,
            listOf(
                floatArrayOf(50f, 31f, 50f, 62f),
                floatArrayOf(40f, 58f, 34f, 46f, 50f, 34f, 66f, 46f, 60f, 58f),
                floatArrayOf(32f, 94f, 28f, 76f, 50f, 62f, 72f, 76f, 68f, 94f),
            ),
        ),
        { b ->
            b.ok(11, 12, 13, 14) && (b.y(13) + b.y(14)) / 2 - (b.y(11) + b.y(12)) / 2 < 0.6f * b.unit &&
                abs(b.x(13) - b.x(14)) > max(1.6f * abs(b.x(11) - b.x(12)), 0.5f * b.unit)
        },
    ),
    ARABESQUE(
        "Arabesque", "🩰",
        Pictogram(
            42f, 18f,
            listOf(
                floatArrayOf(45f, 27f, 50f, 58f),
                floatArrayOf(10f, 22f, 46f, 31f, 80f, 42f),
                floatArrayOf(50f, 94f, 50f, 58f, 92f, 50f),
            ),
        ),
        { b ->
            b.ok(11, 12, 15, 16) && run {
                val (lifted, hip) = if (b.y(15) < b.y(16)) 15 to 11 else 16 to 12
                abs(b.y(15) - b.y(16)) > 0.5f * b.unit && abs(b.x(lifted) - b.x(hip)) > 0.4f * b.unit
            }
        },
    ),
}

/** Notices a twirl: the shoulders swap sides (back to the camera) and swap back soon after. */
class SpinDetector {
    private var turnedAwayAt = 0L
    private var lastSpin = 0L

    fun update(body: Body, now: Long): Boolean {
        if (!body.ok(5, 6)) return false
        // Facing the camera, the left shoulder (5) is on the right of the picture.
        val facingAway = body.x(5) < body.x(6) && abs(body.x(5) - body.x(6)) > 0.15f * body.unit
        if (facingAway) {
            if (turnedAwayAt == 0L) turnedAwayAt = now
            return false
        }
        val spun = turnedAwayAt != 0L && now - turnedAwayAt < 2500 && now - lastSpin > 2000
        turnedAwayAt = 0L
        if (spun) lastSpin = now
        return spun
    }
}

/**
 * Ballet Class: copy a ballet position and hold it still for two seconds. The stiller the hold,
 * the more stars (up to 3). A twirl anytime earns a bonus star. Music-box music plays. 6 rounds.
 */
class BalletClass(kit: GameKit) : MiniGame(kit, GameKind.BALLET) {

    override val howToPlay = "Copy the ballet pose and hold it gracefully!"
    override val solo = true
    override val scoreUnit = "stars"

    private val poses = BalletPose.entries.shuffled(kit.random) + BalletPose.entries.shuffled(kit.random).take(2)
    private val meters = PerPerson { MovementMeter() }
    private val spins = PerPerson { SpinDetector() }
    private var round = -1
    private var roundStart = 0L
    private var holdStart: Long? = null
    private var wobble = 0f
    private var wobbleSamples = 0
    private var cheeringUntil: Long? = null

    override fun onStart(t: Tick) {
        kit.sounds.startLoop(Sound.MUSIC_BOX_LOOP)
        nextRound(t.now)
    }

    override fun onStop() = kit.sounds.stopLoop()

    override fun play(t: Tick): Boolean {
        meters.forgetAllBut(t.bodies)
        spins.forgetAllBut(t.bodies)
        val speeds = t.bodies.associate { it.person.id to meters.of(it).update(it, t.now) }
        for (body in t.bodies) {
            if (spins.of(body).update(body, t.now)) {
                score++
                body.aboveHead()?.let { (x, y) -> t.fx.pop("Twirl! 🌟", x, y, 0.45f * body.unit, t.now) }
                kit.sounds.play(Sound.YAY)
            }
        }

        cheeringUntil?.let { until ->
            if (t.now < until) return true
            cheeringUntil = null
            if (round + 1 >= poses.size) return false
            nextRound(t.now)
        }
        if (t.bodies.isEmpty()) roundStart += t.elapsed

        val dancer = t.bodies.firstOrNull(poses[round].matches)
        if (dancer == null) {
            holdStart = null
        } else {
            val since = holdStart ?: t.now.also {
                holdStart = it
                wobble = 0f
                wobbleSamples = 0
            }
            wobble += speeds[dancer.person.id] ?: 0f
            wobbleSamples++
            if (t.now - since >= HOLD_MS) {
                val stars = starsFor(wobble / wobbleSamples)
                score += stars
                show("⭐".repeat(stars) + " " + PRAISE[stars - 1], t.now, Color.rgb(255, 128, 171))
                t.fx.confettiRain(t.now, t.imageWidth, t.imageHeight)
                kit.sounds.play(Sound.TADA)
                cheeringUntil = t.now + CHEER_MS
                return true
            }
        }
        if (t.now - roundStart > ROUND_MS) {
            show("Next one!", t.now, ORANGE)
            kit.sounds.play(Sound.MISS)
            cheeringUntil = t.now + CHEER_MS
        }
        return true
    }

    /** Average body speed during the hold (torso-lengths a second) → 1 to 3 stars. Thresholds are guesses. */
    private fun starsFor(averageSpeed: Float) = when {
        averageSpeed < 0.15f -> 3
        averageSpeed < 0.3f -> 2
        else -> 1
    }

    private fun nextRound(now: Long) {
        round++
        roundStart = now
        holdStart = null
    }

    override fun playScene(now: Long): GameScene {
        val pose = poses[round]
        val cheering = cheeringUntil != null
        val holding = holdStart != null && !cheering
        val card = Card(
            pose.title, pose.picture,
            tally = score, unit = scoreUnit,
            subtitle = if (holding) "Hold it gracefully… 🩰" else "${round + 1} of ${poses.size}",
            timeLeft = if (cheering) null else (1 - (now - roundStart).toFloat() / ROUND_MS).coerceIn(0f, 1f),
            highlight = holding,
        )
        return GameScene(card, big)
    }

    private companion object {
        const val ROUND_MS = 10_000L
        const val HOLD_MS = 2_000L
        const val CHEER_MS = 1_800L
        val PRAISE = listOf("Nice!", "Lovely!", "Graceful!")
    }
}

/** A ribbon following one hand: recent positions, oldest first, in image pixels. */
class Ribbon(val points: FloatArray, val width: Float, val color: Int)

/** Remembers where each hand has just been, for the ballerina look's flowing ribbons. Camera thread only. */
class RibbonTrails {
    private val trails = HashMap<Int, ArrayDeque<Pair<Float, Float>>>()
    private val widths = HashMap<Int, Float>()

    fun update(bodies: List<Body>) {
        val seen = HashSet<Int>()
        for (body in bodies) {
            for ((side, wrist) in listOf(0 to Body.LEFT_WRIST, 1 to Body.RIGHT_WRIST)) {
                val key = body.person.id * 2 + side
                if (!body.ok(wrist)) continue
                seen += key
                val trail = trails.getOrPut(key) { ArrayDeque() }
                trail.addLast(body.x(wrist) to body.y(wrist))
                while (trail.size > LENGTH) trail.removeFirst()
                widths[key] = 0.09f * body.unit
            }
        }
        trails.keys.retainAll(seen)
    }

    fun clear() = trails.clear()

    fun snapshot(): List<Ribbon> = trails.mapNotNull { (key, trail) ->
        if (trail.size < 2) return@mapNotNull null
        val points = FloatArray(trail.size * 2)
        trail.forEachIndexed { i, (x, y) ->
            points[i * 2] = x
            points[i * 2 + 1] = y
        }
        Ribbon(points, widths[key] ?: 4f, if (key % 2 == 0) Color.rgb(255, 128, 171) else Color.rgb(206, 147, 216))
    }

    private companion object {
        const val LENGTH = 14
    }
}

/** Draws the ballerina look: a tutu at the hips, a tiara on the head and ribbons from the hands. One per drawing thread. */
class BallerinaPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun drawTutu(canvas: Canvas, person: Person, fit: ImageFit) {
        val body = Body(person)
        if (!body.ok(11, 12)) return
        var lx = fit.x(body.x(11))
        var ly = fit.y(body.y(11))
        var rx = fit.x(body.x(12))
        var ry = fit.y(body.y(12))
        if (lx > rx) { // order by screen side so the tutu never turns upside down
            lx = rx.also { rx = lx }
            ly = ry.also { ry = ly }
        }
        val u = body.unit * fit.scale
        val hipWidth = hypot(rx - lx, ry - ly)
        val width = max(hipWidth * 2.6f, 0.95f * u)
        val waist = max(hipWidth * 0.65f, 0.2f * u)
        canvas.save()
        canvas.translate((lx + rx) / 2, (ly + ry) / 2)
        canvas.rotate(Math.toDegrees(atan2(ry - ly, rx - lx).toDouble()).toFloat())
        skirt(canvas, waist, width * 1.12f, 0.42f * u, Color.argb(150, 248, 187, 208))
        skirt(canvas, waist, width, 0.36f * u, Color.argb(230, 255, 128, 171))
        fill.color = Color.WHITE
        for (i in 0 until 6) {
            val along = (i + 0.5f) / 6 - 0.5f
            canvas.drawCircle(along * width * 0.8f, 0.12f * u + (i % 2) * 0.1f * u, 0.025f * u, fill)
        }
        line.color = Color.rgb(236, 64, 122)
        line.strokeWidth = 0.05f * u
        canvas.drawLine(-waist, -0.08f * u, waist, -0.08f * u, line)
        canvas.restore()
    }

    /** A flared skirt with a scalloped hem, hanging down from y = 0 in the current canvas space. */
    private fun skirt(canvas: Canvas, waist: Float, hem: Float, length: Float, color: Int) {
        val top = -0.08f * length / 0.36f
        val scallops = 7
        path.reset()
        path.moveTo(-waist, top)
        path.lineTo(waist, top)
        path.lineTo(hem / 2, length)
        val step = hem / scallops
        for (i in 0 until scallops) {
            val x = hem / 2 - (i + 1) * step
            path.quadTo(x + step / 2, length + step * 0.6f, x, length)
        }
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    fun drawTiara(canvas: Canvas, person: Person, fit: ImageFit) {
        val face = FacePose.of(person, fit::x, fit::y) ?: return
        val w = face.size * 0.8f
        val h = face.size * 0.3f
        canvas.save()
        canvas.translate(face.centerX, face.centerY)
        canvas.rotate(face.angleDegrees)
        canvas.translate(0f, -face.size * 0.5f)
        path.reset()
        path.moveTo(-w / 2, 0f)
        path.lineTo(-w / 2, -h * 0.45f)
        path.lineTo(-w / 4, -h * 0.15f)
        path.lineTo(0f, -h)
        path.lineTo(w / 4, -h * 0.15f)
        path.lineTo(w / 2, -h * 0.45f)
        path.lineTo(w / 2, 0f)
        path.close()
        fill.color = Color.rgb(255, 213, 79)
        canvas.drawPath(path, fill)
        line.color = Color.rgb(255, 160, 0)
        line.strokeWidth = h * 0.08f
        canvas.drawPath(path, line)
        fill.color = Color.rgb(236, 64, 122)
        canvas.drawCircle(0f, -h * 0.35f, h * 0.16f, fill)
        fill.color = Color.rgb(100, 181, 246)
        canvas.drawCircle(-w * 0.3f, -h * 0.12f, h * 0.1f, fill)
        canvas.drawCircle(w * 0.3f, -h * 0.12f, h * 0.1f, fill)
        canvas.restore()
    }

    /** Ribbons get thinner towards their tail. Solid colour: see-through pieces would look striped where they overlap. */
    fun drawRibbons(canvas: Canvas, ribbons: List<Ribbon>, fit: ImageFit) {
        for (ribbon in ribbons) {
            val count = ribbon.points.size / 2
            for (i in 1 until count) {
                val fresh = i.toFloat() / (count - 1)
                line.color = ribbon.color
                line.strokeWidth = ribbon.width * fit.scale * (0.25f + 0.75f * fresh)
                canvas.drawLine(
                    fit.x(ribbon.points[(i - 1) * 2]), fit.y(ribbon.points[(i - 1) * 2 + 1]),
                    fit.x(ribbon.points[i * 2]), fit.y(ribbon.points[i * 2 + 1]), line,
                )
            }
        }
    }
}
