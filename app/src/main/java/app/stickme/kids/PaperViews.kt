package app.stickme.kids

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DiscretePathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

internal fun View.dp(value: Float) = value * resources.displayMetrics.density

/**
 * A sticker: a coloured shape with a white border and a soft shadow, holding whatever you put inside it
 * (an icon, some text). Use [stickerShape] for circles, rounded squares, octagons, torn paper or a star.
 */
class StickerButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    enum class Shape { CIRCLE, ROUNDED, OCTAGON, TORN, STAR }

    var stickerColor = Paper.CHALK
        set(value) { field = value; invalidate() }
    var borderColor = Paper.PAPER_LIGHT
        set(value) { field = value; invalidate() }
    var stickerShape = Shape.ROUNDED
        set(value) { field = value; rebuild(); invalidate() }

    /** How round the corners of a [Shape.ROUNDED] sticker are. */
    var cornerRadius = dp(16f)
        set(value) { field = value; rebuild(); invalidate() }
    private var radius
        get() = cornerRadius
        set(value) { cornerRadius = value }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val inner = Path()
    private val box = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        if (elevation == 0f) elevation = dp(6f) // unless the layout chose one
        context.obtainStyledAttributes(attrs, R.styleable.StickerButton).use { a ->
            stickerColor = a.getColor(R.styleable.StickerButton_stickerColor, stickerColor)
            borderColor = a.getColor(R.styleable.StickerButton_stickerBorder, borderColor)
            radius = a.getDimension(R.styleable.StickerButton_stickerRadius, radius)
            stickerShape = Shape.entries[a.getInt(R.styleable.StickerButton_stickerShape, Shape.ROUNDED.ordinal)]
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                when (stickerShape) {
                    Shape.CIRCLE -> outline.setOval(0, 0, view.width, view.height)
                    Shape.ROUNDED -> outline.setRoundRect(0, 0, view.width, view.height, radius)
                    Shape.OCTAGON -> if (!path.isEmpty) outline.setConvexPath(path) else outline.setEmpty()
                    Shape.TORN -> outline.setRoundRect(0, 0, view.width, view.height, dp(4f))
                    Shape.STAR -> outline.setEmpty()
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        inner.reset()
        if (w == 0f || h == 0f) return
        when (stickerShape) {
            Shape.CIRCLE -> path.addOval(0f, 0f, w, h, Path.Direction.CW)
            Shape.ROUNDED -> path.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
            Shape.OCTAGON -> {
                polygon(path, w, h, 0f, 30f, 0f, 70f, 30f, 100f, 70f, 100f, 100f, 70f, 100f, 30f, 70f, 0f, 30f, 0f)
                val inset = dp(10f)
                box.set(inset, inset, w - inset, h - inset)
                polygon(inner, box.width(), box.height(), 0f, 30f, 0f, 70f, 30f, 100f, 70f, 100f, 100f, 70f, 100f, 30f, 70f, 0f, 30f, 0f)
                inner.offset(inset, inset)
            }
            Shape.TORN -> tornRectangle(path, w, h)
            Shape.STAR -> polygon(
                path, w, h, 50f, 0f, 61f, 32f, 95f, 35f, 68f, 56f, 79f, 91f,
                50f, 70f, 21f, 91f, 32f, 56f, 5f, 35f, 39f, 32f,
            )
        }
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = stickerColor
        canvas.drawPath(path, fill)
        val borderWidth = dp(4f)
        when (stickerShape) {
            Shape.CIRCLE, Shape.ROUNDED -> {
                border.color = borderColor
                border.strokeWidth = borderWidth * 2
                canvas.save()
                canvas.clipPath(path)
                canvas.drawPath(path, border)
                canvas.restore()
            }
            Shape.OCTAGON -> {
                border.color = borderColor
                border.strokeWidth = borderWidth
                canvas.drawPath(inner, border)
            }
            else -> Unit
        }
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        animate().scaleX(if (pressed) 0.93f else 1f).scaleY(if (pressed) 0.93f else 1f).setDuration(90).start()
    }

    companion object {
        /** Points are percentages of the width and height. */
        fun polygon(path: Path, w: Float, h: Float, vararg percent: Float) {
            path.moveTo(percent[0] / 100f * w, percent[1] / 100f * h)
            for (i in 2 until percent.size step 2) path.lineTo(percent[i] / 100f * w, percent[i + 1] / 100f * h)
            path.close()
        }

        /** A rectangle with ragged top and bottom edges, like paper torn by hand. */
        fun tornRectangle(path: Path, w: Float, h: Float, seed: Int = 3) {
            val random = Random(seed)
            val jag = h * 0.07f
            path.moveTo(0f, random.nextFloat() * jag)
            var x = 0f
            while (x < w) {
                x = min(w, x + w * 0.04f)
                path.lineTo(x, random.nextFloat() * jag)
            }
            path.lineTo(w - random.nextFloat() * w * 0.02f, h * 0.5f)
            x = w
            while (x > 0f) {
                x = max(0f, x - w * 0.04f)
                path.lineTo(x, h - random.nextFloat() * jag)
            }
            path.lineTo(random.nextFloat() * w * 0.02f, h * 0.5f)
            path.close()
        }
    }
}

