package app.stickme.kids

import android.graphics.Color
import android.graphics.RectF
import kotlin.random.Random

/** Every game in the 🎯 menu. */
enum class GameKind(val emoji: String, val label: String) {
    COPY_ME("🎯", "Copy me"),
    BUBBLES("🫧", "Bubbles"),
    FREEZE("🧊", "Freeze dance"),
    JUMPS("🦘", "Jumps"),
    METEORS("☄️", "Meteors"),
    LIMBO("🎋", "Limbo"),
    MIRROR("🪞", "Mirror me"),
    SIMON("🗣️", "Simon says"),
    PAINT("🖌️", "Air paint"),
    WORKOUT("🏋️", "Workout"),
    BALLET("🩰", "Ballet class"),
}

/** Remembers each game's best score. */
interface ScoreBook {
    fun best(game: GameKind): Int

    /** Saves [score] if it beats the best so far; true when it did. */
    fun offer(game: GameKind, score: Int): Boolean
}

class MemoryScoreBook : ScoreBook {
    private val best = HashMap<GameKind, Int>()
    override fun best(game: GameKind) = best[game] ?: 0
    override fun offer(game: GameKind, score: Int): Boolean {
        if (score <= best(game)) return false
        best[game] = score
        return true
    }
}

/** What every game may use: sounds, the phone's voice, best scores and dice. */
class GameKit(
    val sounds: SoundBoard,
    val scores: ScoreBook = MemoryScoreBook(),
    val speak: (String) -> Unit = {},
    val random: Random = Random.Default,
)

/**
 * One camera frame as a game sees it. [elapsed] is the time since the previous frame.
 * [visible] is the part of the picture that shows on screen (the camera picture is cropped to fill it).
 */
class Tick(
    val bodies: List<Body>,
    val now: Long,
    val elapsed: Long,
    val fx: FunEngine,
    val imageWidth: Int,
    val imageHeight: Int,
    val visible: RectF = RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat()),
) {
    fun withBodies(newBodies: List<Body>) = Tick(newBodies, now, elapsed, fx, imageWidth, imageHeight, visible)
}

/** What the screen is showing, so the buttons around it can change to match. */
enum class SceneMode { FIT, PICK, PLAY, RESULT }

/** How a finished game went, for the results screen. */
class ResultInfo(val kind: GameKind, val score: Int, val unit: String, val newBest: Boolean, val previousBest: Int)

/**
 * A game frozen at one moment, safe to draw from both the screen and the video recorder.
 * [behind] draws under the stick figures, [front] over them.
 */
class GameScene(
    val card: Card?,
    val big: BigText?,
    private val behind: ((GameCanvas) -> Unit)? = null,
    private val front: ((GameCanvas) -> Unit)? = null,
    val mode: SceneMode = SceneMode.PLAY,
    /** A short chalk hint for the bottom of the screen. */
    val hint: String = "",
    val canSkip: Boolean = false,
    val result: ResultInfo? = null,
) {
    fun drawBehind(c: GameCanvas) {
        card?.let { c.tools.drawCard(c, it) }
        behind?.invoke(c)
    }

    fun drawFront(c: GameCanvas) {
        front?.invoke(c)
        big?.let { c.tools.drawBigText(c, it) }
    }
}

/**
 * Shared flow for every game: get ready (stand in view, and for solo games raise your hands to claim it),
 * a 3-2-1 countdown, play, then a results screen that stays until the kid chooses what next.
 * Games fill in [play] and [playScene]. Camera thread only.
 */
abstract class MiniGame(protected val kit: GameKit, val kind: GameKind) {

    private enum class Stage { GATE, COUNTDOWN, PLAY, RESULT, DONE }

    private var stage = Stage.GATE
    private var stageStart = -1L
    private var countdownShown = 0
    private var result: ResultInfo? = null
    private var lastPlayerAt: Pair<Float, Float>? = null

    protected var big: BigText? = null
    var score = 0
        protected set

    /** One short sentence shown during the countdown. */
    protected abstract val howToPlay: String

    /** Solo games are played by one person, picked by raising hands when several people are in view. */
    open val solo = false

    /** Which parts of the body must be in view before the game starts. */
    open val needs: Set<Part> = Part.ALL

    /** How many people must be fully in view; the game waits until they are. */
    open val needPeople = 1

    /** What the score counts, for the results screen. */
    open val scoreUnit = "points"

    /** The person playing a solo game, once picked. */
    var playerId: Int? = null
        private set

    private val gate by lazy { GameGate(kit, kind.label, solo, needs, needPeople) }

    internal val isPlaying get() = stage == Stage.PLAY

    /** Plays one frame. Returns false when the game is over. */
    protected abstract fun play(t: Tick): Boolean

    protected abstract fun playScene(now: Long): GameScene

    protected open fun onStart(t: Tick) {}

    /** Called once when the game ends or is stopped early, for example to stop music. */
    protected open fun onStop() {}

