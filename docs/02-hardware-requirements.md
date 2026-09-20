# Android Hardware Requirements

## Hard requirements (the app cannot run without these)

| Requirement | Why | How to check |
| --- | --- | --- |
| Android 8.0 (API 26) or newer | project `minSdk 26` | Settings -> About phone -> Android version |
| ARCore supported device | L1 pose + Cloud Anchors | device is listed at https://developers.google.com/ar/devices |
| "Google Play Services for AR" installable | ARCore runtime, updated via Play Store | Play Store -> search "Google Play Services for AR" |
| **Depth API support** | L3 obstacle detection | the device's row on https://developers.google.com/ar/devices says **"Supports Depth API"** |
| Rear camera | everything | — |
| Internet connectivity at demo time | Cloud Anchor host/resolve is a network call | — |
| Google account / Play Store present | to install Play Services for AR | — |
| USB debugging enabled | to sideload our APK | Settings -> Developer options -> USB debugging |

A ToF / LiDAR sensor is **not** required: ARCore computes depth from motion and only uses
a hardware depth sensor if one happens to be present.

## Strong preferences

* A phone released in the last ~4 years — VIO tracking quality and thermal headroom matter
  much more than raw benchmark scores.
* Not a device with an aggressive battery-saver / background-kill policy (some Xiaomi and
  Huawei builds) — it will kill a long camera session mid-demo.
* Huawei devices without Google Play Services are **unusable** for this project.

## Environment requirements (about the room, not the phone)

Cloud Anchor hosting and resolving fail on featureless surfaces. The demo room needs:
* textured, well-lit surfaces (posters, furniture, equipment) at the anchor locations;
* stable lighting — do not map in the morning and demo in the dark;
* no large mirrors or full-glass walls in the anchor's field of view.

## Not required today

* OAuth / service-account setup — a plain ARCore API key with 1-day Cloud Anchor TTL is
  sufficient for a same-day demo.
* GPU compute, ToF sensor, external sensors, Wi-Fi beacons, Bluetooth beacons.