/** A strip of washi tape with torn ends, holding a paper note to the board. */
class TapeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var stripe = Paper.YELLOW
    private var light = Paper.PAPER

    init {
        context.obtainStyledAttributes(attrs, R.styleable.TapeView).use { a ->
            stripe = a.getColor(R.styleable.TapeView_tapeColor, stripe)
            light = a.getColor(R.styleable.TapeView_tapeLight, light)
        }
    }

    override fun onDraw(canvas: Canvas) = Tape.draw(canvas, width.toFloat(), height.toFloat(), stripe, light)
}

/** Draws a strip of diagonally striped tape with torn ends. Shared by [TapeView] and the game cards. */
object Tape {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    fun draw(canvas: Canvas, w: Float, h: Float, stripe: Int, light: Int) {
        clip.reset()
        StickerButton.polygon(clip, w, h, 3f, 0f, 97f, 0f, 100f, 25f, 96f, 50f, 100f, 75f, 97f, 100f, 3f, 100f, 0f, 75f, 4f, 50f, 0f, 25f)
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(225, Color.red(light), Color.green(light), Color.blue(light))
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.style = Paint.Style.STROKE
        val unit = h / 22f
        paint.strokeWidth = 6f * unit
        paint.color = Color.argb(225, Color.red(stripe), Color.green(stripe), Color.blue(stripe))
        val spacing = 12f * unit * 1.4142f
        var x = -h
        while (x < w + h) {
            canvas.drawLine(x, h, x + h, 0f, paint)
            x += spacing
        }
        canvas.restore()
    }
}

/** A strip of cardboard with a torn edge along the bottom of the screen, or down its right side in landscape. */
class TornStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var onRight = false
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Paper.CARDBOARD }
    private val speckle = Paint().apply {
        shader = BitmapShader(Chalkboard.noise(white = false, strength = 0.9f, seed = 9), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.TornStrip).use { a ->
            onRight = a.getInt(R.styleable.TornStrip_tornSide, 0) == 1
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val random = Random(if (onRight) 11 else 7)
        path.reset()
        val w1 = w.toFloat()
        val h1 = h.toFloat()
        if (onRight) {
            path.moveTo(w1, 0f)
            path.lineTo(dp(14f) + random.nextFloat() * dp(10f), 0f)
            var y = dp(9f)
            while (y < h1) {
                path.lineTo(dp(10f) + random.nextFloat() * dp(14f), y)
                y += dp(9f)
            }
            path.lineTo(w1, h1)
        } else {
            path.moveTo(0f, dp(22f))
            var x = 0f
            while (x <= w1) {
                path.lineTo(x, dp(14f) + random.nextFloat() * dp(14f))
                x += dp(9f)
            }
            path.lineTo(w1, h1)
            path.lineTo(0f, h1)
        }
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, fill)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), speckle)
        canvas.restore()
    }
}

/** Cut-out letters, like a ransom note: every letter on its own tilted tile. */
object RansomText {
    private val TILT = floatArrayOf(-7f, 5f, -3f, 6f, -5f, 4f, -6f, 10f)
    private val DROP = floatArrayOf(0f, 5f, 0f, -4f, 3f, 0f, 4f, 0f)
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 0, 0, 0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private class Letter(val char: String, val index: Int, val width: Float, val height: Float, val handFont: Boolean)

