# SpatialNav

SpatialNav is an accessibility-focused indoor navigation app that remembers meaningful
places in a room and guides users to them using real-time spatial tracking and voice
instructions.

**Map → Save Destination → Navigate → Voice Guidance**

1. **Map a Room** — hold the phone up until tracking stabilizes, then set the room origin.
2. **Add Destination** — walk to the bathroom, the door, the desk; each is saved in the
   room's own coordinate frame.
3. **Navigate** — reopen the space later, pick a destination, and follow the large arrow.
4. **Voice guidance** — "Turn left.", "Bathroom is 3 meters ahead.", "You have arrived at
   Bathroom." Speech happens on instruction changes and distance milestones only.
5. **Obstacles** — ARCore depth watches the space straight ahead and overrides the
   guidance when it is blocked: "Obstacle ahead. Move right." This is reactive avoidance,
   not path planning (`docs/10-p4-obstacle-qa.md`).

You can also just ask: typing *"Take me to the restroom"* uses **OpenAI — natural-language
destination understanding** to pick `Bathroom` out of the destinations this room already
has, then hands over to the same deterministic navigation. The model never computes
coordinates, localization or routes. Setup: `docs/09-ai-destination-qa.md`.

Built with **Kotlin Multiplatform**. The spatial reasoning kernel lives in `shared/commonMain`
and runs on two platforms: the **Android** app (ARCore + TTS + OpenAI) and a **Desktop/JVM**
**SpatialNav Simulator** (Compose Multiplatform). See
[Kotlin Multiplatform architecture](#kotlin-multiplatform-architecture).

Current limitation: the demo build uses a **manual origin** as the persistent spatial
reference — the user physically returns to a marked spot to re-align. It is a calibration
mechanism, not production-grade persistent localization; on a second restart the measured
error on a Xiaomi 14 was 3.65 m. Cloud Anchors are implemented behind the same interface and
only need an API key (see `docs/07-p2-device-test.md`).

## Repository layout

```
Hackson/
├── shared/         # Kotlin Multiplatform spatial core (commonMain + commonTest)
├── android/        # Kotlin Android app (Gradle root; ARCore, TTS, OpenAI)
├── desktop/        # Compose Desktop "SpatialNav Simulator" (JVM)
├── backend/        # Optional Python service (offline reconstruction / heavy CV)
├── docs/           # Engineering assessment, architecture, decisions
├── scripts/        # Environment + build helper scripts
└── references/     # Notes on external projects (no vendored large repos)
```

The Gradle build root is `android/` (so "open `android/` in Android Studio" still holds);
`settings.gradle.kts` there includes the sibling `:shared` and `:desktop` modules.

## Kotlin Multiplatform architecture

One implementation of the spatial core, two apps. Desktop is **not** a duplicate
implementation — both platforms depend on the same `:shared` module.

```
                    shared/src/commonMain
     NavigationEngine · DepthClearanceAnalyzer · NavigationFrame
     GuidanceAnnouncer · TrackingStabilizer · RoomMap/Destination/Vec3
                              |
            +-----------------+-----------------+
            |                                   |
     Android app (:app)                  Desktop app (:desktop)
     ARCore pose → NavigationFrame       simulated pose → NavigationFrame
     ARCore depth → DepthSampler →       simulated clearances →
       DepthClearanceAnalyzer              DepthClearanceAnalyzer
     Android TTS, OpenAI resolver,       Compose Desktop UI
     ARCore session, permissions, UI
```

**Shared (`shared/src/commonMain/kotlin/com/hackson/spatialnav/`)**

| File | Role |
| --- | --- |
| `navigation/NavigationEngine.kt` | distance / bearing / heading error → `GO_FORWARD`, `TURN_LEFT`, `TURN_RIGHT`, `ARRIVED` |
| `navigation/NavigationFrame.kt` | world pose → room-local pose (quaternion math) |
| `navigation/GuidanceAnnouncer.kt` | when to speak (instruction change, milestone, arrive-once) |
| `perception/DepthClearanceAnalyzer.kt` | left/center/right clearance → `CLEAR`, `MOVE_LEFT`, `MOVE_RIGHT`, `STOP`, `UNKNOWN`, plus hysteresis |
| `ar/TrackingStabilizer.kt` | `SEARCHING → STABILIZING → READY` |
| `model/RoomMap.kt` | `RoomMap`, `Destination`, `Waypoint`, `Edge`, `Vec3`, spatial reference record |
| `model/Clock.kt` | the only `expect`/`actual` in the module (`nowEpochMs`) |

Shared tests live in `shared/src/commonTest/` (34 tests) and run on **both** targets:
`NavigationEngineTest` 8, `DepthClearanceAnalyzerTest` 9, `NavigationFrameTest` 7,
`GuidanceAnnouncerTest` 5, `TrackingStabilizerTest` 5.

**Android-only** (`android/app/`): ARCore session/frame/pose, `perception/DepthSampler`
(depth image → clearances), Android TTS, OpenAI destination resolver, activities,
permissions, JSON persistence, UI. Its Android-only tests (16) stay there.

**Desktop-only** (`desktop/`): `Simulation.kt` produces a simulated pose and simulated
depth clearances and feeds them to the shared engine; `Main.kt` is the Compose UI. It
contains no navigation or obstacle logic of its own.

## Run

### Run Android

Open the **`android/`** directory in Android Studio and run the `app` configuration on an
ARCore-supported device. Or:

```bash
cd android && ./gradlew :app:assembleDebug
```

### Run Desktop

```bash
cd android && ./gradlew :desktop:run
```

A `SpatialNav Simulator` window opens: Bathroom 6 m ahead, `GO FORWARD`. `Move Forward`
shrinks the distance until the shared engine reports `ARRIVED`; `Place Obstacle` injects
clearances that the shared `DepthClearanceAnalyzer` turns into `MOVE LEFT` / `MOVE RIGHT` /
`STOP`.

## Build

`android/local.properties` is gitignored. Create it with your own SDK location, or export
`ANDROID_HOME`:

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties   # macOS default: ~/Library/Android/sdk
./gradlew :app:assembleDebug
```

Cloud Anchors need an ARCore API key, which is a personal credential and is never
committed. Add `arcore.apiKey=...` to `android/local.properties` (or export
`ARCORE_API_KEY`) to enable them; without it the app falls back to the manual-origin
spatial reference. See `docs/07-p2-device-test.md`.

Note: `settings.gradle.kts` lists the Google Maven Central mirror ahead of `mavenCentral()`
because `repo.maven.apache.org` rate-limits (HTTP 429) the CI machine. It is harmless
elsewhere.

## Architecture (five layers, each answering one question)

| Layer | Question | Today's implementation |
| --- | --- | --- |
| L1 Localization | Where am I? | ARCore VIO + Cloud Anchors |
| L2 RoomMap | Where can I go? | our own JSON model: spatial reference, destinations, waypoints, edges |
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
* `docs/07-p2-device-test.md` — persistent room + destinations, and the ARCore API key setup
* `docs/08-p3-demo-qa.md` — the demo flow, navigation rules and device QA checklist
* `docs/09-ai-destination-qa.md` — natural-language destination resolution and its API key
* `docs/10-p4-obstacle-qa.md` — reactive depth obstacle avoidance: thresholds and QA
