package app.stickme.kids

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import kotlin.random.Random

/** The "paper & chalk" look: chalk on a dark board, paper cut-outs, tape and stickers. Mirrors res/values/colors.xml. */
object Paper {
    val CHALKBOARD = Color.parseColor("#1E2420")
    val CHALK = Color.parseColor("#EDEBE3")
    val CHALK_DIM = Color.parseColor("#B8BDB4")
    val PAPER = Color.parseColor("#F6F0E1")
    val PAPER_LIGHT = Color.parseColor("#FBF7EC")
    val INK = Color.parseColor("#22201C")
    val INK_BROWN = Color.parseColor("#4A3F33")
    val CARDBOARD = Color.parseColor("#C89A64")
    val YELLOW = Color.parseColor("#FFE27A")
    val PINK = Color.parseColor("#FF9FC6")
    val MINT = Color.parseColor("#8FF2C8")
    val BLUE = Color.parseColor("#8CCBFF")
    val PEACH = Color.parseColor("#FFC28A")
    val RED = Color.parseColor("#E8483B")
    val RED_DARK = Color.parseColor("#C8362B")
    val GREEN_OK = Color.parseColor("#1E9E64")
    val RULED_LINE = Color.argb(56, 90, 140, 200)

    /** Colours handed out in turn to stickers, letter tiles and crayons. */
    val STICKERS = intArrayOf(YELLOW, MINT, BLUE, PINK, PEACH, CHALK)

    /** Letter tile colours for cut-out words. */
    private val TILES = intArrayOf(YELLOW, PAPER, BLUE, PINK, PAPER, MINT, PEACH)
    fun tileColor(index: Int) = TILES[index % TILES.size]
}

/** The two fonts: fat rounded letters for titles, handwriting for the rest. Call [init] once at startup. */
object Fonts {
    @Volatile var heading: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        private set
    @Volatile var hand: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private set

    fun init(context: Context) {
        ResourcesCompat.getFont(context, R.font.bagel_fat_one)?.let { heading = it }
        ResourcesCompat.getFont(context, R.font.gochi_hand)?.let { hand = it }
    }
}

class StickMeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fonts.init(this)
    }
}

/**
 * The dark chalkboard with a fine dusting of chalk dust. Draws the same way on the screen
 * and into recorded videos.
 */
object Chalkboard {
    private val dust: Bitmap by lazy { noise(white = true, strength = 0.42f) }
    private val dustPaint by lazy {
        Paint().apply { shader = BitmapShader(dust, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Paper.CHALKBOARD)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dustPaint)
    }

    /** Random speckles, tileable. [strength] is the strongest speckle's opacity (0 to 1). */
    fun noise(white: Boolean, strength: Float, size: Int = 128, seed: Int = 5): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            // Same recipe as the design's turbulence: most specks are faint, a few are stronger.
            val alpha = ((random.nextFloat() * 0.7f - 0.28f).coerceAtLeast(0f) / 0.42f * strength * 255).toInt()
            if (white) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 51, 31, 13)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
