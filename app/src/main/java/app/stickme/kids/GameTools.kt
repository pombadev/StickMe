package app.stickme.kids

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The card at the top of the screen. */
class Card(
    val title: String,
    val picture: Pictogram? = null,
    /** Short text on the right, for things a tally can't show, such as hearts. */
    val corner: String? = null,
    val subtitle: String = "",
    /** A small line above the title, such as the game's name. */
    val kicker: String = "",
    /** The score, drawn as tally marks (and as a number once it gets big). */
    val tally: Int? = null,
    val unit: String = "points",
    val timeLeft: Float? = null,
    val highlight: Boolean = false,
    /** A checklist on the right: (label, done). */
    val checks: List<Pair<String, Boolean>>? = null,
)

/** A big bouncing word in the middle of the screen, like a countdown number or "GREAT!". */
class BigText(val text: String, val born: Long, val color: Int, val stays: Boolean = false)

/** Where and when a game is being drawn, plus the paints to draw with. */
class GameCanvas(val canvas: Canvas, val width: Int, val height: Int, val fit: ImageFit, val now: Long, val tools: GameTools)

/** Paints and drawing helpers for games, in the paper-and-chalk style. One per drawing thread. */
class GameTools(val effects: EffectsPainter) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    /** The paper card with what to do. Drawn under the stick figures so it never hides anyone. */
    fun drawCard(c: GameCanvas, info: Card) {
        val canvas = c.canvas
        val s = min(c.width, c.height).toFloat()
        val u = s / 390f // one unit of the design, which was drawn 390 wide
        val landscape = c.width > c.height
        val width = if (landscape) 300f * u else c.width - 36f * u
        val left = if (landscape) 22f * u else 18f * u
        val top = if (landscape) 26f * u else 58f * u
        val pad = 18f * u

        // Work out the card's height from what is on it.
        var y = top + 22f * u
        val kickerY = y + 15f * u
        if (info.kicker.isNotEmpty()) y += 20f * u
        val titleY = y + 27f * u
        y += 34f * u
        val subtitleY = y + 16f * u
        if (info.subtitle.isNotEmpty()) y += 24f * u
        val timerY = y + 20f * u
        if (info.timeLeft != null) y += 36f * u
        val checksHeight = info.checks?.let { 22f * u + it.size * 30f * u } ?: 0f
        val bottom = max(y + 12f * u, top + checksHeight)
        val right = left + width

        canvas.save()
        canvas.rotate(-1.5f, left + width / 2, (top + bottom) / 2)
        // A soft shadow, then the paper with its ruled lines.
        for ((grow, drop, alpha) in listOf(Triple(10f, 16f, 28), Triple(6f, 11f, 34), Triple(3f, 6f, 40))) {
            shadow.color = Color.argb(alpha, 0, 0, 0)
            canvas.drawRect(left - grow * u / 2, top + drop * u - grow * u / 2, right + grow * u / 2, bottom + drop * u + grow * u / 2, shadow)
        }
        fill.color = Paper.PAPER
        canvas.drawRect(left, top, right, bottom, fill)
        stroke.color = Paper.RULED_LINE
        stroke.strokeWidth = max(1f, u)
        var lineY = top + 27f * u
        while (lineY < bottom) {
            canvas.drawLine(left, lineY, right, lineY, stroke)
            lineY += 27f * u
        }
        if (info.highlight) {
            stroke.color = Paper.GREEN_OK
            stroke.strokeWidth = 4f * u
            canvas.drawRect(left + 2f * u, top + 2f * u, right - 2f * u, bottom - 2f * u, stroke)
        }
        canvas.save()
        canvas.translate(left + width / 2 - 45f * u, top - 11f * u)
        Tape.draw(canvas, 90f * u, 22f * u, Color.rgb(140, 203, 255), Color.rgb(190, 226, 255))
        canvas.restore()

        // Right side: score tally, a checklist, or a short corner text.
        var textLeft = left + pad
        var textRight = right - pad
        val picture = info.picture
        if (picture != null) {
            val size = 52f * u
            drawPictogram(canvas, picture, left + pad, titleY - 34f * u, size, if (info.highlight) Paper.GREEN_OK else Paper.INK)
            textLeft += size + 12f * u
        }

        info.tally?.let { textRight -= drawScore(canvas, info.tally, info.unit, right - pad, top + 20f * u, u) + 10f * u }
        info.checks?.let { textRight -= drawChecks(canvas, it, right - pad, top + 20f * u, u) + 10f * u }
        info.corner?.let { corner ->
            label.typeface = Fonts.hand
            label.textSize = 26f * u
            label.color = Paper.INK
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(corner, right - pad, top + 44f * u, label)
            textRight -= label.measureText(corner) + 10f * u
        }

        if (info.kicker.isNotEmpty()) {
            fitText(canvas, info.kicker, Fonts.hand, 19f * u, Paper.INK_BROWN, textLeft, kickerY, textRight - textLeft)
        }
        fitText(canvas, info.title, Fonts.heading, 30f * u, Paper.INK, textLeft, titleY, textRight - textLeft)
        if (info.subtitle.isNotEmpty()) {
            fitText(canvas, info.subtitle, Fonts.hand, 20f * u, Paper.INK_BROWN, textLeft, subtitleY, right - pad - textLeft)
        }
        info.timeLeft?.let { drawPencilTimer(canvas, it, left + pad, right - pad, timerY, u) }
        canvas.restore()
    }

    private fun fitText(canvas: Canvas, text: String, font: android.graphics.Typeface, size: Float, color: Int, x: Float, y: Float, room: Float) {
        label.typeface = font
        label.textSize = size
        label.color = color
        label.textAlign = Paint.Align.LEFT
        val wide = label.measureText(text) / max(room, 1f)
        if (wide > 1f) label.textSize = size / wide
        canvas.drawText(text, x, y, label)
    }

    /** The score as pencil tally marks (four strokes and a slash), or a number once there are too many. Returns its width. */
    private fun drawScore(canvas: Canvas, score: Int, unit: String, right: Float, top: Float, u: Float): Float {
        label.typeface = Fonts.hand
        label.textSize = 18f * u
        label.color = Paper.INK_BROWN
        label.textAlign = Paint.Align.RIGHT
        val caption = if (score == 1 && unit.endsWith("s")) unit.dropLast(1) else unit
        val captionText = "$score $caption"
        val captionWidth = label.measureText(captionText)
        canvas.drawText(captionText, right, top + 44f * u, label)
        if (score <= 10) {
            val groups = score / 5
            val rest = score % 5
            val groupWidth = 38f * u
            val width = groups * groupWidth + rest * 8f * u + (if (groups > 0 && rest > 0) 6f * u else 0f)
            stroke.color = Paper.INK
            stroke.strokeWidth = 3.4f * u
            var x = right - width
            repeat(groups) {
                for (k in 0 until 4) canvas.drawLine(x + k * 9f * u, top, x + k * 9f * u + u, top + 30f * u, stroke)
                canvas.drawLine(x - 4f * u, top + 24f * u, x + 33f * u, top + 6f * u, stroke)
                x += groupWidth
            }
            if (groups > 0 && rest > 0) x += 6f * u
            repeat(rest) {
                canvas.drawLine(x, top, x + u, top + 30f * u, stroke)
                x += 8f * u
            }
            return max(width, captionWidth)
        }
        label.typeface = Fonts.heading
        label.textSize = 34f * u
        label.color = Paper.INK
        canvas.drawText(score.toString(), right, top + 28f * u, label)
        return max(label.measureText(score.toString()), captionWidth)
    }

    /** A tick or a cross beside each word. Returns the width of the list. */
    private fun drawChecks(canvas: Canvas, checks: List<Pair<String, Boolean>>, right: Float, top: Float, u: Float): Float {
        label.typeface = Fonts.hand
        label.textSize = 22f * u
        val widest = checks.maxOf { label.measureText(it.first) }
        var y = top
        for ((name, ok) in checks) {
            label.color = if (ok) Paper.INK else Paper.RED_DARK
            label.textAlign = Paint.Align.LEFT
            val x = right - widest
            canvas.drawText(name, x, y + 20f * u, label)
            stroke.strokeWidth = 3.4f * u
            stroke.color = if (ok) Paper.GREEN_OK else Paper.RED_DARK
            val ix = x - 30f * u
            path.reset()
            if (ok) {
                path.moveTo(ix + 3f * u, y + 11f * u); path.lineTo(ix + 9f * u, y + 18f * u); path.lineTo(ix + 21f * u, y + 3f * u)
            } else {
                path.moveTo(ix + 5f * u, y + 5f * u); path.lineTo(ix + 19f * u, y + 19f * u)
                path.moveTo(ix + 19f * u, y + 5f * u); path.lineTo(ix + 5f * u, y + 19f * u)
            }
            canvas.drawPath(path, stroke)
            y += 30f * u
        }
        return widest + 34f * u
    }

    /** A hand-drawn red line that fills as time runs out, with a pencil at its tip. */
    private fun drawPencilTimer(canvas: Canvas, left: Float, from: Float, to: Float, y: Float, u: Float) {
        val width = to - from
        stroke.strokeWidth = 5f * u
        stroke.color = Color.argb(46, 34, 32, 28)
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f * u, 8f * u), 0f)
        canvas.drawLine(from, y, to, y, stroke)
        stroke.pathEffect = null
        val filled = (width * left.coerceIn(0f, 1f))
        stroke.color = Paper.RED
        path.reset()
        var x = 0f
        path.moveTo(from, y)
        while (x < filled) {
            x = min(filled, x + 4f * u)
            path.lineTo(from + x, y + sin(x / (22f * u)) * 2.6f * u)
        }
        canvas.drawPath(path, stroke)
        // The pencil: yellow body, wooden tip, pointing at the line it is drawing.
        canvas.save()
        canvas.translate(from + filled - 6f * u, y - 4f * u)
        canvas.rotate(-25f)
        fill.color = Paper.YELLOW
        canvas.drawRect(6f * u, -8f * u, 34f * u, 2f * u, fill)
        fill.color = Color.parseColor("#E8C49A")
        path.reset()
        path.moveTo(6f * u, -8f * u); path.lineTo(6f * u, 2f * u); path.lineTo(-2f * u, -3f * u); path.close()
        canvas.drawPath(path, fill)
        stroke.color = Paper.INK
        stroke.strokeWidth = 1.6f * u
        canvas.drawPath(path, stroke)
        canvas.drawRect(6f * u, -8f * u, 34f * u, 2f * u, stroke)
        canvas.restore()
    }

    /** Countdown numbers and cheers in the middle of the screen: cut-out letters, drawn on top of everything. */
    fun drawBigText(c: GameCanvas, text: BigText) {
        val s = min(c.width, c.height).toFloat()
        val life = ((c.now - text.born) / BIG_TEXT_MS.toFloat()).coerceAtLeast(0f)
        if (life >= 1f && !text.stays) return
        val grow = EffectsPainter.bounce(min(life, 1f))
        val alpha = if (text.stays) 1f else EffectsPainter.fadeOut(life)
        val size = s * (if (text.text.length <= 2) 0.26f else 0.13f)
        val cx = c.width / 2f
        val cy = c.height * 0.45f
        c.canvas.saveLayerAlpha(0f, 0f, c.width.toFloat(), c.height.toFloat(), (alpha * 255).toInt())
        c.canvas.scale(grow, grow, cx, cy)
        RansomText.draw(c.canvas, text.text, cx, cy, size, maxWidth = c.width * 0.92f / grow, lastIsRed = text.text.endsWith("!"))
        c.canvas.restore()
    }

    /** A number or word floating above someone's head, in image coordinates. */
    fun drawLabel(c: GameCanvas, text: String, imageX: Float, imageY: Float, imageSize: Float, color: Int) {
        val size = imageSize * c.fit.scale
        val y = maxOf(c.fit.y(imageY), size * 1.1f)
        effects.drawComicText(c.canvas, text, c.fit.x(imageX), y, size, color)
    }

    /** A handwritten caption at a spot in the picture, such as "watching" under someone's feet. */
    fun drawCaption(c: GameCanvas, text: String, imageX: Float, imageY: Float, size: Float, color: Int, tilt: Float = 0f, sticker: Boolean = false) {
        val x = c.fit.x(imageX)
        val y = c.fit.y(imageY)
        label.typeface = Fonts.hand
        label.textSize = size
        label.textAlign = Paint.Align.CENTER
        c.canvas.save()
        c.canvas.rotate(tilt, x, y)
        if (sticker) {
            val w = label.measureText(text)
            fill.color = Paper.YELLOW
            box.set(x - w / 2 - size * 0.5f, y - size * 1.0f, x + w / 2 + size * 0.5f, y + size * 0.35f)
            shadow.color = Color.argb(100, 0, 0, 0)
            c.canvas.drawRect(box.left, box.top + size * 0.18f, box.right, box.bottom + size * 0.18f, shadow)
            c.canvas.drawRect(box, fill)
            label.color = Paper.INK
        } else {
            label.color = color
        }
        c.canvas.drawText(text, x, y, label)
        c.canvas.restore()
    }

    /** Handwritten text at a spot on the screen. */
    fun drawCaptionScreen(c: GameCanvas, text: String, x: Float, y: Float, size: Float, color: Int, tilt: Float = 0f) {
        label.typeface = Fonts.hand
        label.textSize = size
        label.textAlign = Paint.Align.CENTER
        label.color = color
        c.canvas.save()
        c.canvas.rotate(tilt, x, y)
        c.canvas.drawText(text, x, y, label)
        c.canvas.restore()
    }

    fun drawPictogram(canvas: Canvas, picture: Pictogram, left: Float, top: Float, size: Float, color: Int) {
        val unit = size / 100f
        stroke.color = color
        stroke.strokeWidth = 7f * unit
        for (line in picture.lines) {
            for (i in 0 until line.size - 2 step 2) {
                canvas.drawLine(
                    left + line[i] * unit, top + line[i + 1] * unit,
                    left + line[i + 2] * unit, top + line[i + 3] * unit, stroke,
                )
            }
        }
        fill.color = color
        canvas.drawCircle(left + picture.headX * unit, top + picture.headY * unit, 8f * unit, fill)
    }

    private companion object {
        const val BIG_TEXT_MS = 1100L
    }
}
