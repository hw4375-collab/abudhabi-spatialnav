# Hackson — Indoor Spatial Navigation for Blind Users

Kotlin Android app that maps an indoor space once, then relocalizes the user with the live
camera and guides them to a semantic destination with voice instructions and live obstacle
warnings.

## Repository layout

```
Hackson/
├── android/        # Kotlin Android app (Gradle, CameraX + TTS verified)
├── backend/        # Optional Python service (offline reconstruction / heavy CV)
├── docs/           # Engineering assessment, architecture, decisions
├── scripts/        # Environment + build helper scripts
└── references/     # Notes on external projects (no vendored large repos)
```

`localization/`, `perception/`, `navigation/` and `reconstruction/` are NOT top-level
directories: for the MVP all of them run on-device, so they live as Kotlin packages under
`android/app/src/main/java/com/hackson/spatialnav/`. Anything that later has to move
server-side gets its own module under `backend/`.

## Build

`android/local.properties` is gitignored. Create it with your own SDK location, or export
`ANDROID_HOME`:

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # macOS default: ~/Library/Android/sdk
./gradlew :app:assembleDebug
```

Note: `settings.gradle.kts` lists the Google Maven Central mirror ahead of `mavenCentral()`
because `repo.maven.apache.org` rate-limits (HTTP 429) the CI machine. It is harmless
elsewhere.

## Architecture (five layers, each answering one question)

| Layer | Question | Today's implementation |
| --- | --- | --- |
| L1 Localization | Where am I? | ARCore VIO + Cloud Anchors |
| L2 RoomMap | Where can I go? | our own JSON model: anchors, destinations, waypoints, edges, occupancy |
| L3 Perception | What is blocking me now? | ARCore Raw Depth |
| L4 Navigation | How do I get there? | A\* over the RoomMap graph |
| L5 Voice | What should I do next? | Android TextToSpeech |

Cloud Anchors are a persistent coordinate reference, **not** the room map. See
`docs/01-architecture-layers.md`.

3D Gaussian Splatting is deliberately off the critical path — it is a future L2 enrichment
and visualization layer, not a navigation dependency.

## Docs

* `docs/00-engineering-assessment.md` — environment, decisions, risks, MVP order
* `docs/01-architecture-layers.md` — the five layers and their boundaries
* `docs/02-hardware-requirements.md` — what the demo phone must support
* `docs/03-device-compatibility-checklist.md` — 5-minute check for a candidate phone
* `docs/04-p0-device-test.md` — how to run the P0 hardware validation on the phone
* `docs/05-arcore-camera-ownership.md` — why ARCore, not CameraX, owns the camera
* `docs/06-p1-device-test.md` — TTS fix plus the ARCore capability / live pose test
