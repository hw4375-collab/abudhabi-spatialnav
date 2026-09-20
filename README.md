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

Built with **Kotlin** in **Android Studio**, on **ARCore** (motion tracking and a persistent
spatial reference) and **Android TextToSpeech**. Open the **`android/`** directory in
Android Studio and run the `app` configuration on a connected device.

Current limitation: the demo build uses a **manual origin** as the persistent spatial
reference — the user physically returns to a marked spot to re-align. It is a calibration
mechanism, not production-grade persistent localization; on a second restart the measured
error on a Xiaomi 14 was 3.65 m. Cloud Anchors are implemented behind the same interface and
only need an API key (see `docs/07-p2-device-test.md`).

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
