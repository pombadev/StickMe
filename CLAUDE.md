# Stick Me!

Native Kotlin Android app for kids: the camera turns people into glowing stick figures in real time, with faces, looks, effects, video recording and 11 pose games. Pose tracking is RTMPose (whole-body, 133 points) run on the phone with ONNX Runtime. User-facing overview: README.md.

Remote: https://github.com/pombadev/StickMe (public). Licence: Apache 2.0.

## Working rules

- Open-ended requests ("make it more fun", "make it better", "add something cool" and similar): first write a plan of what you would add, wait for approval, then build. Do not write code before the plan is approved.
- Commit after each finished piece of work. Build big features in batches and commit each batch.
- Never commit APKs (`*.apk` is ignored); hand them over as `StickMe-<version>.apk` in the project root.

## Commands

```sh
./gradlew assembleDebug assembleDebugAndroidTest   # build app + tests
./gradlew connectedDebugAndroidTest                # run device tests (uninstalls the app afterwards)
./gradlew assembleRelease                          # test APK, signed with the debug key
```

To keep files a test writes (pictures under `getExternalFilesDir`), install both APKs and run
`adb shell am instrument -w [-e class app.stickme.kids.GamesTest] app.stickme.kids.test/androidx.test.runner.AndroidJUnitRunner`,
then `adb pull /sdcard/Android/data/app.stickme.kids/files/...`.

Emulator: AVD `Medium_Phone`. Its camera has no people, so check pose behaviour with device tests, not the live camera.

## Code map (`app/src/main/java/app/stickme/kids`)

- `MainActivity` — camera, buttons, menus, recording, music and voice. Frames are analysed on one `worker` thread.
- `PoseEngine` — detector + pose model; `PoseModel` lists the two pose models (tiny = default, medium).
- `PoseSmoother` — smoothing and stable person ids between frames.
- `ScenePainter` / `SkeletonView` — drawing; `Skeleton` — the 133-point layout and colours.
- `Effects.kt` — `Body` (pose questions), `FunEngine` (move reactions, sparkles, echoes, runs games), particles.
- `Games.kt` — `GameKind`, `MiniGame` base (countdown → play → results), `GameHost`, `GameTools`. One file per game.
- `Moves.kt` — shared detectors (jump, clap, movement speed). `Poses.kt` / `Ballet.kt` / `Workout.kt` — pose checks and pictures.
- `Faces.kt`, `SoundBoard.kt` (sounds and music made in code), `VideoRecorder.kt`.

## Things that are easy to get wrong

- Camera-thread state must reach the screen and the recorder as frozen snapshots (`SceneFrame`, `FunSnapshot`, `GameScene`); both draw at the same time, so each drawing thread gets its own painter.
- Image coordinates are the upright camera picture; map to the screen only through `ImageFit` (it handles cropping and front-camera mirroring).
- Real model output facing the camera has the left shoulder (point 5) on the picture's right. `TestPoses.standing()` is the other way round (it faces away).
- `FaceCatalog` lives outside `FaceStyle` on purpose: building the list inside its companion left nulls in it.
- Detection thresholds (freeze movement, ballet stillness, squat depth, jump height...) are guesses not yet tuned with real kids.
- Changing pre/post-processing must keep `PoseEngineTest` (match with the Python reference) passing.
