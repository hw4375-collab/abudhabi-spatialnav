# P3 device QA — live guidance and the demo flow

The full product flow now runs in the app: **Map → Add Destination → Navigate → Voice
Guidance → Arrive**.

## Build and install

```bash
git pull
cd android
./gradlew installDebug
```

Android Studio: open the **`android/`** directory (not the repo root) and Run ▶ `app`.

Logs:

```bash
adb logcat -c && adb logcat -s NavCheck:I P2Check:I RoomStore:I ManualOrigin:I
```

`NavCheck` is the navigation log, one line per second:

```
NavCheck: destination=Bathroom current=+0.42,+1.80 target=+2.13,+4.72 distance=3.38 headingError=+27.1 state=TURN_RIGHT
```

## Demo flow to rehearse

1. **Home** — "SpatialNav / Navigate spaces with confidence.", with `Navigate` and
   `Map a Room`, saved spaces below, and a small `Developer / Diagnostics` entry at the
   bottom (P0 hardware check and P1 ARCore diagnostic live there now).
2. **Map a Room** → name it → sweep the phone until `Spatial tracking ready` →
   **Set Room Origin** while standing on a spot you have marked with tape.
3. **Add Destination** → Bathroom / Door / Desk / Custom. Walk to the place first: the
   destination is saved where the phone is *now*.
4. **Where do you want to go?** — one big button per saved destination.
5. Tap `Bathroom` → the navigation screen: a large arrow, `GO FORWARD`, `2.4 m`,
   `to Bathroom`, `Path clear`, and a big `STOP`.
6. Walk. The arrow and the instruction follow the phone; speech only on changes.
7. Within 1 m the screen shows `You've arrived` and speaks
   "You have arrived at Bathroom."

Reopening the app: `Navigate` → pick the space → stand on the taped origin facing the same
way → **I am on the marker — Align** → destinations are back.

## Navigation rules

Everything is deterministic Kotlin (`NavigationEngine`), computed in the X/Z plane of the
room frame; the vertical axis is ignored so a destination marked at chest height does not
keep a residual distance.

| Condition | State | Screen | Speech |
|---|---|---|---|
| distance ≤ 1.0 m | `ARRIVED` | ✓ `You've arrived` | "You have arrived at X." (once) |
| heading error ≤ 20° | `GO_FORWARD` | ↑ `GO FORWARD` | "X is N meters ahead." |
| heading error > +20° | `TURN_RIGHT` | ↱ `TURN RIGHT` | "Turn right." |
| heading error < −20° | `TURN_LEFT` | ↰ `TURN LEFT` | "Turn left." |

Speech is throttled by `GuidanceAnnouncer`: it speaks when the instruction changes, when a
whole metre has been covered (and at least 2.5 s since the last utterance), and once on
arrival. No speech on every frame.

## PASS / FAIL

| # | Check | PASS |
|---|---|---|
| 1 | Home screen | no technical data on the primary screen; both large buttons work |
| 2 | Mapping | origin set, destinations added, listed as big buttons |
| 3 | Start navigation | arrow + instruction + distance appear, TTS says "Navigating to X." |
| 4 | Turning | rotating on the spot flips the instruction between TURN_LEFT / GO_FORWARD / TURN_RIGHT in the correct direction |
| 5 | Walking | the distance counts down smoothly and matches reality within ~1 m |
| 6 | Arrival | `You've arrived` and the arrival phrase, spoken once |
| 7 | No audio spam | speech is occasional, never per frame |
| 8 | STOP | returns to the destination picker, speech stops |
| 9 | Stability | no crash, fps stays ≈ 60 |

Check 4 is the one to demo: it is the fastest way to show the guidance is real and not
scripted.

## Known limitations (do not hide these in the demo)

* The manual origin is a **calibration** step, not persistent localization. Measured
  Bathroom error on the Xiaomi 14 was 0.69 m on the first restart and 3.65 m on the second.
  Re-align before the demo run rather than trusting an old alignment.
* No obstacle avoidance and no path planning: guidance points straight at the destination,
  so the user must be able to walk roughly in a straight line towards it.
* Accuracy degrades with ARCore drift over long walks; a fresh alignment resets it.
