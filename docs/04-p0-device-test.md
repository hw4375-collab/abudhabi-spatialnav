# P0 — Real-Device Hardware Validation

Goal: prove the phone can do everything the navigation stack depends on, before any
navigation logic exists. Nothing in this app is mocked; every line below reflects a real
Android API result.

## Run it (shortest path, no Android Studio needed)

```bash
git clone https://github.com/hw4375-collab/abudhabi-spatialnav.git
cd abudhabi-spatialnav/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS default
adb devices                                                   # phone must show as "device"
./gradlew installDebug
adb shell am start -n com.hackson.spatialnav/.MainActivity
```

Already cloned: `git pull && cd android && ./gradlew installDebug`.

With Android Studio instead: open the `android/` directory (**not** the repo root), let it
sync, press Run.

## Watch the results

Everything is on screen. For a copyable log:

```bash
adb logcat -c && adb logcat -s P0Check:I
```

## PASS / FAIL criteria

The screen shows one line per check, plus the device model and a live frame counter:

```
Google Pixel 7 / Android 14
[PASS   ] CAMERA_PERMISSION — granted by user
[PASS   ] CAMERA_PREVIEW — surface streaming
[PASS   ] IMAGE_ANALYSIS — received 30 frames at 640x480
[PASS   ] TTS_INIT — engine ready, en-US available
[PASS   ] TTS_SPEAK — utterance completed — did you hear it?
frames=214 fps=29.8
```

| Check | PASS means | FAIL / stuck means |
| --- | --- | --- |
| `CAMERA_PERMISSION` | you tapped Allow on the permission dialog | you tapped Deny — uninstall and retry, or grant it in Settings -> Apps -> SpatialNav -> Permissions |
| `CAMERA_PREVIEW` | the live camera image fills the screen and the line says `surface streaming` | stays PENDING with a black screen, or says `bind failed: ...` — report the message |
| `IMAGE_ANALYSIS` | 30 frames arrived, and `frames=` keeps climbing with `fps` around 15–30 | stays PENDING, or `frames=` freezes while the preview still moves |
| `TTS_INIT` | the "Test TTS" button became tappable | button stays greyed out; the line says why (usually no en-US voice installed — Settings -> Accessibility -> Text-to-speech -> install voice data) |
| `TTS_SPEAK` | tap "Test TTS": you **hear** "Walk forward for two meters, then turn slightly left." and the line turns PASS | no sound: check media volume and silent mode first. The line can read PASS while you hear nothing if the volume is down — **trust your ears, not the line** |

**P0 passes only when all five lines read PASS and you actually heard the sentence.**

Also worth noting for later: keep the app open for ~2 minutes and watch `fps`. If it drops
below ~10 or the phone gets hot, tell us — that constrains what we can run per frame in P3/P4.

## If something fails

Send back:
1. the full on-screen text (a photo is fine),
2. `adb logcat -d -s P0Check:I > p0.log` and the file,
3. the exact phone model and Android version.
