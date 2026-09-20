# 5-Minute Phone Compatibility Checklist

Run this on any candidate phone before committing to it as the demo device.
Every step must pass.

1. **Android version >= 8.0**
   Settings -> About phone -> Android version.

2. **Device is on the ARCore list AND its row says "Supports Depth API"**
   Open https://developers.google.com/ar/devices on the phone and search the exact model
   name. "Supported" alone is not enough — the comment column must read
   **"Supports Depth API"**. This is the step that eliminates most phones.

3. **"Google Play Services for AR" installs from the Play Store**
   Play Store -> search it -> it must show Install/Update, not "not compatible".

4. **ARCore actually tracks in the demo room**
   Install Google's "AR Core" / any AR measuring app (e.g. Google "Measure", or any Play
   Store AR app), walk 5 metres in the *actual demo room*, and confirm placed AR content
   stays glued to the floor. If content drifts or the app asks you to keep moving forever,
   the room's texture or lighting is the problem, not the phone.

5. **USB debugging works from a laptop**
   Settings -> About phone -> tap "Build number" 7 times -> Developer options ->
   USB debugging on. Then `adb devices` on the laptop must list the phone as `device`
   (not `unauthorized`).

6. **Battery / thermal sanity**
   Battery above 50%, battery saver OFF, and the phone is not already warm. A 20-minute
   continuous camera + depth session heats phones quickly.

If step 2 fails, stop — the phone cannot do live obstacle detection and is not usable for
the full demo.
