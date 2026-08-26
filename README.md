# XX-Dialer
> The phone app that knows who is allowed to ring.

A default dialer for GrapheneOS with a ring-policy engine: spam never rings,
starred contacts always ring, everyone else rings only inside their window. It
had to be a whole dialer because a screening app cannot change the ringtone —
the system plays it, and only the default dialer owns the ringer. So the keypad,
recents, contacts and in-call screen come along for the ride.

All screening is local. No `INTERNET` permission, and `verifyNoInternet` fails
the build if one ever appears — enforced, not asserted in prose.

<img src="docs/images/screenshot.png" width="270" alt="XX-Dialer Recents screen on a Pixel 6, AMOLED Night">

## The ring policy 📞

First match wins, top to bottom:

| Caller | Rings | Tone |
|---|---|---|
| Emergency / emergency callback | always | default |
| Blocked or spam | never — rejected before the phone rings | — |
| ★ Starred contact | always | default |
| Business contact (label, not starred) | 09:00 – 19:00 | default |
| Saved contact | always | default |
| Unknown number you dialed in the last 48 h | always | unknown tone |
| Unknown number | 09:00 – 17:00 | **unknown tone** |

Outside a window a call is silenced, not rejected: it lands in Recents with the
rule that fired, and you can still grab it if you happen to be looking. A repeat
caller — twice in fifteen minutes — pierces any silence. **Expecting a call**
rings everything for two hours, then expires on its own. Blocking only ever
comes from an explicit signal: your blocklist, your pattern rules, a failed
STIR/SHAKEN attestation. A heuristic can silence a call. It can never eat one.

For the first week it silences nothing at all. **Observe mode** rings every call
normally while the log records what it would have done. You flip enforcement on
once the log has earned it; it is never flipped for you.

## Status 🧪

Installed and holding both roles — `ROLE_DIALER` and `ROLE_CALL_SCREENING` on a
Pixel 6 running GrapheneOS. 350 JVM tests green, 72 of them in `:core`.

**Not proven against a live SIM.** No real call has been placed, answered, or
screened through a carrier. The ring policy is proven by those truth-table tests
and nothing else. The probe answers in [PROBE.md](PROBE.md), the daily-driver
gate, and the instrumented suites are all still open.

## Build 🛠️

JDK 21, JVM 17 bytecode. AGP 8.9.1, Kotlin 2.1.20, Gradle 8.11.1.
`compileSdk`/`targetSdk` 35, `minSdk` 31.

```
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
./gradlew :core:test :app:testDebugUnitTest
```

The application id is `com.piercingxx.xxdialer`; it was `com.piercingxx.xxphone`
before the rename, and Android keys everything off the package. Uninstall the
old one first, then re-grant both roles through Setup — roles, the Room
database, the channels and the theme prefs do not follow a rename.

The probe builds separately: `./gradlew :probe:assembleDebug`, then
[PROBE.md](PROBE.md).

## Docs 📚

- [design.md](design.md) — policy, windows, channels, failure modes, theme sync
- [todo.md](todo.md) — workstreams, gates, and the order to do them in
- [design/xx-dialer-screens.html](design/xx-dialer-screens.html) — the mockup

## Family

Brand and type from
[piercingxx-branding](https://github.com/PiercingXX/piercingxx-branding): AMOLED
black, Signal white, Space Mono / JetBrains Mono. Theme follows XX-Launcher's
broadcast across all nine apps. Same stack as
[Nope-Mode](https://github.com/PiercingXX/Nope-Mode), TxxT, and the Launcher.

Free and ad-free. Collects no personal data. Nothing this app sees ever leaves
your device.

## Licence

Copyright (c) 2026 PiercingXX. All rights reserved. See [LICENSE](LICENSE).
