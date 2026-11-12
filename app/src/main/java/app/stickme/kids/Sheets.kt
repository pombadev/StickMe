package app.stickme.kids

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

private fun View.dpf(value: Float) = value * resources.displayMetrics.density
private fun View.dpi(value: Float) = (value * resources.displayMetrics.density).toInt()

private fun View.fade(show: Boolean) {
    if (show) {
        alpha = 0f
        visibility = View.VISIBLE
        animate().alpha(1f).setDuration(180).start()
    } else {
        animate().alpha(0f).setDuration(140).withEndAction { visibility = View.GONE; alpha = 1f }.start()
    }
}

/** The sticker sheet: every game as a sticker, tilted a little. Tap one to play it. */
class GamesSheet(private val sheet: View, private val onPick: (GameKind) -> Unit) {

    private val grid: GridLayout = sheet.findViewById(R.id.gamesGrid)

    private fun dpi(v: Float) = sheet.dpi(v)

    val isShown get() = sheet.visibility == View.VISIBLE

    init {
        sheet.findViewById<View>(R.id.gamesClose).setOnClickListener { hide() }
        val landscape = sheet.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        grid.columnCount = if (landscape) 6 else 3
        val tilt = floatArrayOf(-4f, 3f, -2f, 5f, -3f, 2f, -5f, 4f, -1f, 3f, -4f)
        GameKind.entries.forEachIndexed { i, kind ->
            val sticker = StickerButton(sheet.context).apply {
                stickerShape = StickerButton.Shape.ROUNDED
                cornerRadius = dpf(20f)
                stickerColor = Paper.STICKERS[i % Paper.STICKERS.size]
                borderColor = Color.WHITE
                rotation = tilt[i % tilt.size]
                contentDescription = kind.label
                setOnClickListener { hide(); onPick(kind) }
            }
            val column = LinearLayout(sheet.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dpi(4f), dpi(6f), dpi(4f), dpi(6f))
            }
            column.addView(ImageView(sheet.context).apply {
                setImageResource(iconFor(kind))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dpi(56f), dpi(56f)))
            column.addView(TextView(sheet.context).apply {
                text = kind.label
                typeface = Fonts.hand
                setTextColor(Paper.INK)
                textSize = 19f
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2f) })
            sticker.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (kind == GameKind.MIRROR) {
                sticker.addView(StickerButton(sheet.context).apply {
                    stickerShape = StickerButton.Shape.CIRCLE
                    stickerColor = Paper.RED
                    borderColor = Color.WHITE
                    rotation = 12f
                    addView(TextView(context).apply {
                        text = "2 players"
                        typeface = Fonts.hand
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setLineSpacing(0f, 0.9f)
                        includeFontPadding = false
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }, FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.TOP or Gravity.END).apply {
                    topMargin = -dpi(12f)
                    marginEnd = -dpi(10f)
                })
            }
            grid.addView(sticker, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = dpi(118f)
                setMargins(dpi(5f), dpi(6f), dpi(5f), dpi(6f))
            })
        }
    }

    fun show() = sheet.fade(true)
    fun hide() { if (isShown) sheet.fade(false) }

    private fun iconFor(kind: GameKind) = when (kind) {
        GameKind.COPY_ME -> R.drawable.ic_game_copy_me
        GameKind.BUBBLES -> R.drawable.ic_game_bubbles
        GameKind.FREEZE -> R.drawable.ic_game_freeze_dance
        GameKind.JUMPS -> R.drawable.ic_game_jumps
        GameKind.METEORS -> R.drawable.ic_game_meteors
        GameKind.LIMBO -> R.drawable.ic_game_limbo
        GameKind.MIRROR -> R.drawable.ic_game_mirror_me
        GameKind.SIMON -> R.drawable.ic_game_simon_says
        GameKind.PAINT -> R.drawable.ic_game_air_paint
        GameKind.WORKOUT -> R.drawable.ic_game_workout
        GameKind.BALLET -> R.drawable.ic_game_ballet_class
    }
}

