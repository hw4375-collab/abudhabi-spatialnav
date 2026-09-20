# AI destination understanding — configuration and QA

One thin AI capability: the user types a sentence, OpenAI picks **one of the destinations
already saved in this room**, and the existing deterministic navigation starts. The model
never computes coordinates, distance, heading or instructions.

```
user language → OpenAI destination resolver → saved RoomMap destination
              → NavigationEngine → ARCore → TTS   (all unchanged)
```

## Key configuration (hackathon demo only)

Add one line to `android/local.properties` (git-ignored, never commit it):

```
openai.apiKey=sk-REPLACE_ME
```

`OPENAI_API_KEY` in the environment works too. Gradle injects it into
`BuildConfig.OPENAI_API_KEY`; there is no key in Kotlin, XML, README, logs or git.

**This is a demo configuration, not a production one.** Anything compiled into an APK is
extractable, so production requires `Android → backend proxy → OpenAI`, with the key held
server-side. That proxy is intentionally not built today.

## Build and run

```bash
git pull
cd android
./gradlew installDebug
adb logcat -c && adb logcat -s AIResolver:I NavCheck:I P2Check:I
```

## QA

1. Open a space with `Bathroom` (and ideally `Door`) saved, complete alignment.
2. Type `Take me to the restroom` → `Find Destination`. Expect
   `Understanding destination…`, then the navigation screen for **Bathroom** and the
   spoken "Navigating to Bathroom." Log: `AIResolver: matched=Bathroom`.
3. `I need the bathroom`, `take me to the door` → same behaviour for the matching
   destination.
4. **Unknown:** `Take me to the kitchen` (with no Kitchen saved). Expect
   `I couldn't match that destination. Choose one below.`, **no navigation**, and
   `AIResolver: no_match`.
5. **Offline fallback:** enable airplane mode and ask again. Expect the same message with a
   reason appended, no crash, and `AIResolver: network_error`. The Bathroom / Door / Desk
   buttons must still start navigation normally.
6. **No key:** a build without `openai.apiKey` shows the same fallback message immediately;
   everything else behaves exactly as in `abb7cfb`.

Model: `gpt-4o-mini` via the Chat Completions API, `temperature=0`,
`response_format=json_object`, 8 s timeout, run off the UI thread. The reply must be
`{"destination": "<exact saved name>"}` or `{"destination": null}`, and the name is matched
against the local RoomMap before anything happens — an invented destination is rejected.

## Known limitations

* One request per tap, no retry, no conversation history, no speech input (typed only).
* Matching is exact-name against the room after the model answers, so a destination the user
  never saved can never be navigated to — by design.
* A slow network delays the answer up to 8 s; the buttons remain the fast path.
