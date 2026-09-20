# Engineering Assessment — Day 0

## 1. Environment (verified on the dev machine)

| Item | Status |
| --- | --- |
| OS | Ubuntu 22.04, 8 vCPU, 31 GB RAM, 114 GB free |
| JDK | OpenJDK 17.0.19 (`javac` present) |
| Gradle | 8.7 (installed manually, wrapper generated in `android/`) |
| Android SDK | installed at `/home/ubuntu/android-sdk`: platform-tools, platforms;android-34, build-tools;34.0.0 |
| Kotlin | 1.9.24 via the Android Gradle plugin 8.5.2 |
| Python | 3.10.12, pip 22.0.2 |
| Git | 2.34.1 |
| GPU / CUDA | none (`nvidia-smi` absent) — CPU only |
| Android build | `:app:assembleDebug` BUILD SUCCESSFUL |

Known environment issue: `repo.maven.apache.org` answers HTTP 429 from this machine.
Workaround already applied in `android/settings.gradle.kts`: the Google mirror
`https://maven-central.storage-download.googleapis.com/maven2` is listed before
`mavenCentral()`.

## 2. Repository state

`/Desktop/AbuDhabi/Hackson` did not exist on this machine; the project was created at
`/home/ubuntu/Desktop/AbuDhabi/Hackson`. Nothing was overwritten.

## 3. Android baseline (real, built, not mocked)

`android/` contains a minimal Kotlin app that:

* declares and requests the `CAMERA` runtime permission;
* shows a live `PreviewView` via CameraX (`camera-core/camera2/lifecycle/view` 1.3.4);
* runs an `ImageAnalysis` analyzer (the hook where per-frame perception will live);
* speaks through Android `TextToSpeech`.

Not yet verified on real hardware — only the build is verified. Camera preview, TTS audio
and ARCore all need a physical phone.

## 4. Component decisions

### Localization (the core risk)

**Decision: ARCore on-device — motion tracking for continuous 6-DoF pose, Cloud Anchors
for relocalization into the saved room.**

Why not Gaussian-Splatting relocalization (SplatLoc / GSplatLoc / SplatHLoc):
they are research PyTorch pipelines requiring a CUDA GPU, per-scene training and
dataset preprocessing; this machine has no GPU, and nothing runs on Android. They solve
our problem in principle but cannot ship today.

ARCore gives us, natively on the phone:
* metric-scale 6-DoF pose at camera rate (VIO, no server, no GPU);
* persistence across sessions through Cloud Anchors (TTL up to 365 days with OAuth
  token auth; only 1 day with a plain API key);
* Depth API for obstacles.

Cost: a Google Cloud project with the ARCore API enabled, and Cloud Anchor resolution is
fragile on featureless walls. Mitigation: host several anchors around the room, and allow
a manual "I am at the entrance" fallback that seeds the pose from a known anchor.

### Persistent room map

**Decision: our own lightweight room model, not a dense reconstruction.**
A room is a JSON file: a set of Cloud Anchor ids with their poses, named destinations in
the room frame, and a 2D occupancy/walkable grid (or a small waypoint graph) authored
during mapping by walking the walkable area. Dense 3D reconstruction is not needed to
navigate, and it is what would eat the day.

### Path planning

**Decision: A\* (or a waypoint graph) on the 2D grid, in Kotlin.** Tens of lines,
no dependency, deterministic.

### Live obstacle detection

**Decision: ARCore Raw Depth API.** Works on Depth-API-capable phones without a ToF
sensor. Sample the depth image in a forward corridor and emit "obstacle N metres ahead".
Object detection (MediaPipe / ML Kit) is optional polish for naming the obstacle, not
required for avoidance.

### Voice

**Decision: Android `TextToSpeech` out, `SpeechRecognizer` in.** No LLM in the
positioning loop; an LLM may later parse free-form destination phrasing.

### Backend

**Decision: no Python backend for the MVP.** Everything above runs on-device, and a
server would add latency plus a network failure mode on hackathon Wi-Fi. `backend/` stays
as a placeholder for later offline reconstruction / map sharing.

## 5. USE / ADAPT / MOCK / DO NOT BUILD

**USE directly**
* ARCore SDK for Android (motion tracking, Cloud Anchors, Raw Depth)
* CameraX, Android TextToSpeech / SpeechRecognizer
* ARCore `hello_ar_kotlin` sample as the rendering/session base

**ADAPT**
* `shahtab123/indoor-nav-android` — Kotlin, Cloud-Anchor mapping + route graph +
  pathfinding, built on `hello_ar_kotlin`. Closest existing thing to our Phase A/B/C.
  Check its licence before copying code.
* `morhenny/ar-navigation` / `ar-localization` — Kotlin, Cloud Anchors + Geospatial,
  useful as reference for the host/resolve flow.

**MOCK temporarily (and labelled as mocked in the UI)**
* Room authoring UX: the first room JSON may be hand-written rather than captured.
* Voice input: start with buttons, add `SpeechRecognizer` after the loop closes.

**DO NOT BUILD TODAY**
* 3D Gaussian Splatting reconstruction or GS-based relocalization
* Custom SLAM, custom depth networks
* Any 3D visualization of the map
* Cross-building / multi-room support

## 6. Top three risks

1. **Cloud Anchor resolution fails in the demo room** (poor texture, lighting, quota).
   Mitigation: host 3–5 anchors, test in the actual demo room early, keep a manual
   pose-seed fallback.
2. **No physical Android device with ARCore + Depth support in the loop.** The emulator
   cannot meaningfully exercise ARCore or Depth. Mitigation: confirm a supported phone
   (Depth API list) before Priority 2 starts.
3. **Google Cloud setup friction** (project, ARCore API, OAuth keystore fingerprint for
   365-day TTL). Mitigation: do it first; fall back to API-key auth with 1-day TTL, which
   is sufficient for a same-day demo.

## 7. MVP order (vertical slices)

* **P0** Android app builds, camera preview, TTS. — done (build verified).
* **P1** ARCore session on a real device: pose displayed on screen, spoken through TTS.
* **P2** Mapping mode: host Cloud Anchors, define named destinations, save room JSON;
  reload and resolve.
* **P3** Destination selection + A\* path + turn-by-turn voice instructions.
* **P4** Raw Depth obstacle warnings merged into the instruction stream.
* **P5/P6** 3D visualization and UI polish — only after P1–P4 work end to end.
