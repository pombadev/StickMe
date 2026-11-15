package app.stickme.kids

import android.graphics.Color
import kotlin.math.abs

/** Counts one exercise move: the body goes into one shape, then back into another. */
private class TwoStepCounter(private val first: (Body) -> Boolean, private val second: (Body) -> Boolean) {
    private var halfway = false

    /** True when a whole move (first shape, then second shape) has just finished. */
    fun update(body: Body): Boolean {
        if (!halfway && first(body)) {
            halfway = true
        } else if (halfway && second(body)) {
            halfway = false
            return true
        }
        return false
    }
}

private fun Body.hipY() = (y(11) + y(12)) / 2
private fun Body.feetApart() = abs(x(15) - x(16))

/** The five exercises, in order, each with a picture and how to count one move. */
enum class Exercise(val title: String, val emoji: String, val picture: Pictogram, private val counter: () -> (Body) -> Boolean) {
    JUMPING_JACKS(
        "Jumping jacks", "🙆",
        Pictogram.standing(floatArrayOf(24f, 4f, 50f, 30f, 76f, 4f), floatArrayOf(26f, 94f, 50f, 58f, 74f, 94f)),
        {
            val count = TwoStepCounter(
                first = { b -> b.ok(5, 6, 9, 10, 15, 16) && b.y(9) < b.y(5) && b.y(10) < b.y(6) && b.feetApart() > 0.7f * b.unit },
                second = { b -> b.ok(5, 6, 9, 10, 15, 16) && b.y(9) > b.y(5) && b.y(10) > b.y(6) && b.feetApart() < 0.5f * b.unit },
            )
            count::update
        },
    ),
    SQUATS(
        "Squats", "🏋️",
        Pictogram(
            50f, 30f,
            listOf(
                floatArrayOf(50f, 40f, 50f, 66f),
                floatArrayOf(50f, 44f, 82f, 46f),
                floatArrayOf(36f, 94f, 30f, 74f, 50f, 66f, 70f, 74f, 64f, 94f),
            ),
        ),
        {
            val count = TwoStepCounter(
                first = { b -> b.ok(11, 12, 13, 14) && (b.y(13) + b.y(14)) / 2 - b.hipY() < 0.45f * b.unit },
                second = { b -> b.ok(11, 12, 13, 14) && (b.y(13) + b.y(14)) / 2 - b.hipY() > 0.65f * b.unit },
            )
            count::update
        },
    ),
    HIGH_KNEES(
        "High knees", "🏃",
        Pictogram.standing(floatArrayOf(36f, 46f, 50f, 30f, 66f, 22f), floatArrayOf(46f, 94f, 50f, 58f, 66f, 58f, 64f, 80f)),
        {
            // Every knee lift counts, left or right, as long as that knee came back down first.
            val left = TwoStepCounter(
                first = { b -> b.ok(11, 12, 13) && b.y(13) < b.hipY() + 0.3f * b.unit },
                second = { b -> b.ok(11, 12, 13) && b.y(13) > b.hipY() + 0.55f * b.unit },
            )
            val right = TwoStepCounter(
                first = { b -> b.ok(11, 12, 14) && b.y(14) < b.hipY() + 0.3f * b.unit },
                second = { b -> b.ok(11, 12, 14) && b.y(14) > b.hipY() + 0.55f * b.unit },
            )
            val eitherKnee: (Body) -> Boolean = { b -> left.update(b) or right.update(b) }
            eitherKnee
        },
    ),
    TOE_TOUCHES(
        "Toe touches", "🙇",
        PoseTarget.TOUCH_TOES.picture,
        {
            val count = TwoStepCounter(
                first = { b -> b.ok(9, 10, 13, 14) && minOf(b.y(9), b.y(10)) > (b.y(13) + b.y(14)) / 2 },
                second = { b -> b.ok(9, 10, 11, 12) && maxOf(b.y(9), b.y(10)) < b.hipY() },
            )
            count::update
        },
    ),
    STAR_JUMPS(
        "Star jumps", "⭐",
        PoseTarget.STAR.picture,
        {
            val count = TwoStepCounter(
                first = { b ->
                    val head = b.headY
                    head != null && b.ok(9, 10, 15, 16) && b.y(9) < head && b.y(10) < head && b.feetApart() > 0.8f * b.unit
                },
                second = { b -> b.ok(5, 6, 9, 10, 15, 16) && b.y(9) > b.y(5) && b.y(10) > b.y(6) && b.feetApart() < 0.5f * b.unit },
            )
            count::update
        },
    ),
    ;