    /** Draws [word] centred on ([cx], [cy]); [size] is the letter height. Shrinks to fit [maxWidth] when given. */
    fun draw(canvas: Canvas, word: String, cx: Float, cy: Float, size: Float, maxWidth: Float = Float.MAX_VALUE, lastIsRed: Boolean = true) {
        val letters = ArrayList<Letter>()
        val gap = size * 0.07f
        var index = 0
        for (c in word.codePoints().toArray()) {
            val ch = String(Character.toChars(c))
            if (ch == " ") {
                letters += Letter(" ", -1, size * 0.28f, size, false)
                continue
            }
            val hand = index % 3 == 1 || index % 4 == 0 && index % 3 == 0
            applyFont(hand, size)
            val w = text.measureText(ch) + size * 0.3f
            letters += Letter(ch, index, max(w, size * 0.55f), size, hand)
            index++
        }
        var total = letters.sumOf { (it.width + gap).toDouble() }.toFloat() - gap
        val fit = if (total > maxWidth) maxWidth / total else 1f
        total *= fit
        val lastLetter = letters.lastOrNull { it.index >= 0 }
        var x = cx - total / 2
        for (letter in letters) {
            val w = letter.width * fit
            if (letter.index < 0) {
                x += w + gap * fit
                continue
            }
            val i = letter.index
            val round = i % 4 == 3
            val red = lastIsRed && letter === lastLetter && letter.char in setOf("!", "?")
            canvas.save()
            canvas.translate(x + w / 2, cy + DROP[i % DROP.size] * size / 50f)
            canvas.rotate(TILT[i % TILT.size])
            canvas.scale(fit, fit)
            val half = letter.width / 2
            val tall = size * 0.62f
            box.set(-half, -tall, half, tall)
            val pad = if (round) size * 0.12f else 0f
            box.inset(-pad / 2, -pad / 2)
            if (round) {
                canvas.drawOval(box.left, box.top + size * 0.08f, box.right, box.bottom + size * 0.08f, shadow)
                tile.color = Paper.tileColor(i)
                canvas.drawOval(box, tile)
            } else {
                canvas.drawRect(box.left, box.top + size * 0.08f, box.right, box.bottom + size * 0.08f, shadow)
                tile.color = if (red) Paper.RED else Paper.tileColor(i)
                canvas.drawRect(box, tile)
            }
            applyFont(letter.handFont, size)
            text.color = if (red) Color.WHITE else Paper.INK
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(letter.char, 0f, size * 0.3f, text)
            canvas.restore()
            x += w + gap * fit
        }
    }

    private fun applyFont(hand: Boolean, size: Float) {
        text.typeface = if (hand) Fonts.hand else Fonts.heading
        text.textSize = if (hand) size * 1.05f else size * 0.92f
    }
}

/**
 * Chalk drawings for the startup screens: waking up, asking for the camera, nobody in view and the
 * results screen. All drawn in the design's 390 x 844 space, scaled and centred to fit.
 */
