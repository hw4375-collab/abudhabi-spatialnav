# P4 — reactive obstacle avoidance QA

## What it is (and is not)

```
destination guidance:  pose + saved destination → NavigationEngine → GO_FORWARD / TURN_*
safety layer:          ARCore depth → clearance left/center/right → CLEAR / MOVE_LEFT / MOVE_RIGHT / STOP
final instruction:     safety layer overrides destination guidance whenever it sees an obstacle
```

This is **reactive** avoidance. It is not A\*, not a global occupancy map, not path planning:
it steers the user around what is in front of them right now, and the unchanged
`NavigationEngine` re-aims at the destination as soon as the way is clear. It cannot
guarantee a route around an arbitrary obstacle. OpenAI is not involved in any of this — no
image, no depth value and no safety question is ever sent to the model.

## How depth is read

From the **existing** ARCore session and frame: `frame.acquireDepthImage16Bits()` with
`DepthMode.AUTOMATIC` (already verified SUPPORTED on the Xiaomi 14). No second camera
pipeline and no CameraX. Raw depth was not used: it is sparser, and with a 1.2 m threshold
the smoothed depth map gives the steadier demo.

Processing (`DepthSampler`, ~6 reads per second, not every frame):

* a horizontal band across the middle of the image is split into **left / center / right**;
* zero / out-of-range samples are discarded, every 3rd pixel is read;
* each region's clearance is the **20th percentile** of its valid samples — conservative
  (an obstacle is what sticks out) but immune to a few noisy pixels;
* a region with fewer than 24 valid samples reports "unknown", never "clear".

## Thresholds

All in one place: `DepthClearanceAnalyzer.Config`.

| Constant | Default | Meaning |
|---|---|---|
| `blockedMeters` | 1.2 m | ahead is blocked below this |
| `clearHysteresisMeters` | 0.3 m | must clear 1.5 m before returning to CLEAR |
| `sideUsableMeters` | 1.2 m | a side is worth stepping into above this |
| `sideSwitchMarginMeters` | 0.3 m | how much better the other side must be before switching |
| `missingReadingGrace` | 10 reads | how long a depth dropout is ridden out |

## States, UI and voice

| State | Screen | Voice |
|---|---|---|
| `CLEAR` | normal guidance, `Path clear` in green | normal destination guidance |
| `MOVE_LEFT` | ← `MOVE LEFT`, `OBSTACLE AHEAD` in red | "Obstacle ahead. Move left." |
| `MOVE_RIGHT` | → `MOVE RIGHT`, `OBSTACLE AHEAD` in red | "Obstacle ahead. Move right." |
| `STOP` | ✕ `STOP`, `OBSTACLE AHEAD` in red | "Stop. Obstacle ahead." |
| `UNKNOWN` | normal guidance | normal guidance |

Obstacle speech happens on **state change only**, with a 3 s cooldown against repeats, and
it silences the destination guidance while it is active; returning to `CLEAR` says
"Path clear." and hands the voice back. The destination and the remaining distance stay on
screen throughout.

Anti-oscillation: a direction once chosen is kept until the other side is clearly (0.3 m)
better, and clearing needs 0.3 m more room than blocking did — so the instruction does not
flip while the user edges past a chair.

**Depth unavailable:** `NotYetAvailableException` is caught and treated as a missing
reading. The last verdict is held for ~10 reads (≈1.5 s) and then the state becomes
`UNKNOWN`, which shows normal destination guidance without claiming the path is clear. A
phone or build without depth support simply never runs the layer — navigation behaves
exactly as in `abb7cfb`. No depth failure can crash or block navigation.

## Device QA

```bash
git pull && cd android && ./gradlew installDebug
adb logcat -c && adb logcat -s ObstacleCheck:I NavCheck:I AIResolver:I P2Check:I
```

`ObstacleCheck: left=2.41 center=0.86 right=2.30 state=MOVE_RIGHT` — one line per state
change, plus one every few seconds.

1. Navigate to Bathroom down a clear route → `GO FORWARD`, `Path clear`.
2. Put a chair ~0.8–1.0 m directly ahead → red `OBSTACLE AHEAD` and `MOVE LEFT`/`MOVE RIGHT`
   towards the more open side, spoken once.
3. Step around the chair → `Path clear.` is spoken, then normal guidance resumes
   (`TURN LEFT` / `GO FORWARD` back towards the Bathroom).
4. Block both sides (e.g. a doorway with the chair in it) → `STOP`.
5. Remove the obstacle → recovery back to normal guidance.
6. Point the phone at a blank close wall / cover the camera briefly → no crash; the state
   may go `UNKNOWN`.
7. Walk the full flow to `ARRIVED` with the chair on the route.

**First thing to check:** in step 2, confirm that placing the chair on the *right* shrinks
`right=` in the log. The mapping from depth-image rows to the user's left/right depends on
the sensor orientation; if it is mirrored on this device, the fix is a one-line swap in
`DepthSampler.sample`.

## Known limitations

* Reactive only; a large obstacle can lead the user into a dead end, where the layer says
  `STOP` rather than routing around.
* Depth is trusted in a band ahead of the camera, so the phone must be held roughly upright
  and forward-facing; pointing it at the floor reads the floor as an obstacle.
* Nothing below the band (kerbs, a low bar) and nothing outside the camera's field of view
  is seen. This is not a white cane.
* Thresholds are tuned by eye, not measured; `DepthClearanceAnalyzer.Config` is the one
  place to adjust them.
