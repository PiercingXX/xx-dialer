# XX-Phone

The phone app that knows who is allowed to ring.

A default dialer for GrapheneOS with a ring-policy engine: spam never rings,
starred contacts always ring, and everyone else rings only when their window
says so — unknown numbers on their own ringtone, 9 to 5. All screening is
local. The app declares no `INTERNET` permission, and that is checkable.

**Status:** specification → implemented at the code level. The pure-JVM
policy core (`core/`, 66 tests), the FactStore/telecom/ring layers, all
screens, and the WS0 probe APK exist in-tree; ~220 JVM tests green; no
`INTERNET` permission in either built APK (`aapt2 dump permissions`, checked).
Device-gated gates remain OPEN: the four WS0 [VERIFY] answers, the daily-driver
gate (WS7), and the instrumented suites wait until the `caiman` runs
[PROBE.md](PROBE.md) and installs the app. Nothing on this list is
device-proven yet.
**Spec:** [design.md](design.md) — the full design.
**Build plan:** [todo.md](todo.md) — workstreams, gates, and the order to do them in.
**Screens:** [design/xx-phone-screens.html](design/xx-phone-screens.html) — the mockup.
**Target:** Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37.

---

## The ring policy

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

Outside a window a call is silenced, not rejected: it lands in Recents with
the rule that fired, and you can still grab it if you're looking at the phone.
A repeat caller (twice in 15 minutes) pierces any silence. **Expecting a
call** rings everything for a bounded 2 hours, then expires on its own.
Blocking only ever comes from an explicit signal — your blocklist, your
pattern rules, a failed STIR/SHAKEN attestation. A heuristic can silence a
call; it can never eat one.

And for the first week it silences nothing at all: **observe mode** rings
every call normally while the log records what it would have done. You flip
enforcement on after the log has proven itself; it is never flipped for you.

## Why a whole dialer

Because a call-screening app cannot change the ringtone — the system plays it.
The only app allowed to own the ringer is the default dialer. So XX-Phone is
the default dialer: keypad, recents, contacts, in-call screen, and the policy
engine that is the actual point.

## Family

Brand, tokens, and type come from
[piercingxx-branding](https://github.com/PiercingXX/piercingxx-branding).
AMOLED black, Signal white, Space Mono / JetBrains Mono. Same stack and
conventions as [Nope-Mode](https://github.com/PiercingXX/Nope-Mode) and the
Launcher.

Free and ad-free. Collects no personal data. Nothing this app sees ever
leaves your device.

## Build

Prerequisites: JDK 17; Android SDK with `platforms;android-35` at minimum
(the build pins `compileSdk 35` — see the note in `build.gradle.kts`;
`platforms;android-37.1` is installed locally but unused by the build).

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The WS0 probe builds separately: `./gradlew :probe:assembleDebug`, then
follow [PROBE.md](PROBE.md). `verifyNoInternet` runs automatically on every
debug build (`:app` and `:probe`): it dumps the APK's declared permissions via
`aapt2 dump permissions` and fails the build if `android.permission.INTERNET`
appears — the design.md R8/D8 claim, enforced by the build.

First run after install: the Setup flow claims `ROLE_DIALER` +
`ROLE_CALL_SCREENING`, with the Restricted-Settings walk-through when the
role request comes back not-held (design §4.1). The app then starts in
**observe mode** for a week — every call rings normally while the log
records what enforcement would have done. Enforcement is offered, never
flipped automatically (design §6, D13).