class ChalkArtView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Scene { WAKE, CAMERA, NOBODY, RESULTS }

    var scene = Scene.WAKE
        set(value) { field = value; invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val box = RectF()

    init {
        scene = Scene.entries[
            context.obtainStyledAttributes(attrs, R.styleable.ChalkArt).use { it.getInt(R.styleable.ChalkArt_chalkScene, 0) },
        ]
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width / 390f, height / 844f)
        canvas.save()
        canvas.translate((width - 390f * s) / 2, (height - 844f * s) / 2)
        canvas.scale(s, s)
        when (scene) {
            Scene.WAKE -> wake(canvas)
            Scene.CAMERA -> camera(canvas)
            Scene.NOBODY -> nobody(canvas)
            Scene.RESULTS -> results(canvas)
        }
        canvas.restore()
    }

    private fun stroke(color: Int, width: Float, alpha: Int = 255, rough: Boolean = true) = line.apply {
        this.color = color
        strokeWidth = width
        this.alpha = alpha
        pathEffect = if (rough) DiscretePathEffect(14f, 1.5f) else null
    }

    private fun chalk(canvas: Canvas, d: String, color: Int, width: Float, alpha: Int = 255) {
        path.reset()
        path.set(androidx.core.graphics.PathParser.createPathFromPathData(d) ?: return)
        // A soft glow under the line, like chalk dust, then the line itself.
        canvas.drawPath(path, stroke(color, width * 2.6f, (alpha * 0.22f).toInt(), rough = false))
        canvas.drawPath(path, stroke(color, width, alpha))
    }

    /** The design's stick figure, with its top-left at ([x], [y]). */
    private fun figure(canvas: Canvas, x: Float, y: Float, scale: Float = 1f, smile: Boolean = true) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        chalk(canvas, "M60 72v76", Paper.PEACH, 7f)
        chalk(canvas, "M60 86L36 62L24 26M60 148L44 200L38 256", Paper.MINT, 7f)
        chalk(canvas, "M60 86L84 62L96 26M60 148L76 200L84 256", Paper.BLUE, 7f)
        chalk(canvas, "M38 44a22 22 0 1 0 44 0a22 22 0 1 0-44 0", Paper.CHALK, 6f)
        if (smile) chalk(canvas, "M52 40v2M68 40v2M50 52q10 9 20 0", Paper.CHALK, 4f)
        canvas.restore()
    }

    private fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, rotate: Float = 0f, alpha: Int = 255) {
        words.typeface = Fonts.hand
        words.textSize = size
        words.color = color
        words.alpha = alpha
        canvas.save()
        canvas.rotate(rotate, x, y)
        canvas.drawText(s, x, y, words)
        canvas.restore()
    }

    private fun wake(canvas: Canvas) {
        fill.color = Color.argb(9, 255, 255, 255)
        canvas.drawOval(25f, 350f, 365f, 590f, fill)
        canvas.save()
        canvas.translate(135f, 330f)
        // The friend is only half drawn: a head, a body, one arm, a hand, and the rest as faint dots.
        chalk(canvas, "M38 44a22 22 0 1 0 44 0a22 22 0 1 0-44 0", Paper.CHALK, 6f)
        chalk(canvas, "M60 72v76", Paper.PEACH, 7f)
        chalk(canvas, "M60 86L36 62L24 26", Paper.MINT, 7f)
        chalk(canvas, "M60 86L74 72", Paper.BLUE, 7f)
        val dots = Paint(line).apply {
            color = Paper.CHALK; strokeWidth = 4f; alpha = 90; pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 12f), 0f)
        }
        path.reset()
        path.moveTo(74f, 72f); path.lineTo(84f, 62f); path.lineTo(96f, 26f)
        path.moveTo(60f, 148f); path.lineTo(44f, 200f); path.lineTo(38f, 256f)
        path.moveTo(60f, 148f); path.lineTo(76f, 200f); path.lineTo(84f, 256f)
        canvas.drawPath(path, dots)
        // The chalk it is being drawn with.
        canvas.save()
        canvas.translate(74f, 72f)
        canvas.rotate(-35f)
        fill.color = Paper.PAPER
        canvas.drawRoundRect(-4f, -34f, 8f, 2f, 3f, 3f, fill)
        fill.color = Color.parseColor("#D9D3C2")
        canvas.drawRoundRect(-4f, -34f, 8f, -24f, 3f, 3f, fill)
        canvas.restore()
        canvas.restore()
        chalk(canvas, "M40 752c90-6 220-8 310 0", Paper.CHALK, 3f, 75)
        RansomText.draw(canvas, "Stick Me!", 195f, 176f, 50f)
        text(canvas, "drawing you a friend...", 195f, 660f, 34f, Paper.CHALK, -2f)
        text(canvas, "getting the camera ready", 195f, 700f, 19f, Paper.CHALK_DIM)
    }

    private fun camera(canvas: Canvas) {
        fill.color = Color.argb(9, 255, 255, 255)
        canvas.drawOval(25f, 350f, 365f, 590f, fill)
        // A small friend waving hello from the top corner.
        chalk(canvas, "M280 96a20 20 0 1 0 40 0a20 20 0 1 0-40 0", Paper.CHALK, 5f)
        chalk(canvas, "M292 92v2M308 92v2M294 104q6-4 12 0", Paper.CHALK, 3.5f)
        chalk(canvas, "M300 116v50", Paper.PEACH, 5f)
        chalk(canvas, "M300 128l-20 20M300 166l-12 40", Paper.MINT, 5f)
        chalk(canvas, "M300 128l16-24M300 166l12 40", Paper.BLUE, 5f)
        chalk(canvas, "M332 56q8-14 20-8q10 8-2 18q-6 4-6 12M344 92v2", Paper.YELLOW, 4f)
    }

    private fun nobody(canvas: Canvas) {
        fill.color = Color.argb(8, 255, 255, 255)
        canvas.drawOval(25f, 280f, 365f, 520f, fill)
        // A dotted outline of where to stand.
        val dashed = Paint(line).apply {
            color = Paper.CHALK; strokeWidth = 5f; alpha = 140; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 13f), 0f)
        }
        canvas.save()
        canvas.translate(135f, 250f)
        path.reset()
        path.addCircle(60f, 44f, 24f, Path.Direction.CW)
        path.moveTo(60f, 72f); path.lineTo(60f, 156f)
        path.moveTo(60f, 88f); path.lineTo(30f, 116f); path.lineTo(22f, 150f)
        path.moveTo(60f, 88f); path.lineTo(90f, 116f); path.lineTo(98f, 150f)
        path.moveTo(60f, 156f); path.lineTo(42f, 214f); path.lineTo(36f, 280f)
        path.moveTo(60f, 156f); path.lineTo(78f, 214f); path.lineTo(84f, 280f)
        canvas.drawPath(path, dashed)
        canvas.restore()
        // Two footprints to stand on.
        chalk(canvas, "M148 560c-12-2-16 14-8 24c6 8 18 6 18-6c0-8-4-16-10-18zM146 548h.01M152 545h.01M158 547h.01", Paper.YELLOW, 3.5f)
        chalk(canvas, "M236 560c12-2 16 14 8 24c-6 8-18 6-18-6c0-8 4-16 10-18zM238 548h.01M232 545h.01M226 547h.01", Paper.YELLOW, 3.5f)
        chalk(canvas, "M150 606h86", Paper.CHALK, 3f, 150)
        text(canvas, "stand here", 195f, 636f, 22f, Paper.CHALK, alpha = 205)
    }

    private fun results(canvas: Canvas) {
        chalk(canvas, "M40 150l14 10M30 200h18M44 246l14-8", Paper.YELLOW, 3f)
        chalk(canvas, "M350 150l-14 10M360 200h-18M346 246l-14-8", Paper.YELLOW, 3f)
        chalk(canvas, "M70 60v14M63 67h14M320 80v12M314 86h12M340 330v14M333 337h14M46 350v10M41 355h10", Paper.CHALK, 3f)
        for ((x, y, color, turn) in listOf(
            Confetto(52f, 420f, Paper.PINK, 25f), Confetto(320f, 400f, Paper.MINT, -30f),
            Confetto(300f, 40f, Paper.BLUE, 40f), Confetto(96f, 110f, Paper.PEACH, -20f),
        )) {
            canvas.save()
            canvas.rotate(turn, x + 8f, y + 5f)
            fill.color = color
            canvas.drawRect(x, y, x + 15f, y + 9f, fill)
            canvas.restore()
        }
    }

    private data class Confetto(val x: Float, val y: Float, val color: Int, val turn: Float)
}