/** The crayon box (looks) and the card of faces. */
class LooksSheet(
    private val sheet: View,
    private val settings: SceneSettings,
    private val onLookChanged: () -> Unit,
    private val onFaceChanged: () -> Unit,
) {
    private class Entry(val look: Look, val crayon: CrayonBoxView.Crayon, val tip: String)

    private val looks = listOf(
        Entry(Look.NEON, CrayonBoxView.Crayon("Neon", Paper.MINT, Paper.MINT), "glow in the dark!"),
        Entry(
            Look.RAINBOW,
            CrayonBoxView.Crayon("Rainbow", Paper.PINK, Paper.YELLOW, stripes = intArrayOf(Paper.PINK, Paper.PEACH, Paper.YELLOW, Paper.MINT, Paper.BLUE)),
            "every colour at once",
        ),
        Entry(Look.SPARKLE, CrayonBoxView.Crayon("Sparkle", Paper.PAPER, Paper.PAPER, dots = true), "wave your hands!"),
        Entry(Look.ECHO, CrayonBoxView.Crayon("Echo", Paper.BLUE, Paper.BLUE), "dance, then look back!"),
        Entry(Look.BALLERINA, CrayonBoxView.Crayon("Ballerina", Paper.PINK, Paper.PINK), "twirl and wave!"),
    )

    private val crayons: CrayonBoxView = sheet.findViewById(R.id.crayonBox)
    private val faceRow: LinearLayout = sheet.findViewById(R.id.faceOptions)
    private val lookName: TextView = sheet.findViewById(R.id.lookName)
    private val lookTip: TextView = sheet.findViewById(R.id.lookTip)
    private val faceButtons = ArrayList<FrameLayout>()

    val isShown get() = sheet.visibility == View.VISIBLE

    init {
        crayons.crayons = looks.map { it.crayon }
        crayons.onPick = { i ->
            settings.look = looks[i].look
            showLook()
            onLookChanged()
        }
        // Give the crayons room, but never more than the screen can spare for them.
        val screenHeight = sheet.resources.displayMetrics.heightPixels
        val wanted = sheet.dpi(400f)
        sheet.findViewById<View>(R.id.crayonArea).layoutParams.height = minOf(wanted, (screenHeight * 0.45f).toInt())
        sheet.findViewById<View>(R.id.looksDone).setOnClickListener { hide() }
        buildFaces()
        showLook()
    }

    private fun showLook() {
        val i = looks.indexOfFirst { it.look == settings.look }.coerceAtLeast(0)
        crayons.selected = i
        lookName.text = looks[i].crayon.name
        lookTip.text = looks[i].tip
    }

    private fun buildFaces() {
        FaceCatalog.all.forEachIndexed { i, style ->
            val holder = FrameLayout(sheet.context)
            val sticker = StickerButton(sheet.context).apply {
                stickerShape = StickerButton.Shape.CIRCLE
                stickerColor = Paper.STICKERS[i % Paper.STICKERS.size]
                borderColor = Color.WHITE
                rotation = (if (i % 2 == 1) 1 else -1) * (3f + i % 6)
                contentDescription = style.button
                elevation = dpf(3f)
                addView(TextView(context).apply {
                    text = style.button
                    textSize = 26f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                setOnClickListener { button ->
                    settings.face = style
                    if (style == FaceStyle.Mix) {
                        FaceCatalog.rollNewMix() // tapping the dice again rolls new faces
                        button.animate().rotationBy(360f).setDuration(350).start()
                    }
                    highlightFace()
                    onFaceChanged()
                }
            }
            holder.addView(sticker, FrameLayout.LayoutParams(dpi(50f), dpi(50f), Gravity.CENTER))
            faceRow.addView(holder, LinearLayout.LayoutParams(dpi(58f), dpi(58f)).apply { marginEnd = dpi(2f) })
            faceButtons += holder
        }
        highlightFace()
    }

    private fun highlightFace() {
        FaceCatalog.all.forEachIndexed { i, style ->
            faceButtons[i].background = if (style == settings.face) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dpi(3f), Paper.RED, dpf(5f), dpf(4f))
                }
            } else null
        }
    }

    private fun dpi(v: Float) = sheet.dpi(v)
    private fun dpf(v: Float) = sheet.dpf(v)

    fun show() {
        showLook()
        highlightFace()
        sheet.fade(true)
    }

    fun hide() { if (isShown) sheet.fade(false) }
}

