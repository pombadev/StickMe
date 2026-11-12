package app.stickme.kids

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraPicture: PreviewView
    private lateinit var skeletons: SkeletonView
    private lateinit var hint: TextView
    private lateinit var settingsPanel: View
    private lateinit var speedInfo: TextView
    private lateinit var recordingBadge: TextView
    private lateinit var recordButton: View
    private lateinit var facePicker: HorizontalScrollView
    private lateinit var faceOptions: LinearLayout
    private lateinit var gameMenu: View
    private lateinit var gameOptions: android.widget.GridLayout

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var engine: PoseEngine? = null
    private val smoother = PoseSmoother()

    private val settings = SceneSettings()
    private val videoRecorder by lazy { VideoRecorder(applicationContext) }
    private val videoPainter = ScenePainter(settings)
    private val ticker = Handler(Looper.getMainLooper())
    private val sounds = SoundBoard()
    private val funEngine = FunEngine(settings, GameKit(sounds, SavedScores())) // camera thread only

    /** Best scores, kept between app launches. */
    private inner class SavedScores : ScoreBook {
        override fun best(game: GameKind) = prefs.getInt("best_${game.name}", 0)

        override fun offer(game: GameKind, score: Int): Boolean {
            if (score <= best(game)) return false
            prefs.edit().putInt("best_${game.name}", score).apply()
            return true
        }
    }

    private var useFrontCamera = false
    @Volatile private var cameraVisible = false
    private var analysis: ImageAnalysis? = null
    private var askedForRecordingPermissions = false

    private var framesSinceReport = 0
    private var lastReportAt = SystemClock.elapsedRealtime()
    private var lastPersonSeenAt = SystemClock.elapsedRealtime()
    private var hintShown = false // camera thread; false makes the next frame replace the "waking up" text
    private val autoClosers = HashMap<View, Runnable>()

    private val askForCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else hint.setText(R.string.need_camera)
    }

    private val askForRecordingPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startRecording() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            analysis?.targetRotation = cameraPicture.display?.rotation ?: return
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraPicture = findViewById(R.id.cameraPicture)
        skeletons = findViewById(R.id.skeletons)
        hint = findViewById(R.id.hint)
        recordingBadge = findViewById(R.id.recordingBadge)
        recordButton = findViewById(R.id.record)
        facePicker = findViewById(R.id.facePicker)
        faceOptions = findViewById(R.id.faceOptions)
        gameMenu = findViewById(R.id.gameMenu)
        gameOptions = findViewById(R.id.gameOptions)
        settingsPanel = findViewById(R.id.settingsPanel)
        speedInfo = findViewById(R.id.speedInfo)
        skeletons.settings = settings

        findViewById<TextView>(R.id.showCamera).setOnClickListener {
            cameraVisible = !cameraVisible
            cameraPicture.animate().alpha(if (cameraVisible) CAMERA_ALPHA else 0f).setDuration(250).start()
        }
        findViewById<TextView>(R.id.changeLook).setOnClickListener {
            settings.look = Look.entries[(settings.look.ordinal + 1) % Look.entries.size]
            skeletons.invalidate()
            showToast(LOOK_NAMES.getValue(settings.look))
        }

        val playGame = findViewById<TextView>(R.id.playGame)
        playGame.setOnClickListener {
            when {
                funEngine.games.isRunning -> funEngine.games.requestStop()
                gameMenu.visibility == View.VISIBLE -> hide(gameMenu)
                else -> {
                    hide(facePicker)
                    showForAWhile(gameMenu, GAME_MENU_CLOSE_MS)
                }
            }
        }
        buildGameMenu()
        funEngine.games.onChanged = { playing ->
            runOnUiThread {
                playGame.text = if (playing != null) "⏹" else "🎯"
                playGame.contentDescription = if (playing != null) "Stop the game" else "Choose a game"
                if (playing != null) {
                    hide(facePicker)
                    hide(settingsPanel)
                    hide(gameMenu)
                }
            }
        }

        findViewById<TextView>(R.id.flipCamera).setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }
        findViewById<TextView>(R.id.pickFace).setOnClickListener {
            if (facePicker.visibility == View.VISIBLE) hide(facePicker) else showForAWhile(facePicker)
        }
        findViewById<TextView>(R.id.openSettings).setOnClickListener {
            if (settingsPanel.visibility == View.VISIBLE) hide(settingsPanel) else showForAWhile(settingsPanel)
        }
        recordButton.setOnClickListener {
            if (videoRecorder.isRecording) stopRecording() else startRecordingAfterAsking()
        }
        buildFacePicker()

        val toggleSound = findViewById<TextView>(R.id.toggleSound)
        fun showSound() { toggleSound.text = if (sounds.muted) "🔇  Sounds off" else "🔊  Sounds on" }
        sounds.muted = prefs.getBoolean(PREF_MUTED, false)
        showSound()
        toggleSound.setOnClickListener {
            sounds.muted = !sounds.muted
            prefs.edit().putBoolean(PREF_MUTED, sounds.muted).apply()
            showSound()
            showForAWhile(settingsPanel)
        }

        val switchModel = findViewById<TextView>(R.id.switchModel)
        val savedModel = savedModel()
        switchModel.text = savedModel.label
        switchModel.setOnClickListener {
            val current = engine?.model ?: return@setOnClickListener // still loading
            val next = PoseModel.entries[(current.ordinal + 1) % PoseModel.entries.size]
            switchModel.text = next.label
            prefs.edit().putString(PREF_MODEL, next.name).apply()
            loadModel(next)
            showForAWhile(settingsPanel)
        }

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)

        loadModel(savedModel)

        if (hasPermission(Manifest.permission.CAMERA)) startCamera() else askForCamera.launch(Manifest.permission.CAMERA)
    }

    private val prefs by lazy { getSharedPreferences("stickme", MODE_PRIVATE) }

    private fun savedModel(): PoseModel =
        PoseModel.entries.firstOrNull { it.name == prefs.getString(PREF_MODEL, null) } ?: PoseModel.TINY

    /** Runs on the camera thread, so no frame is ever analysed with a half-loaded or closed model. */
    private fun loadModel(model: PoseModel) {
        hint.setText(R.string.loading)
        hint.visibility = View.VISIBLE
        worker.execute {
            val old = engine
            engine = null
            old?.close()
            try {
                engine = PoseEngine(applicationContext, model)
                smoother.reset()
                hintShown = false
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the pose models", e)
                runOnUiThread { Toast.makeText(this, "Could not load models: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun buildFacePicker() {
        val size = dp(56)
        val gap = dp(5)
        FaceCatalog.all.forEach { style ->
            val option = TextView(this).apply {
                text = style.button
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setOnClickListener { button ->
                    settings.face = style
                    highlightChosenFace()
                    skeletons.invalidate()
                    if (style == FaceStyle.Mix) {
                        // Stay open so kids can keep rolling the dice.
                        FaceCatalog.rollNewMix()
                        button.animate().rotationBy(360f).setDuration(350).start()
                        showForAWhile(facePicker)
                    } else {
                        hide(facePicker)
                    }
                }
            }
            faceOptions.addView(option, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, 0, gap, 0) })
        }
        highlightChosenFace()
    }

    private fun highlightChosenFace() {
        FaceCatalog.all.forEachIndexed { i, style ->
            faceOptions.getChildAt(i).setBackgroundResource(
                if (style == settings.face) R.drawable.face_option_selected else R.drawable.round_button,
            )
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val fourByThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourByThree).build())
                .build()
                .also { it.surfaceProvider = cameraPicture.surfaceProvider }

            val smallFrames = ResolutionSelector.Builder()
                .setAspectRatioStrategy(fourByThree)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            val newAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(smallFrames)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .apply { cameraPicture.display?.let { setTargetRotation(it.rotation) } }
                .build()
            val mirrored = useFrontCamera
            newAnalysis.setAnalyzer(worker) { frame -> analyze(frame, mirrored) }

            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                smoother.reset()
                provider.bindToLifecycle(this, selector, preview, newAnalysis)
                analysis = newAnalysis
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed to start", e)
                hint.text = "Camera failed to start: ${e.message}"
                hint.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy, mirrored: Boolean) {
        image.use {
            val currentEngine = engine ?: return
            val upright = image.toBitmap().rotated(image.imageInfo.rotationDegrees)
            val people = smoother.smooth(currentEngine.findPeople(upright))
            val extras = funEngine.update(people, SystemClock.uptimeMillis(), upright.width, upright.height)
            val frame = SceneFrame(people, upright, upright.width, upright.height, mirrored, extras)
            skeletons.show(frame)
            if (videoRecorder.isRecording) {
                val pictureAlpha = if (cameraVisible) (CAMERA_ALPHA * 255).toInt() else 0
                videoRecorder.drawFrame { canvas ->
                    videoPainter.drawScene(canvas, videoRecorder.width, videoRecorder.height, frame, pictureAlpha)
                }
            }
            reportSpeed(people.size)
        }
    }

    private fun startRecordingAfterAsking() {
        val missing = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT < 29 && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        // Ask once per app run; if the kid says no to the microphone we still record, just without sound.
        if (missing.isEmpty() || askedForRecordingPermissions) {
            startRecording()
        } else {
            askedForRecordingPermissions = true
            askForRecordingPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startRecording() {
        val portrait = skeletons.height >= skeletons.width
        val (width, height) = if (portrait) 720 to 1280 else 1280 to 720
        val withSound = hasPermission(Manifest.permission.RECORD_AUDIO)
        try {
            videoRecorder.start(width, height, withSound)
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed to start", e)
            Toast.makeText(this, "Couldn't start recording: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        recordButton.setBackgroundResource(R.drawable.stop_button)
        recordButton.contentDescription = "Stop recording"
        recordingBadge.visibility = View.VISIBLE
        ticker.post(updateRecordingBadge)
    }

    private val updateRecordingBadge = object : Runnable {
        override fun run() {
            if (!videoRecorder.isRecording) return
            val seconds = videoRecorder.secondsSoFar
            recordingBadge.text = "● REC  %d:%02d".format(seconds / 60, seconds % 60)
            ticker.postDelayed(this, 500)
        }
    }

    private fun stopRecording() {
        ticker.removeCallbacks(updateRecordingBadge)
        recordButton.setBackgroundResource(R.drawable.record_button)
        recordButton.contentDescription = "Record a video"
        recordingBadge.visibility = View.GONE
        // Stopping runs on the camera thread so it can never cut a frame in half.
        worker.execute {
            val message = when (val result = videoRecorder.stopAndSave()) {
                is RecordingResult.Saved -> "Video saved to your Gallery 🎬"
                RecordingResult.TooShort -> "That was too short to save. Try a longer one!"
                is RecordingResult.Failed -> "Couldn't save the video: ${result.reason}"
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    /** Camera thread: keeps the speed in the settings panel and shows the hint only while nobody is in view. */
    private fun reportSpeed(peopleCount: Int) {
        val now = SystemClock.elapsedRealtime()
        if (peopleCount > 0) lastPersonSeenAt = now
        // During the game the card already says what to do, so the hint would only get in its way.
        val showHint = now - lastPersonSeenAt > NOBODY_HINT_DELAY_MS && !funEngine.games.isRunning
        if (showHint != hintShown) {
            hintShown = showHint
            runOnUiThread {
                hint.setText(R.string.point_at_someone)
                hint.visibility = if (showHint) View.VISIBLE else View.GONE
            }
        }

        framesSinceReport++
        val elapsed = now - lastReportAt
        if (elapsed < 1000) return
        val fps = framesSinceReport * 1000f / elapsed
        framesSinceReport = 0
        lastReportAt = now
        runOnUiThread { speedInfo.text = "Speed: %.0f pictures a second".format(fps) }
    }

    /** Opens a pop-up row or panel and closes it again if nobody touches it for a few seconds. */
    private fun showForAWhile(view: View, closeAfterMs: Long = AUTO_CLOSE_MS) {
        view.visibility = View.VISIBLE
        val closer = autoClosers.getOrPut(view) { Runnable { hide(view) } }
        ticker.removeCallbacks(closer)
        ticker.postDelayed(closer, closeAfterMs)
    }

    /** A grid of big game pictures with short names; tapping one starts that game. */
    private fun buildGameMenu() {
        val size = dp(76)
        for (kind in GameKind.entries) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.round_button)
                contentDescription = kind.label
                setOnClickListener {
                    hide(gameMenu)
                    funEngine.games.requestStart(kind)
                }
                addView(TextView(context).apply {
                    text = kind.emoji
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                })
                addView(TextView(context).apply {
                    text = kind.label
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    maxLines = 1
                })
            }
            gameOptions.addView(item, android.widget.GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
    }

    private fun hide(view: View) {
        autoClosers[view]?.let(ticker::removeCallbacks)
        view.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        if (videoRecorder.isRecording) stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        worker.execute {
            engine?.close()
            sounds.close()
        }
        worker.shutdown()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showToast(text: String) =Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "StickMe"
        const val CAMERA_ALPHA = 0.6f
        const val PREF_MODEL = "pose_model"
        const val PREF_MUTED = "sound_muted"
        const val NOBODY_HINT_DELAY_MS = 1500L
        const val AUTO_CLOSE_MS = 5000L
        const val GAME_MENU_CLOSE_MS = 10_000L
        val LOOK_NAMES = mapOf(
            Look.NEON to "💡 Neon",
            Look.RAINBOW to "🌈 Rainbow",
            Look.SPARKLE to "✨ Sparkles — wave your hands!",
            Look.ECHO to "👻 Echo — dance!",
        )
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val turn = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, turn, true)
}