/** The prize ribbon on the results screen: a scalloped rosette with the score on it. */
class RibbonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var scoreText = "0"
        set(value) { field = value; invalidate() }
    var unit = "points"
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = min(width / 240f, height / 330f)
        canvas.save()
        canvas.translate((width - 240f * s) / 2, 0f)
        canvas.scale(s, s)
        // Two ribbon tails.
        for (tail in listOf("M86 190L52 318l30-14 18 26 22-130z", "M154 190l34 128-30-14-18 26-22-130z")) {
            path.set(androidx.core.graphics.PathParser.createPathFromPathData(tail) ?: return)
            fill.color = Paper.BLUE
            canvas.drawPath(path, fill)
            edge.color = Paper.PAPER_LIGHT
            edge.strokeWidth = 3f
            canvas.drawPath(path, edge)
        }
        // The scalloped rosette.
        path.reset()
        val cx = 120f
        val cy = 118f
        val n = 18
        for (i in 0 until n) {
            val a0 = i.toFloat() / n * 2 * PI.toFloat()
            val a1 = (i + 0.5f) / n * 2 * PI.toFloat()
            val a2 = (i + 1f) / n * 2 * PI.toFloat()
            if (i == 0) path.moveTo(cx + 86f * cos(a0), cy + 86f * sin(a0))
            path.quadTo(cx + 112f * cos(a1), cy + 112f * sin(a1), cx + 86f * cos(a2), cy + 86f * sin(a2))
        }
        path.close()
        fill.color = Paper.PINK
        canvas.drawPath(path, fill)
        edge.strokeWidth = 4f
        canvas.drawPath(path, edge)
        fill.color = Paper.PAPER
        canvas.drawCircle(cx, cy, 70f, fill)
        edge.color = Paper.INK
        edge.strokeWidth = 3f
        edge.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f)
        canvas.drawCircle(cx, cy, 70f, edge)
        edge.pathEffect = null

        words.typeface = Fonts.heading
        words.color = Paper.INK
        words.textSize = if (scoreText.length > 3) 46f else if (scoreText.length > 2) 56f else 68f
        canvas.drawText(scoreText, cx, 140f, words)
        words.typeface = Fonts.hand
        words.color = Paper.INK_BROWN
        words.textSize = 22f
        canvas.drawText(unit, cx, 168f, words)
        canvas.restore()
    }
}

