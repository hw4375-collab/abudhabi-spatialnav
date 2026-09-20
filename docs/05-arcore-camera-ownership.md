# Decision: ARCore owns the camera, CameraX does not run beside it

## The conflict

The Android camera is an exclusive resource. CameraX opens it through Camera2 and holds it
for as long as a use case is bound; an ARCore `Session` opens it for itself when
`session.resume()` is called. Whichever asks second loses:

* CameraX bound first → `session.resume()` throws `CameraNotAvailableException`.
* ARCore resumed first → `bindToLifecycle` fails to open the device.

There is no version of "keep the existing `ImageAnalysis` pipeline running while ARCore
tracks". ARCore's own `SharedCamera` feature (`Session.Feature.SHARED_CAMERA`) does allow an
app to share the camera with Camera2, but it forces the session into a constrained
configuration, is documented as incompatible with several other features, and is a known
source of device-specific breakage. It is not worth the risk for a hackathon.

## Decision

1. **ARCore is the single camera owner during navigation.** The AR session is the one that
   must never stutter — losing tracking loses the user's position, which is the whole product.
2. **The two screens are separate activities.** `HardwareCheckActivity` (P0, CameraX) releases its
   binding in `onPause`, before `ArDiagnosticActivity` reaches `onResume`. Lifecycle-bound
   CameraX would mostly do this on its own; `cameraProvider.unbindAll()` is called explicitly
   so the hand-off does not depend on timing.
3. **Future perception reads frames from ARCore, not from CameraX.** ARCore already exposes
   everything L3 needs from the same frame that produced the pose, which is strictly better
   than a second pipeline because the image and the pose are then guaranteed to be
   synchronised:
   * `frame.acquireCameraImage()` — CPU `YUV_420_888` image, for object detection later.
   * `frame.acquireDepthImage16Bits()` / `acquireRawDepthImage16Bits()` — the depth map that
     L3 obstacle detection will actually use.
   * `session.getSupportedCameraConfigs(CameraConfigFilter(session))` — to pick the CPU image
     resolution, since the default is chosen for tracking, not for inference.
   Every one of these must be `close()`d in the same frame or the session stalls.

## Consequence for P0

The CameraX screen stays exactly as it is and keeps its own PASS/FAIL checks, because it is
still the cheapest way to prove the camera, the permission and the frame pipeline work on a
new phone. It simply stops being the path that feeds perception. Once P1 passes on the
Xiaomi 14, the `ImageAnalysis` use case has no remaining role and can be deleted.
