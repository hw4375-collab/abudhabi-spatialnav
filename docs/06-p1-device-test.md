# P0.1 + P1 — TTS fix and ARCore capability / live pose

Same install procedure as `docs/04-p0-device-test.md`:

```bash
git pull
cd android && ./gradlew installDebug
adb logcat -c && adb logcat -s P0Check:I P1Check:I
```

`P0Check` is the CameraX/TTS screen, `P1Check` is the ARCore screen.

## P0.1 — TTS

The manifest had no `<queries>` block. Android 11+ package visibility hides every TTS engine
from an app that does not declare the `android.intent.action.TTS_SERVICE` intent, so
`TextToSpeech` found no engine and `onInit` returned `ERROR`. The queries block is now
declared.

Re-run the P0 screen. Expected:

```
[PASS   ] TTS_INIT — engine ready, en-US available
[PASS   ] TTS_SPEAK — utterance completed — did you hear it?
```

If `TTS_INIT` still fails, the line now says which of the two causes it is:

* `engine init status=-1` — still no engine visible. Check Settings -> Accessibility ->
  Text-to-speech output actually lists an engine (some Xiaomi ROMs ship none; install Google
  Speech Services from the Play Store).
* `en-US unavailable (code -1/-2)` — engine present, voice data missing. Install the English
  voice from the same settings screen.

Also note: the frame rate line is now a **3-second rolling window**, not a lifetime average,
so thermal throttling shows up while you watch.

## P1 — ARCore

Tap **ARCore diagnostic** on the P0 screen. The AR screen shows the live camera with the
readout on top. Four things are checked before any pose appears:

```
ARCore  : SUPPORTED
Session : created
AR APK  : 1.49.xxxxx            <- installed Google Play Services for AR
Depth   : SUPPORTED (AUTOMATIC=yes, RAW_DEPTH_ONLY=yes)
AR fps  : 29.8 (3 s window, 412 frames)

Tracking: TRACKING

Position (local origin)
X right   : +0.000 m
Y up      : +0.000 m
Z forward : +0.000 m

Orientation
Yaw   : +0.0 deg
Pitch : -3.2 deg   Roll: +0.4 deg
quat  : ...
world : ...
```

`ARCore`, `Depth` and the AR APK version are read from the live session
(`ArCoreApk.checkAvailability`, `Session.isDepthModeSupported`), not from a device table.

### Coordinate convention

The origin is the pose of the **first tracked frame** — stand where you want zero to be, and
look in the direction you want to call "forward", before the numbers settle. `Reset origin
here` re-zeroes at any time.

* `Z forward` grows when you walk the way you were facing at the origin.
* `X right` grows when you sidestep right.
* `Y up` grows when you raise the phone.
* `Yaw` is degrees turned relative to the start heading, **positive when turning right**,
  wrapping at ±180.

### PASS / FAIL

| Check | PASS | FAIL |
| --- | --- | --- |
| ARCore availability | `ARCore : SUPPORTED`, `Session : created` | `unsupported device (...)` or `session creation failed: ...` — copy the line verbatim |
| Play Services for AR | a version number appears | `not installed / not visible` — let the app's install prompt run, or install "Google Play Services for AR" from the Play Store |
| Depth | `SUPPORTED (AUTOMATIC=yes, ...)` | `UNSUPPORTED (no depth mode)` — obstacle detection (P4) is off the table on this phone; tell us immediately |
| Tracking | `Tracking: TRACKING` within a few seconds of slow movement | stays `PAUSED`/`STOPPED`; the reason is printed (`INSUFFICIENT_FEATURES`, `EXCESSIVE_MOTION`, `INSUFFICIENT_LIGHT`, `CAMERA_UNAVAILABLE`) |
| Stand still | all numbers stable within a few cm over ~15 s | values drift steadily — report the drift per minute |
| Walk forward 2 m | `Z forward` reads roughly +2.0 m (±0.2 m is fine) | wrong axis, wrong sign, or badly wrong scale |
| Sidestep 1 m right | `X right` reads roughly +1.0 m, `Z` barely changes | |
| Turn right 90 deg | `Yaw` reads roughly +90 deg | sign reversed — report it, it is a one-line fix |
| Walk out and back | returning to the start reads near 0,0,0 | large offset = VIO drift; report the magnitude, it sets how often we need Cloud Anchors in P2 |
| Thermals | `AR fps` stays above ~20 for 3 minutes | report when it drops and whether the phone is hot |

`CAMERA_UNAVAILABLE` specifically would mean CameraX did not let go of the camera — that
would be a bug on our side, not the phone's. See `docs/05-arcore-camera-ownership.md`.

Pose is written to logcat once per 30 frames:

```
P1Check: POSE x=+0.014 y=-0.031 z=+1.902 yaw=+2.4 pitch=-4.1 roll=+0.2 fps=29.6
```

Capturing ~20 seconds of that while walking a known 2 m line is the most useful single
artefact you can send back.