    /** A fresh move counter for one person. */
    fun newCounter(): (Body) -> Boolean = counter()
}

/**
 * A 3-minute kids' workout: five exercises of 30 seconds with short rests between.
 * Every counted move dings; each kid's count shows on their tummy. The score is all moves together.
 */
class Workout(kit: GameKit) : MiniGame(kit, GameKind.WORKOUT) {

    override val howToPlay = "5 exercises, 30 seconds each. Let's move!"
    override val scoreUnit = "moves"

    private class Label(val x: Float, val y: Float, val count: Int, val size: Float)

    private var exercise = 0
    private var resting = false
    private var stageStart = 0L
    private var counters = PerPerson { Exercise.JUMPING_JACKS.newCounter() }
    private val moves = HashMap<Int, Int>() // this exercise, per person
    private var labels: List<Label> = emptyList()

    internal val currentExercise get() = Exercise.entries[exercise]
    internal val isResting get() = resting

    override fun onStart(t: Tick) = startExercise(0, t.now)

    override fun play(t: Tick): Boolean {
        if (t.bodies.isEmpty()) stageStart += t.elapsed
        if (resting) {
            labels = emptyList()
            if (t.now - stageStart >= REST_MS) startExercise(exercise + 1, t.now)
            return true
        }
        counters.forgetAllBut(t.bodies)
        for (body in t.bodies) {
            if (counters.of(body)(body)) {
                val id = body.person.id
                moves[id] = (moves[id] ?: 0) + 1
                score++
                kit.sounds.play(Sound.DING)
            }
        }
        labels = t.bodies.mapNotNull { body ->
            val (x, y) = body.onTummy() ?: return@mapNotNull null
            Label(x, y, moves[body.person.id] ?: 0, 0.55f * body.unit)
        }
        if (t.now - stageStart < EXERCISE_MS) return true
        if (exercise + 1 >= Exercise.entries.size) return false
        resting = true
        stageStart = t.now
        show("Great job! 💪", t.now, GREEN)
        kit.sounds.play(Sound.YAY)
        return true
    }

    private fun startExercise(index: Int, now: Long) {
        exercise = index
        resting = false
        stageStart = now
        val next = Exercise.entries[index]
        counters = PerPerson { next.newCounter() }
        moves.clear()
        if (index > 0) {
            show("GO!", now, Color.WHITE)
            kit.sounds.play(Sound.GO)
        }
    }

    override fun playScene(now: Long): GameScene {
        val current = Exercise.entries[exercise]
        val card = if (resting) {
            val next = Exercise.entries[exercise + 1]
            Card(
                "Rest", next.picture,
                tally = score, unit = scoreUnit,
                subtitle = "Next: ${next.title}",
                timeLeft = (1 - (now - stageStart).toFloat() / REST_MS).coerceIn(0f, 1f),
            )
        } else {
            Card(
                current.title, current.picture,
                tally = score, unit = scoreUnit,
                subtitle = "${exercise + 1} of ${Exercise.entries.size}",
                timeLeft = (1 - (now - stageStart).toFloat() / EXERCISE_MS).coerceIn(0f, 1f),
            )
        }
        val shown = labels
        return GameScene(card, big, front = { c ->
            shown.forEach { c.tools.drawLabel(c, it.count.toString(), it.x, it.y, it.size, Color.rgb(255, 235, 59)) }
        })
    }

    private companion object {
        const val EXERCISE_MS = 30_000L
        const val REST_MS = 8_000L
    }
}
