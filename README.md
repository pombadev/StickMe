# Stick Me!

A fun Android app for kids: point the camera at someone and they turn into a glowing stick figure, live. Everything runs on the phone, with no internet needed.

Built with [RTMPose](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose), which finds people and places 133 points on each one (body, feet, face and hands).

## What kids can do

- **Glowing chalk stick figures** for up to 4 people at once, with faces and fingers, drawn on a chalkboard. While the app is still looking for someone it says "looking for a friend".
- **Look** (crayon box): Neon, Rainbow, Sparkle trails, Echo ghosts, Ballerina (tutu, tiara, ribbons and music-box music)
- **Face**: drawn faces whose mouth and eyes follow the person, lots of emoji faces, and a dice button to roll random ones
- **Magic moves**: hands up → confetti, clap → fireworks, jump → stars
- **Record**: saves a video of what's on screen to the Gallery (Movies/StickMe)
- **Camera**: switch between the front and back camera
- **Games**: peel a sticker off the games sheet to play. Every game first asks you to step back until your head, hands and feet (whatever the game needs) are in view, then counts down 3-2-1. When it ends, a results screen stays until you pick "Play again" or another game.

| Game | How it plays |
|---|---|
| Copy me | Copy the pose in the picture and hold it |
| Bubbles | Pop floating bubbles with your hands |
| Freeze dance | Dance; when the music stops, freeze |
| Jumps | Jump as many times as you can in 30 seconds |
| Meteors | Dodge falling meteors |
| Limbo | Duck under a bar that gets lower each level |
| Mirror me | Two players: one poses, the other copies |
| Simon says | Only move when Simon says (uses the phone's voice) |
| Air paint | Draw in the air with a finger; clap to clear |
| Workout | 5 exercises, every move counted |
| Ballet class | Copy ballet positions and hold them gracefully |

**Who plays:** solo games (Copy me, Limbo, Simon says, Air paint, Workout, Ballet class) with two or more people in view ask everyone to raise their hands; whoever keeps them up while the yellow line fills is the player, and the others watch. Freeze dance, Jumps, Bubbles and Meteors let everyone play at once, and Mirror me needs two people.

## For grown-ups

Hold the lock button for 2 seconds to open the grown-ups folder:

- **Tracking**: Fast (default) or Detailed
- **Sounds and music**: on or off
- **Screen**: follow the phone, stay upright, or stay sideways

## Privacy

The app has no internet permission. The camera picture is only used on the phone, nothing is uploaded, and videos are saved only when the record button is pressed.

## Building

Needs the Android SDK and Java 17.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install on a connected phone
./gradlew assembleRelease      # signed with the debug key, for testing only
```

Tests run on a phone or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

They check the model output against a Python reference, play every game with made-up poses, and record a short video.

## How it works

1. CameraX hands each frame to a background thread.
2. **RTMDet-nano** finds people (320 × 320 input).
3. **RTMPose whole-body** (tiny or medium, 256 × 192 input) places 133 points on all of them in one batch.
4. Points are smoothed between frames, then effects, faces and games are drawn on top.

Models run with [ONNX Runtime](https://onnxruntime.ai/) on the CPU. The app is large (about 150 MB) mostly because it carries both pose models.

## Look and feel

The "paper and chalk" design: a chalkboard with paper cards, tape and sticker buttons. It uses two fonts bundled in the app, [Bagel Fat One](https://fonts.google.com/specimen/Bagel+Fat+One) and [Gochi Hand](https://fonts.google.com/specimen/Gochi+Hand), both under the SIL Open Font License (copies in `licenses/`).

## Models and credits

The model files in `app/src/main/assets` are the ONNX exports published by OpenMMLab:

| File | Model |
|---|---|
| `person_detector.onnx` | RTMDet-nano, person only |
| `wholebody_pose_tiny.onnx` | RTMPose-t whole-body (DWPose distilled, COCO-WholeBody + UBody) |
| `wholebody_pose_medium.onnx` | RTMPose-m whole-body (DWPose distilled, COCO-WholeBody + UBody) |

[MMPose](https://github.com/open-mmlab/mmpose) and [DWPose](https://github.com/IDEA-Research/DWPose) are released under the Apache License 2.0. The models were trained on public datasets that have their own terms of use; check those before any commercial use.

The test photo `app/src/androidTest/assets/skiers.jpg` comes from MMPose's test data (originally from the COCO dataset).

## License

Copyright 2026 pombadev

Licensed under the [Apache License, Version 2.0](LICENSE).
