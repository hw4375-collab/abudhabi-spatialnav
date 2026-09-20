# P2 device test — persistent room + semantic destinations

One product question: **can SpatialNav remember a physical room and its destinations across
app restarts?**

Everything below runs on the Xiaomi 14 (or any ARCore + Depth device that passed P1).

## 0. Optional: enable Cloud Anchors

Without an ARCore API key the app still works: it falls back to the **manual origin**
spatial reference, where the room origin is a physical spot you stand on. With a key it uses
**Cloud Anchors** and the room is found by simply looking around.

To enable Cloud Anchors:

1. Google Cloud console → create (or pick) a project → **APIs & Services → Enable APIs** →
   enable **ARCore API**.
2. **Credentials → Create credentials → API key**. Restrict it to the ARCore API.
3. Put it in `android/local.properties` (git-ignored, never committed):

   ```properties
   arcore.apiKey=AIza...
   ```

   or export `ARCORE_API_KEY` before building.
4. Rebuild. The room list screen shows which strategy is active.

API-key Cloud Anchors expire after **1 day** — long enough for the hackathon, and the room
list marks an expired room as `REFERENCE EXPIRED`.

## 1. Build and install

```bash
git pull
cd android
./gradlew installDebug
adb shell am start -n com.hackson.spatialnav/.ui.RoomListActivity
```

Android Studio: open the **`android/`** directory (not the repo root), then Run ▶ on `app`.

Logcat:

```bash
adb logcat -c && adb logcat -s P2Check:I RoomStore:I CloudAnchor:I ManualOrigin:I
```

## 2. The acceptance run

### Create

1. Launch the app → **Create room** → name it `Test Room`.
2. The AR screen opens. The status shows `Init: SEARCHING — Move phone slowly to scan
   surroundings...`. Walk a couple of steps and sweep the phone over textured surfaces.
3. Within a few seconds: `Init: READY — Spatial tracking ready.` and **Anchor room here**
   becomes tappable. *Stand where you want the room origin to be* and tap it.
   * Manual origin: type where you are standing ("on the door mat facing the window").
     **Mark the spot on the floor with tape** — you must return to it later.
   * Cloud Anchor: hosting takes a few seconds; watch for `HOST=SUCCESS id=...`.
4. Status becomes `Relocalization: SUCCESS` and the four destination buttons light up.

### Mark

5. Walk to the door, stand on it, tap **Door**. Repeat for **Bathroom** and **Desk**
   (`Custom…` names anything else). Each marking writes the room file immediately — there is
   no separate save button.
6. The destination list at the bottom shows each destination's room coordinates and its
   current distance from you. Standing on Bathroom should read `~0.0x m away`.
7. **Write down the physical spot of Bathroom** (tape it) — this is the reference for the
   error measurement.

### Restart

8. Close the app completely: recents → swipe away, or
   `adb shell am force-stop com.hackson.spatialnav`.
9. Relaunch → the room list shows `Test Room · 3 destinations`. Tap it.
10. Stabilize again (`Init: READY`), then:
    * Cloud Anchor: resolution starts automatically. Look around the place where the room
      was created. Watch for `RESOLVE=SUCCESS` / `RELOCALIZATION=SUCCESS`.
    * Manual origin: walk back to the taped origin, stand on it facing the same way, tap
      **I am on the marker — align**.
11. `Relocalization: SUCCESS`, destinations reappear with their saved coordinates.

### Measure

12. Walk to the physical Bathroom spot and stand on it. Read the `... m away` figure, and
    the `dist=` field in the log line:

    ```
    P2Check: POSE room=ab12cd34 x=+2.108 y=+0.031 z=+4.690 yaw=-36.8 nearest=Bathroom dist=0.042 fps=59.8
    ```

    **That distance is the end-to-end relocalization error** (reference error + VIO drift +
    how precisely you are standing on the tape).

## 3. PASS / FAIL

| # | Check | PASS |
|---|---|---|
| 1 | Stabilization | `SEARCHING → STABILIZING → READY`, never READY on the first frame |
| 2 | Room creation | `Relocalization: SUCCESS`, `ROOM_CREATED id=...` in logcat |
| 3 | Destination marking | three destinations listed with plausible metric coordinates |
| 4 | Persistence | after force-stop the room is still in the list with its destinations |
| 5 | Relocalization | reopening reaches `Relocalization: SUCCESS` |
| 6 | **Spatial recall** | standing on the physical Bathroom spot reads **< 1 m** |
| 7 | Stability | no crash; fps stays ≈ 60 |

Check 6 is the one that matters. Under ~0.5 m is good; P1 already measured ~0.5 m of
return-to-origin drift over a walk, so a sub-metre result here means the reference works.

## 4. If something fails

| Symptom | Cause / action |
|---|---|
| `Init` never reaches READY | tracking keeps dropping — more light, more texture, move slower |
| `unavailable: no ARCore API key` | expected without a key; the manual origin is used |
| `HOST=FAILED ERROR_NOT_AUTHORIZED` | key missing/wrong, or the ARCore API is not enabled |
| `RESOLVE=FAILED ...NO_MATCH` | phone does not recognise the place — stand where the room was created and sweep slowly |
| `RESOLVE=FAILED ...CLOUD_ID_NOT_FOUND` | the 1-day TTL expired; create the room again |
| Destination buttons stay grey | relocalization has not succeeded yet |
| Big error with manual origin | you are not standing exactly on the tape, or not facing the same way — heading error rotates the whole room |

## 5. What is verified where

* **JVM tests (21)** — room file format round trip and schema-version rejection, room store
  save/list/overwrite/corrupt-file handling, stabilization state machine, coordinate frame
  math.
* **Real device only** — Cloud Anchor hosting and resolving (needs a key, a network and a
  real room), actual relocalization error, and AR rendering.
