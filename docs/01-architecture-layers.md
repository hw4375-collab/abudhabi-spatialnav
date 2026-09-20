# Architecture — Five Layers

Each layer answers exactly one question. A layer may only depend on layers above it in this
list, never below. This is the contract that lets us swap an implementation (e.g. replace
Cloud Anchors with a 3DGS relocalizer later) without touching the rest of the system.

```
L1  ARCore / Cloud Anchors   ->  Where am I?
L2  RoomMap                  ->  Where can I go?
L3  Raw Depth / Perception   ->  What is blocking me right now?
L4  Navigation (A*)          ->  How do I get there?
L5  TTS                      ->  What should I do next?
```

## L1 — Localization: "Where am I?"

**Owns:** the persistent coordinate reference and the user's current 6-DoF pose in it.

* ARCore VIO supplies a continuous, metric-scale pose in an arbitrary session frame.
* Cloud Anchors supply the *bridge* between that arbitrary session frame and the room's
  persistent frame: resolving one anchor yields `T_room_session`, and every subsequent VIO
  pose can be transformed into room coordinates for free.
* Re-resolving further anchors while walking corrects VIO drift.

**Output:** `Pose(x, y, z, quaternion)` in the room frame, plus a tracking-quality flag.

**Explicitly NOT owned by L1:** Cloud Anchors are *not* the room map. An anchor is a
survey marker, not a floor plan. It says "this physical point is at these coordinates" and
nothing about what is walkable, what is a destination, or how rooms connect. Everything
semantic lives in L2.

**Swappable:** the layer is defined by the `LocalizationBackend` interface
(`resolve() -> T_room_session`, `currentPose() -> Pose?`). A future 3DGS relocalizer
implements the same interface.

## L2 — RoomMap: "Where can I go?"

**Owns:** the navigable model of the environment. Authored once during mapping, stored as
JSON, versioned, human-inspectable and hand-editable.

Contents:
* **anchor references** — Cloud Anchor ids and their poses in the room frame. L1 consumes
  these; L2 merely stores them.
* **destinations** — semantic named points ("Door", "Desk", "Bathroom") with a pose and an
  approach direction.
* **waypoints** — the nodes of the navigable graph.
* **edges** — which waypoints are directly walkable between, with a width hint.
* **static spatial information** — an optional 2D occupancy grid for walls and permanent
  furniture, used for clearance checks and for snapping an off-graph pose back onto the graph.

**Output:** graph + destinations + static occupancy. Pure data, no ARCore types, no Android
types — so it is unit-testable on the JVM and reusable by any future backend.

## L3 — Live Perception: "What is blocking me right now?"

**Owns:** everything the persistent map cannot know: people, a chair moved this morning, a
bag on the floor.

* ARCore Raw Depth image, sampled inside a forward corridor of the user's width.
* Produces a short-lived `ObstacleReport(distanceMeters, bearingDegrees, clearSide)`;
  nothing here is ever written into the RoomMap.

**Boundary:** L3 has no notion of destination or route. It only reports geometry in front
of the user. Deciding what to do about it is L4's job.

## L4 — Navigation: "How do I get there?"

**Owns:** turning (pose, destination, graph, obstacles) into a route and then into a single
next action.

* A\* over the RoomMap waypoint graph from the waypoint nearest the current pose to the
  destination.
* Recomputes when: the user leaves the route corridor, an L3 obstacle blocks the current
  edge, or relocalization jumps the pose.
* Emits a `NavigationCommand` (an enum + parameters: `FORWARD(2.0m)`, `TURN(-30deg)`,
  `OBSTACLE_AVOID(right)`, `ARRIVED`), not a sentence.

## L5 — Voice: "What should I do next?"

**Owns:** rendering `NavigationCommand` into a short spoken phrase and rate-limiting it.

* Android `TextToSpeech`.
* Rules: one instruction at a time, obstacle warnings pre-empt route instructions, do not
  repeat an unchanged instruction more often than every N seconds.
* Keeping phrasing in L5 alone means the whole navigation stack stays language-independent
  and testable without audio.

## Where 3D Gaussian Splatting fits (NOT today)

3DGS is a **future L2 enrichment and a visualization layer**, not a dependency of the
navigation loop:

* it could later supply the static occupancy of L2 automatically instead of hand-authoring;
* it could later provide an L1 backend (GSplatLoc-style relocalization) behind the same
  `LocalizationBackend` interface;
* it gives sighted helpers / developers a dense visual of the space.

None of those are on today's critical path, and the layering above is exactly what keeps
adding them later cheap.