/**
 * The box of crayons: one crayon for every look. The chosen crayon is pulled out of the box.
 * Tapping a crayon calls [onPick].
 */
class CrayonBoxView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    class Crayon(val name: String, val body: Int, val tip: Int, val stripes: IntArray? = null, val dots: Boolean = false)

    var crayons: List<Crayon> = emptyList()
        set(value) { field = value; invalidate() }
    var selected = 0
        set(value) { field = value; invalidate() }
    var onPick: ((Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Paper.RED
    }
    private val tip = Path()
    private val box = RectF()
    private val crayonBox = RectF()
    private val speckle = Paint().apply {
        shader = BitmapShader(Chalkboard.noise(white = false, strength = 0.9f, seed = 21), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val lift = 70f

    /** Everything is laid out for a 390-unit-wide, 400-unit-tall box and scaled to the view. */
    private fun unit() = min(width / 390f, height / 400f)

    private fun crayonLeft(i: Int): Float {
        val gap = (390f - 60f - 56f) / (crayons.size - 1).coerceAtLeast(1)
        return 30f + i * gap
    }

    override fun onDraw(canvas: Canvas) {
        if (crayons.isEmpty()) return
        val u = unit()
        canvas.save()
        canvas.translate((width - 390f * u) / 2, height - 400f * u)
        canvas.scale(u, u)
        for ((i, c) in crayons.withIndex()) drawCrayon(canvas, c, crayonLeft(i), if (i == selected) -lift else 0f)
        // The front of the box, over the crayons' lower halves.
        crayonBox.set(10f, 200f, 380f, 400f)
        fill.color = Paper.CARDBOARD
        canvas.drawRoundRect(crayonBox.left, crayonBox.top, crayonBox.right, crayonBox.bottom + 20f, 18f, 18f, fill)
        canvas.save()
        canvas.clipRect(crayonBox)
        canvas.drawRect(crayonBox, speckle)
        canvas.restore()
        for ((i, c) in crayons.withIndex()) {
            words.typeface = Fonts.hand
            words.textSize = 17f
            words.color = Color.parseColor("#2B2118")
            words.isFakeBoldText = i == selected
            val x = crayonLeft(i) + 28f
            canvas.drawText(c.name, x, 236f, words)
            if (i == selected) {
                line.strokeWidth = 3f
                path.reset()
                val half = words.measureText(c.name) / 2
                path.moveTo(x - half, 243f)
                var px = x - half
                var up = true
                while (px < x + half) {
                    px += 5f
                    path.lineTo(px, if (up) 240f else 244f)
                    up = !up
                }
                canvas.drawPath(path, line)
            }
        }
        words.isFakeBoldText = false
        words.textAlign = Paint.Align.LEFT
        words.typeface = Fonts.heading
        words.textSize = 42f
        words.color = Paper.RED
        canvas.drawText("LOOKS", 30f, 354f, words)
        words.typeface = Fonts.hand
        words.textSize = 19f
        words.color = Color.parseColor("#2B2118")
        canvas.drawText("pull out a crayon", 30f, 378f, words)
        words.textAlign = Paint.Align.CENTER
        canvas.restore()
    }

    private val path = Path()

    private fun drawCrayon(canvas: Canvas, c: Crayon, left: Float, rise: Float) {
        canvas.save()
        canvas.translate(left, 108f + rise)
        // The pointed tip.
        tip.reset()
        StickerButton.polygon(tip, 40f, 42f, 38f, 0f, 62f, 0f, 100f, 100f, 0f, 100f)
        canvas.save()
        canvas.translate(8f, 0f)
        fill.color = c.tip
        canvas.drawPath(tip, fill)
        canvas.restore()
        // The paper-wrapped body.
        box.set(0f, 42f, 56f, 332f)
        fill.color = c.body
        canvas.drawRoundRect(box, 3f, 3f, fill)
        canvas.save()
        canvas.clipRect(box)
        c.stripes?.let { colors ->
            val w = 56f / colors.size
            colors.forEachIndexed { k, color ->
                fill.color = color
                canvas.drawRect(k * w, 42f, (k + 1) * w, 332f, fill)
            }
        }
        if (c.dots) {
            fill.color = Paper.YELLOW
            var y = 52f
            while (y < 332f) {
                var x = 6f
                while (x < 56f) {
                    canvas.drawCircle(x, y, 2f, fill)
                    x += 12f
                }
                y += 12f
            }
        }
        fill.color = Color.argb(64, 0, 0, 0)
        canvas.drawRect(0f, 42f, 56f, 48f, fill)
        fill.color = Color.argb(31, 0, 0, 0)
        canvas.drawRect(48f, 42f, 56f, 332f, fill)
        canvas.restore()
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (crayons.isEmpty()) return false
        if (event.actionMasked != MotionEvent.ACTION_UP) return event.actionMasked == MotionEvent.ACTION_DOWN
        val u = unit()
        val x = (event.x - (width - 390f * u) / 2) / u
        val y = (event.y - (height - 400f * u)) / u
        // Taps in the crayon's column pick it, from its tip down to the label under the box front.
        for (i in crayons.indices) {
            val left = crayonLeft(i)
            if (x in (left - 6f)..(left + 62f) && y in 0f..260f) {
                onPick?.invoke(i)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** A sheet of paper that can have blue ruled lines, like a notebook page. */
class PaperCard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private var lineSpacing = 0f
    private var paperColor = Paper.PAPER
    private val line = Paint().apply { color = Paper.RULED_LINE }
    private val fill = Paint()

    init {
        setWillNotDraw(false)
        clipChildren = false
        if (elevation == 0f) elevation = dp(8f) // unless the layout chose one
        context.obtainStyledAttributes(attrs, R.styleable.PaperCard).use { a ->
            lineSpacing = a.getDimension(R.styleable.PaperCard_lineSpacing, 0f)
            paperColor = a.getColor(R.styleable.PaperCard_paperColor, paperColor)
        }
        outlineProvider = ViewOutlineProvider.BOUNDS
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = paperColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        if (lineSpacing > 0f) {
            line.strokeWidth = dp(1f)
            var y = lineSpacing
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, line)
                y += lineSpacing
            }
        }
    }
}

/** A hand-drawn tick box: a slightly wonky square, with a red tick inside when [checked]. */
class CheckSquareView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var checked = false
        set(value) { field = value; invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val square = androidx.core.graphics.PathParser.createPathFromPathData("M4 5l24 1-1 22-23-1z")
    private val tick = androidx.core.graphics.PathParser.createPathFromPathData("M8 16l6 7L27 3")

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 32f
        canvas.save()
        canvas.scale(s, s)
        line.color = Paper.INK
        line.strokeWidth = 2.6f
        canvas.drawPath(square, line)
        if (checked) {
            line.color = Paper.RED
            line.strokeWidth = 4f
            canvas.drawPath(tick, line)
        }
        canvas.restore()
    }
}

/** The chalkboard as a background, with chalk dust. */
class ChalkboardDrawable : android.graphics.drawable.Drawable() {
    override fun draw(canvas: Canvas) = Chalkboard.draw(canvas, bounds.width(), bounds.height())
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/** A span that writes part of a text in another font, for the fat "UP" in "Hands UP for confetti!". */
class FontSpan(private val face: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(paint: android.text.TextPaint) { paint.typeface = face }
    override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = face }
}