/** The grown-ups folder: tracking quality, sound, and the promise that nothing leaves the phone. */
class SettingsFolder(
    private val folder: View,
    private val onModel: (PoseModel) -> Unit,
    private val onSound: (Boolean) -> Unit,
) {
    private val fast: CheckSquareView = folder.findViewById(R.id.checkFast)
    private val detailed: CheckSquareView = folder.findViewById(R.id.checkDetailed)
    private val sound: CheckSquareView = folder.findViewById(R.id.checkSound)
    private val note: TextView = folder.findViewById(R.id.trackingNote)
    private var speed = 0
    private var soundOn = true

    val isShown get() = folder.visibility == View.VISIBLE

    init {
        folder.findViewById<View>(R.id.settingsClose).setOnClickListener { hide() }
        folder.findViewById<View>(R.id.optFast).setOnClickListener { pick(PoseModel.TINY) }
        folder.findViewById<View>(R.id.optDetailed).setOnClickListener { pick(PoseModel.MEDIUM) }
        folder.findViewById<View>(R.id.optSound).setOnClickListener {
            soundOn = !soundOn
            sound.checked = soundOn
            onSound(soundOn)
        }
    }

    private fun pick(model: PoseModel) {
        fast.checked = model == PoseModel.TINY
        detailed.checked = model == PoseModel.MEDIUM
        onModel(model)
    }

    fun set(model: PoseModel, soundOn: Boolean) {
        fast.checked = model == PoseModel.TINY
        detailed.checked = model == PoseModel.MEDIUM
        this.soundOn = soundOn
        sound.checked = soundOn
        updateNote()
    }

    fun setSpeed(picturesPerSecond: Int) {
        speed = picturesPerSecond
        updateNote()
    }

    private fun updateNote() {
        val now = if (speed > 0) " Right now: $speed pictures a second." else ""
        note.text = "Fast suits most phones. Detailed follows fingers and faces better but can feel slower.$now"
    }

    fun show() = folder.fade(true)
    fun hide() { if (isShown) folder.fade(false) }
}

/** The results screen: a prize ribbon with the score, and what to do next. */
class ResultsScreen(
    private val screen: View,
    private val onPlayAgain: (GameKind) -> Unit,
    private val onPickAnother: () -> Unit,
) {
    private var kind: GameKind? = null

    val isShown get() = screen.visibility == View.VISIBLE

    init {
        screen.background = ChalkboardDrawable()
        screen.findViewById<View>(R.id.playAgain).setOnClickListener { kind?.let(onPlayAgain) }
        screen.findViewById<View>(R.id.pickAnother).setOnClickListener { onPickAnother() }
    }

    fun show(info: ResultInfo) {
        kind = info.kind
        screen.findViewById<TextView>(R.id.resultGame).text = info.kind.label.lowercase()
        screen.findViewById<RibbonView>(R.id.resultRibbon).apply {
            scoreText = info.score.toString()
            unit = if (info.score == 1 && info.unit.endsWith("s")) info.unit.dropLast(1) else info.unit
        }
        screen.findViewById<View>(R.id.newBestBadge).visibility = if (info.newBest && info.score > 0) View.VISIBLE else View.GONE
        screen.findViewById<TextView>(R.id.beatYour).apply {
            text = when {
                info.newBest && info.previousBest > 0 -> "old best was ${info.previousBest}"
                !info.newBest && info.previousBest > 0 -> "beat your ${info.previousBest}!"
                else -> ""
            }
        }
        if (!isShown) screen.fade(true)
    }

    fun hide() { if (isShown) screen.fade(false) }
}

/**
 * Opens something only after the sticker is held down for [holdMs]. The sticker swells while it is held,
 * so a kid can see something is about to happen, and a quick tap does nothing but show a hint.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.onLongHold(holdMs: Long, onTooShort: () -> Unit, onHeld: () -> Unit) {
    var opened = false
    val opener = Runnable {
        opened = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        onHeld()
    }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                opened = false
                view.postDelayed(opener, holdMs)
                view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(holdMs).setInterpolator(LinearInterpolator()).start()
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val held = opened
                view.removeCallbacks(opener)
                view.animate().cancel()
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                if (!held && event.actionMasked == MotionEvent.ACTION_UP) onTooShort()
                true
            }
            else -> true
        }
    }
}
