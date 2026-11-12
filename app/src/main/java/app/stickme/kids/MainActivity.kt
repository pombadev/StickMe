package app.stickme.kids

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {

    // The screen. Rebuilt when the phone turns, because portrait and landscape have their own layouts.
    private lateinit var root: FrameLayout
    private lateinit var cameraPicture: PreviewView
    private lateinit var skeletons: SkeletonView
    private lateinit var nobody: View
    private lateinit var looking: View
    private lateinit var lookingDots: TextView
    private lateinit var logo: View
    private lateinit var friendsText: TextView
    private lateinit var tipNote: View
    private lateinit var recordingBadge: TextView
    private lateinit var hint: TextView
    private lateinit var cornerButtons: View
    private lateinit var dock: View
    private lateinit var recordDot: View
    private lateinit var recordMiniDot: View
    private lateinit var recordLabel: TextView
    private lateinit var gameControls: View
    private lateinit var gameHint: TextView
    private lateinit var gateLinks: View
    private lateinit var gateHint: View
    private lateinit var playAnyway: View
    private lateinit var stopGame: View
    private lateinit var recordMini: View
    private lateinit var cameraAsk: View
    private lateinit var wakeScreen: View
    private lateinit var gamesSheet: GamesSheet
    private lateinit var looksSheet: LooksSheet
    private lateinit var settingsFolder: SettingsFolder
    private lateinit var results: ResultsScreen

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var engine: PoseEngine? = null
    private val smoother = PoseSmoother()

    private val settings = SceneSettings()
    private val videoRecorder by lazy { VideoRecorder(applicationContext) }
    private val videoPainter = ScenePainter(settings)
    private val ticker = Handler(Looper.getMainLooper())
    private val sounds = SoundBoard()
    private val funEngine = FunEngine(settings, GameKit(sounds, SavedScores(), speak = ::speak)) // camera thread only
    private val prefs by lazy { getSharedPreferences("stickme", MODE_PRIVATE) }

    /** The phone's voice, for Simon Says. Null until the speech engine is ready. */
    @Volatile private var voice: TextToSpeech? = null

    private var useFrontCamera = false
    @Volatile private var cameraVisible = false
    private var analysis: ImageAnalysis? = null
    private var askedForRecordingPermissions = false

    // Camera-thread bookkeeping for what the chrome around the picture shows.
    @Volatile private var firstFrameSeen = false
    private var framesSinceReport = 0
    private var lastReportAt = SystemClock.elapsedRealtime()
    private var lastPersonSeenAt = 0L
    private var tipStartedAt = 0L
    private var shownPeople = -1
    private var shownTip = false
    private var shownNobody = false
    private var shownLooking: Boolean? = null
    private var shownKey: Any? = null

    private val askForCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraAsk.visibility = View.GONE
            setHint(null)
            if (!firstFrameSeen) wakeScreen.visibility = View.VISIBLE
            startCamera()
        } else {
            cameraAsk.visibility = View.GONE
            setHint(R.string.need_camera) { showCameraAsk() }
        }
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

    /** Best scores, kept between app launches. */
    private inner class SavedScores : ScoreBook {
        override fun best(game: GameKind) = prefs.getInt("best_${game.name}", 0)

        override fun offer(game: GameKind, score: Int): Boolean {
            if (score <= best(game)) return false
            prefs.edit().putInt("best_${game.name}", score).apply()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sounds.muted = prefs.getBoolean(PREF_MUTED, false)
        requestedOrientation = savedTurn().androidValue
        funEngine.games.onChanged = { playing ->
            runOnUiThread {
                if (playing != null) looksSheet.hide()
                updateBackgroundMusic()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        setUpScreen()
        startVoice()
        ticker.post(animateLooking)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        loadModel(savedModel())
        if (hasPermission(Manifest.permission.CAMERA)) startCamera() else showCameraAsk()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setUpScreen()
        if (hasPermission(Manifest.permission.CAMERA)) startCamera()
    }

    /** Builds the screen and connects every button. Safe to call again after the phone turns. */
    private fun setUpScreen() {
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        root.background = ChalkboardDrawable()
        cameraPicture = findViewById(R.id.cameraPicture)
        skeletons = findViewById(R.id.skeletons)
        skeletons.settings = settings
        nobody = findViewById(R.id.nobody)
        looking = findViewById(R.id.looking)
        lookingDots = findViewById(R.id.lookingDots)
        logo = findViewById(R.id.logo)
        friendsText = findViewById(R.id.friendsText)
        tipNote = findViewById(R.id.tipNote)
        recordingBadge = findViewById(R.id.recordingBadge)
        hint = findViewById(R.id.hint)
        cornerButtons = findViewById(R.id.cornerButtons)
        dock = findViewById(R.id.dock)
        recordDot = findViewById(R.id.recordDot)
        recordMiniDot = findViewById(R.id.recordMiniDot)
        recordLabel = findViewById(R.id.recordLabel)
        gameControls = findViewById(R.id.gameControls)
        gameHint = findViewById(R.id.gameHint)
        gateLinks = findViewById(R.id.gateLinks)
        gateHint = findViewById(R.id.gateHint)
        playAnyway = findViewById(R.id.playAnyway)
        stopGame = findViewById(R.id.stopGame)
        recordMini = findViewById(R.id.recordMini)
        cameraAsk = findViewById(R.id.cameraAsk)
        wakeScreen = findViewById(R.id.wakeScreen)
        wakeScreen.background = ChalkboardDrawable()
        cameraAsk.background = ChalkboardDrawable()

        tipNote.findViewById<TextView>(R.id.tipText).text = SpannableString(getString(R.string.hands_up_tip)).apply {
            val start = indexOf("UP")
            setSpan(FontSpan(Fonts.heading), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        gamesSheet = GamesSheet(findViewById(R.id.gamesSheet)) { kind -> funEngine.games.requestStart(kind) }
        looksSheet = LooksSheet(
            findViewById(R.id.looksSheet), settings,
            onLookChanged = { skeletons.invalidate(); updateBackgroundMusic() },
            onFaceChanged = { skeletons.invalidate() },
        )
        settingsFolder = SettingsFolder(
            findViewById(R.id.settingsFolder),
            onModel = { model ->
                prefs.edit().putString(PREF_MODEL, model.name).apply()
                if (engine?.model != model) loadModel(model)
            },
            onSound = { on ->
                sounds.muted = !on
                prefs.edit().putBoolean(PREF_MUTED, !on).apply()
                updateBackgroundMusic()
            },
            onTurn = { mode ->
                prefs.edit().putString(PREF_TURN, mode.name).apply()
                requestedOrientation = mode.androidValue
            },
        )
        settingsFolder.set(engine?.model ?: savedModel(), !sounds.muted, savedTurn())
        results = ResultsScreen(
            findViewById(R.id.resultsScreen),
            onPlayAgain = { kind -> funEngine.games.requestStart(kind) },
            onPickAnother = {
                funEngine.games.requestStop()
                gamesSheet.show()
            },
        )

        for (sheet in listOf<(() -> Unit) -> Unit>(
            { gamesSheet.onChange = it }, { looksSheet.onChange = it }, { settingsFolder.onChange = it }, { results.onChange = it },
        )) sheet { refreshNotes() }

        findViewById<View>(R.id.showCamera).setOnClickListener {
            cameraVisible = !cameraVisible
            cameraPicture.animate().alpha(if (cameraVisible) CAMERA_ALPHA else 0f).setDuration(250).start()
        }
        findViewById<View>(R.id.pickFace).setOnClickListener { looksSheet.show() }
        findViewById<View>(R.id.changeLook).setOnClickListener { looksSheet.show() }
        findViewById<View>(R.id.playGame).setOnClickListener { gamesSheet.show() }
        findViewById<View>(R.id.flipCamera).setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
        }
        findViewById<View>(R.id.openSettings).onLongHold(
            holdMs = 2000,
            onTooShort = { Toast.makeText(this, "Hold the lock for 2 seconds", Toast.LENGTH_SHORT).show() },
            onHeld = { settingsFolder.show() },
        )
        findViewById<View>(R.id.record).setOnClickListener { toggleRecording() }
        recordMini.setOnClickListener { toggleRecording() }
        stopGame.setOnClickListener { funEngine.games.requestStop() }
        findViewById<View>(R.id.backToGames).setOnClickListener {
            funEngine.games.requestStop()
            gamesSheet.show()
        }
        playAnyway.setOnClickListener { funEngine.games.requestSkipGate() }
        findViewById<View>(R.id.allowCamera).setOnClickListener { askForCamera.launch(Manifest.permission.CAMERA) }
        findViewById<View>(R.id.notNow).setOnClickListener {
            cameraAsk.visibility = View.GONE
            setHint(R.string.need_camera) { showCameraAsk() }
        }

        // Pick up where things were, in case the phone just turned.
        cameraPicture.alpha = if (cameraVisible) CAMERA_ALPHA else 0f
        wakeScreen.visibility = if (firstFrameSeen) View.GONE else View.VISIBLE
        showRecordingState(videoRecorder.isRecording)
        shownKey = null
        shownPeople = -1
        shownTip = false
        shownNobody = false
        shownLooking = null
    }

    private fun goBack() {
        when {
            results.isShown -> funEngine.games.requestStop()
            settingsFolder.isShown -> settingsFolder.hide()
            gamesSheet.isShown -> gamesSheet.hide()
            looksSheet.isShown -> looksSheet.hide()
            funEngine.games.isRunning -> funEngine.games.requestStop()
            else -> finish()
        }
    }

    // ---- hints and the camera question --------------------------------------------------------

    private fun setHint(text: Int?, onTap: (() -> Unit)? = null) {
        if (text == null) {
            hint.visibility = View.GONE
            return
        }
        hint.setText(text)
        hint.visibility = View.VISIBLE
        hint.setOnClickListener { onTap?.invoke() }
        hint.isClickable = onTap != null
    }

    private fun showCameraAsk() {
        wakeScreen.visibility = View.GONE
        setHint(null)
        cameraAsk.visibility = View.VISIBLE
    }

    // ---- the pose model ------------------------------------------------------------------------

    private fun savedTurn(): TurnMode =
        TurnMode.entries.firstOrNull { it.name == prefs.getString(PREF_TURN, null) } ?: TurnMode.FOLLOW

    private fun savedModel(): PoseModel =
        PoseModel.entries.firstOrNull { it.name == prefs.getString(PREF_MODEL, null) } ?: PoseModel.TINY

    /** Runs on the camera thread, so no frame is ever analysed with a half-loaded or closed model. */
    private fun loadModel(model: PoseModel) {
        if (firstFrameSeen) setHint(R.string.loading)
        worker.execute {
            val old = engine
            engine = null
            old?.close()
            try {
                engine = PoseEngine(applicationContext, model)
                smoother.reset()
                runOnUiThread { if (hint.text == getString(R.string.loading)) setHint(null) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load the pose models", e)
                runOnUiThread { Toast.makeText(this, "Could not load models: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---- the camera ----------------------------------------------------------------------------

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
                wakeScreen.visibility = View.GONE
                setHint(R.string.camera_failed) { setHint(null); startCamera() }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy, mirrored: Boolean) {
        image.use {
            val currentEngine = engine ?: return
            val upright = image.toBitmap().rotated(image.imageInfo.rotationDegrees)
            val people = smoother.smooth(currentEngine.findPeople(upright))
            val now = SystemClock.uptimeMillis()
            val extras = funEngine.update(people, now, upright.width, upright.height, visibleRegion(upright.width, upright.height))
            present(SceneFrame(people, upright, upright.width, upright.height, mirrored, extras), now)
        }
    }

    /** Shows one analysed frame: on screen, in the video if recording, and in the chrome around it. */
    internal fun present(frame: SceneFrame, now: Long = SystemClock.uptimeMillis()) {
        skeletons.show(frame)
        if (videoRecorder.isRecording) {
            val pictureAlpha = if (cameraVisible) (CAMERA_ALPHA * 255).toInt() else 0
            videoRecorder.drawFrame { canvas ->
                videoPainter.drawScene(canvas, videoRecorder.width, videoRecorder.height, frame, pictureAlpha)
            }
        }
        updateChrome(frame.people.size, frame.extras.game, now)
    }

    /** The part of the camera picture that shows on screen (the picture is cropped to fill the screen), in picture pixels. */
    private fun visibleRegion(imageWidth: Int, imageHeight: Int): RectF? {
        val viewWidth = skeletons.width
        val viewHeight = skeletons.height
        if (viewWidth == 0 || viewHeight == 0) return null
        val scale = max(viewWidth / imageWidth.toFloat(), viewHeight / imageHeight.toFloat())
        val w = viewWidth / scale
        val h = viewHeight / scale
        return RectF((imageWidth - w) / 2, (imageHeight - h) / 2, (imageWidth + w) / 2, (imageHeight + h) / 2)
    }

    /** Camera thread: works out what the chrome should show, and tells the screen only when something changed. */
    private fun updateChrome(people: Int, game: GameScene?, now: Long) {
        if (!firstFrameSeen) {
            firstFrameSeen = true
            lastPersonSeenAt = now // the search starts now, not when the app was opened
            runOnUiThread { wakeScreen.animate().alpha(0f).setDuration(250).withEndAction { wakeScreen.visibility = View.GONE; wakeScreen.alpha = 1f }.start() }
        }
        if (people > 0) lastPersonSeenAt = now
        val gameOn = game != null
        val nobodyHere = people == 0 && now - lastPersonSeenAt > NOBODY_DELAY_MS && !gameOn
        if (people > 0 && tipStartedAt == 0L) tipStartedAt = now
        val tip = people > 0 && !gameOn && now - tipStartedAt < TIP_MS
        val friends = if (gameOn) 0 else people

        val searching = people == 0 && !gameOn
        if (friends != shownPeople || tip != shownTip || nobodyHere != shownNobody || searching != shownLooking) {
            shownPeople = friends
            shownTip = tip
            shownNobody = nobodyHere
            shownLooking = searching
            runOnUiThread { refreshNotes() }
        }

        val key = listOf(game?.mode, game?.canSkip, game?.hint, game?.result)
        if (key != shownKey) {
            shownKey = key
            runOnUiThread { showGameState(game) }
        }

        framesSinceReport++
        val elapsed = now - lastReportAt
        if (elapsed >= 1000) {
            val fps = Math.round(framesSinceReport * 1000f / elapsed)
            framesSinceReport = 0
            lastReportAt = now
            runOnUiThread { settingsFolder.setSpeed(fps) }
        }
    }

    private var gameOn = false
    private var dotStep = 0

    /** Three dots that fill up one by one while the app is looking for someone. */
    private val animateLooking = object : Runnable {
        override fun run() {
            if (looking.visibility == View.VISIBLE) {
                dotStep = (dotStep + 1) % 4
                lookingDots.text = "•  ".repeat(dotStep.coerceAtLeast(1)).trim()
            }
            ticker.postDelayed(this, 450)
        }
    }

    /** The dock, logo and corner stickers belong to the play screen: the crayon sheet and games replace them. */
    private fun applyChrome() {
        val hidden = gameOn || looksSheet.isShown
        val visibility = if (hidden) View.GONE else View.VISIBLE
        dock.visibility = visibility
        cornerButtons.visibility = visibility
        logo.visibility = visibility
    }

    /** The notes on the board (who is here, the tip, "where are you?") hide while a sheet covers the board. */
    private fun refreshNotes() {
        applyChrome()
        val covered = gamesSheet.isShown || looksSheet.isShown || settingsFolder.isShown || results.isShown
        val friends = shownPeople
        friendsText.visibility = if (friends > 0 && !covered) View.VISIBLE else View.GONE
        friendsText.text = if (friends == 1) "1 friend here!" else "$friends friends here!"
        tipNote.visibility = if (shownTip && !covered) View.VISIBLE else View.GONE
        nobody.visibility = if (shownNobody && !covered) View.VISIBLE else View.GONE
        looking.visibility = if (shownLooking == true && !shownNobody && !covered) View.VISIBLE else View.GONE
    }

    /** Swaps the dock for the game controls (and back), and shows the results screen. */
    private fun showGameState(game: GameScene?) {
        val mode = game?.mode
        val playing = game != null
        gameOn = playing
        applyChrome()
        gameControls.visibility = if (playing && mode != SceneMode.RESULT) View.VISIBLE else View.GONE
        val inPlay = mode == SceneMode.PLAY
        stopGame.visibility = if (inPlay) View.VISIBLE else View.GONE
        recordMini.visibility = if (inPlay) View.VISIBLE else View.GONE
        gameHint.visibility = if (inPlay) View.VISIBLE else View.GONE
        gameHint.text = game?.hint.orEmpty()
        gateLinks.visibility = if (mode == SceneMode.FIT || mode == SceneMode.PICK) View.VISIBLE else View.GONE
        gateHint.visibility = if (mode == SceneMode.PICK) View.VISIBLE else View.GONE
        playAnyway.visibility = if (mode == SceneMode.FIT && game?.canSkip == true) View.VISIBLE else View.GONE
        val info = game?.result
        if (mode == SceneMode.RESULT && info != null) results.show(info) else results.hide()
        updateBackgroundMusic()
    }

    // ---- recording -----------------------------------------------------------------------------

    private fun toggleRecording() {
        if (videoRecorder.isRecording) stopRecording() else startRecordingAfterAsking()
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
        showRecordingState(true)
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
        showRecordingState(false)
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

    /** The record sticker shows a round dot, or a square while recording. */
    private fun showRecordingState(on: Boolean) {
        for (dot in listOf(recordDot, recordMiniDot)) {
            dot.background = GradientDrawable().apply {
                shape = if (on) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
                cornerRadius = 6f * resources.displayMetrics.density
                setColor(Paper.PAPER_LIGHT)
            }
            val size = ((if (on) 24 else if (dot === recordDot) 30 else 24) * resources.displayMetrics.density).toInt()
            dot.layoutParams = (dot.layoutParams as FrameLayout.LayoutParams).apply { width = size; height = size }
        }
        recordLabel.text = if (on) "stop" else "record"
        recordingBadge.visibility = if (on) View.VISIBLE else View.GONE
        val description = if (on) "Stop recording" else "Record a video"
        findViewById<View>(R.id.record).contentDescription = description
        recordMini.contentDescription = description
    }

    // ---- sound and voice -----------------------------------------------------------------------

    private fun startVoice() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(applicationContext) { status ->
            val ready = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            // Simon's words are English; use the phone's English voice when it has one.
            val english = ready.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            if (english) ready.language = Locale.US
            voice = ready
        }
    }

    private fun speak(words: String) {
        if (sounds.muted) return
        voice?.speak(words, TextToSpeech.QUEUE_FLUSH, null, "simon")
    }

    /** The music box plays with the ballerina look; games pick their own music, so leave it to them. */
    private fun updateBackgroundMusic() {
        if (funEngine.games.isRunning) return
        if (settings.look == Look.BALLERINA) sounds.startLoop(Sound.MUSIC_BOX_LOOP) else sounds.stopLoop()
    }

    // ---- life cycle ----------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        updateBackgroundMusic()
    }

    override fun onPause() {
        super.onPause()
        if (videoRecorder.isRecording) stopRecording()
        sounds.stopLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        ticker.removeCallbacks(animateLooking)
        voice?.shutdown()
        voice = null
        worker.execute {
            engine?.close()
            sounds.close()
        }
        worker.shutdown()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "StickMe"
        const val CAMERA_ALPHA = 0.6f
        const val PREF_MODEL = "pose_model"
        const val PREF_MUTED = "sound_muted"
        const val PREF_TURN = "screen_turn"
        const val NOBODY_DELAY_MS = 1500L
        const val TIP_MS = 12_000L
    }
}

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val turn = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, turn, true)
}
