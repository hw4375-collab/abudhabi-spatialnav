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

```bash
cd android
ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :app:assembleDebug
```

## Status

See `docs/00-engineering-assessment.md`.
