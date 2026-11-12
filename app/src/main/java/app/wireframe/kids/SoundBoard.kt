package app.wireframe.kids

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

enum class Sound { POP, BOING, YAY, TICK, GO, TADA, MISS, WIN }

/**
 * Cartoon sound effects, made with maths when the app starts, so there are no sound files to ship.
 * Safe to call [play] from any thread.
 */
class SoundBoard : AutoCloseable {

    @Volatile var muted = false

    private val tracks = EnumMap<Sound, AudioTrack>(Sound::class.java)

    init {
        for (sound in Sound.entries) {
            try {
                tracks[sound] = staticTrack(synthesize(sound))
            } catch (e: Exception) {
                Log.w("SoundBoard", "Could not prepare $sound", e)
            }
        }
    }

    fun play(sound: Sound) {
        if (muted) return
        val track = tracks[sound] ?: return
        synchronized(track) {
            try {
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
                track.reloadStaticData()
                track.play()
            } catch (e: IllegalStateException) {
                Log.w("SoundBoard", "Could not play $sound", e)
            }
        }
    }

    override fun close() {
        tracks.values.forEach { it.release() }
        tracks.clear()
    }

    private fun staticTrack(samples: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }

    private companion object {
        const val RATE = 22_050
        const val C5 = 523.25f
        const val E5 = 659.25f
        const val G5 = 783.99f
        const val C6 = 1046.5f
        const val E6 = 1318.5f
        const val G6 = 1568.0f

        fun synthesize(sound: Sound): ShortArray = when (sound) {
            Sound.POP -> render(0.12f) { t, _ -> (noise() * 0.5f + wave(900f - 3000f * t, t)) * exp(-30f * t) }
            Sound.TICK -> render(0.08f) { t, _ -> wave(880f, t) * exp(-40f * t) }
            Sound.GO -> render(0.35f) { t, _ -> bell(1320f, t) * exp(-6f * t) }
            Sound.YAY -> melody(0.7f, listOf(0f to C6, 0.08f to E6, 0.16f to G6), decay = 6f)
            Sound.TADA -> melody(
                1.1f, listOf(0f to G5, 0.12f to C6, 0.26f to C6, 0.26f to E6, 0.26f to G6), decay = 3f,
            )
            Sound.WIN -> melody(
                1.6f,
                listOf(0f to C5, 0.1f to E5, 0.2f to G5, 0.3f to C6, 0.4f to E6, 0.5f to G6, 0.6f to C6, 0.6f to G6),
                decay = 2.5f,
            )
            Sound.BOING -> sweep(0.6f) { t ->
                val freq = 150f + 250f * (1 - exp(-10f * t)) + 60f * sin(2 * PI.toFloat() * 9f * t) * exp(-3f * t)
                freq to exp(-3.5f * t)
            }
            Sound.MISS -> sweep(0.45f) { t -> (500f - 550f * t) to exp(-4f * t) }
        }

        private fun noise() = (Math.random() * 2 - 1).toFloat()
        private fun wave(freq: Float, t: Float) = sin(2 * PI.toFloat() * freq * t)
        private fun bell(freq: Float, t: Float) = (wave(freq, t) + 0.3f * wave(freq * 2, t)) / 1.3f

        private fun render(seconds: Float, sample: (t: Float, i: Int) -> Float): ShortArray {
            val count = (seconds * RATE).toInt()
            return ShortArray(count) { i ->
                val t = i / RATE.toFloat()
                val fadeIn = min(1f, i / 40f) // avoids a click at the start
                val fadeOut = min(1f, (count - i) / 200f) // and at the end
                (sample(t, i) * fadeIn * fadeOut * 0.8f).coerceIn(-1f, 1f).times(Short.MAX_VALUE).toInt().toShort()
            }
        }

        /** Bell-like notes starting at the given times, each fading out. */
        private fun melody(seconds: Float, notes: List<Pair<Float, Float>>, decay: Float) = render(seconds) { t, _ ->
            var sum = 0f
            for ((start, freq) in notes) {
                if (t >= start) sum += bell(freq, t - start) * exp(-decay * (t - start))
            }
            sum / 2.2f
        }

        /** A tone whose pitch and loudness change over time ([shape] gives both for each moment). */
        private fun sweep(seconds: Float, shape: (Float) -> Pair<Float, Float>): ShortArray {
            var phase = 0.0
            return render(seconds) { t, _ ->
                val (freq, loudness) = shape(t)
                phase += 2 * PI * freq / RATE
                ((sin(phase) + 0.25 * sin(3 * phase)) / 1.25).toFloat() * loudness
            }
        }
    }
}