    /** Returns false once the game has been stopped. The results screen stays up until then. */
    fun update(t: Tick): Boolean {
        if (stageStart < 0) stageStart = t.now
        when (stage) {
            Stage.GATE -> if (gate.update(t)) {
                playerId = gate.player
                stage = Stage.COUNTDOWN
                stageStart = t.now
            }
            Stage.COUNTDOWN -> {
                val left = 3 - ((t.now - stageStart) / COUNT_MS).toInt()
                if (left <= 0) {
                    stage = Stage.PLAY
                    stageStart = t.now
                    show("GO!", t.now, Color.WHITE)
                    kit.sounds.play(Sound.GO)
                    onStart(playersOnly(t))
                } else if (left != countdownShown) {
                    countdownShown = left
                    show(left.toString(), t.now, YELLOW)
                    kit.sounds.play(Sound.TICK)
                }
            }
            Stage.PLAY -> if (!play(playersOnly(t))) finish(t)
            Stage.RESULT, Stage.DONE -> Unit
        }
        return stage != Stage.DONE
    }

    /** In a solo game only the person who claimed it counts; everyone else is watching. */
    private fun playersOnly(t: Tick): Tick {
        val id = playerId
        if (!solo || id == null) return t
        var mine = t.bodies.filter { it.person.id == id }
        if (mine.isEmpty() && t.bodies.isNotEmpty()) {
            // The tracker may have given the same person a new number: pick up whoever is where they were.
            val last = lastPlayerAt
            val near = last?.let { (x, y) ->
                t.bodies.minByOrNull { Math.hypot((it.person.box.centerX() - x).toDouble(), (it.person.box.centerY() - y).toDouble()) }
                    ?.takeIf { Math.hypot((it.person.box.centerX() - x).toDouble(), (it.person.box.centerY() - y).toDouble()) < t.imageWidth * 0.35 }
            }
            if (near != null) {
                playerId = near.person.id
                mine = listOf(near)
            }
        }
        mine.firstOrNull()?.let { lastPlayerAt = it.person.box.centerX() to it.person.box.centerY() }
        return t.withBodies(mine)
    }

    fun scene(now: Long): GameScene = when (stage) {
        Stage.GATE -> gate.scene(now)
        Stage.COUNTDOWN -> GameScene(Card(kind.label, subtitle = howToPlay), big)
        Stage.PLAY -> playScene(now)
        Stage.RESULT, Stage.DONE -> GameScene(null, null, mode = SceneMode.RESULT, result = result)
    }

    /** Lets the kid start anyway when the body check can't be met, for example while sitting down. */
    fun skipGate() = gate.skip()

    fun stop() {
        if (stage == Stage.PLAY) onStop()
        stage = Stage.DONE
    }

    private fun finish(t: Tick) {
        onStop()
        stage = Stage.RESULT
        stageStart = t.now
        val previous = kit.scores.best(kind)
        val newBest = kit.scores.offer(kind, score)
        result = ResultInfo(kind, score, scoreUnit, newBest, previous)
        big = null
        t.fx.confettiRain(t.now, t.imageWidth, t.imageHeight)
        t.fx.confettiRain(t.now + 600, t.imageWidth, t.imageHeight)
        kit.sounds.play(Sound.WIN)
    }

    protected fun show(text: String, now: Long, color: Int, stays: Boolean = false) {
        big = BigText(text, now, color, stays)
    }

    companion object {
        const val COUNT_MS = 900L
        val YELLOW = Color.rgb(255, 235, 59)
        val GREEN = Color.rgb(118, 255, 3)
        val ORANGE = Color.rgb(255, 152, 0)
        val CHEERS = listOf("GREAT!", "AWESOME!", "WOW!", "SUPER!", "YES!", "AMAZING!")
    }
}

/**
 * Starts, runs and stops the chosen game. Camera thread, except the `request...` functions,
 * which any thread may call.
 */
class GameHost(private val kit: GameKit) {

    @Volatile private var requested: GameKind? = null
    @Volatile private var stopRequested = false
    @Volatile private var skipRequested = false
    @Volatile var current: GameKind? = null
        private set

    val isRunning get() = current != null

    /** Called on the camera thread with the game that started, or null when it ended. */
    var onChanged: ((GameKind?) -> Unit)? = null

    private var game: MiniGame? = null

    internal val activeGame: MiniGame? get() = game

    fun requestStart(kind: GameKind) { requested = kind }

    fun requestStop() { stopRequested = true }

    fun requestSkipGate() { skipRequested = true }

    fun update(t: Tick): GameScene? {
        if (stopRequested) {
            stopRequested = false
            requested = null
            end()
        }
        requested?.let { kind ->
            requested = null
            end()
            game = create(kind)
            current = kind
            onChanged?.invoke(kind)
        }
        val running = game ?: return null
        if (skipRequested) {
            skipRequested = false
            running.skipGate()
        }
        if (!running.update(t)) {
            end()
            return null
        }
        return running.scene(t.now)
    }

    private fun end() {
        game?.stop()
        game = null
        if (current != null) {
            current = null
            onChanged?.invoke(null)
        }
    }

    private fun create(kind: GameKind): MiniGame = when (kind) {
        GameKind.COPY_ME -> CopyMeGame(kit)
        GameKind.BUBBLES -> BubblePop(kit)
        GameKind.FREEZE -> FreezeDance(kit)
        GameKind.JUMPS -> JumpChallenge(kit)
        GameKind.METEORS -> Meteors(kit)
        GameKind.LIMBO -> Limbo(kit)
        GameKind.MIRROR -> MirrorMe(kit)
        GameKind.SIMON -> SimonSays(kit)
        GameKind.PAINT -> AirPainting(kit)
        GameKind.WORKOUT -> Workout(kit)
        GameKind.BALLET -> BalletClass(kit)
    }
}
