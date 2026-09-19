# XX-Dialer

> The phone app that knows who is allowed to ring.

Spam never rings. Starred contacts always ring. Everyone else rings only inside
their window. It had to be a whole dialer — a screening app cannot change the
ringtone. Keypad, recents, contacts, in-call: they come along for the ride.

All screening is local. Offline by default. Visual voicemail is the one
exception, off until you flip it, then IMAP to your carrier mailbox.

<img src="docs/images/screenshot.png" width="270" alt="XX-Dialer Recents screen on a Pixel 6, AMOLED Night">

## Ring policy

First match wins:

| Caller | Rings |
|---|---|
| Emergency | always |
| Blocked or spam | never — rejected before it rings |
| ★ Starred | always |
| Business contact (label, not starred) | 09:00 – 19:00 |
| Saved contact | always |
| Unknown you dialed in the last 48 h | always |
| Unknown | 09:00 – 17:00 |

Outside a window the call is silenced, not rejected. It lands in Recents. A
repeat caller — twice in fifteen minutes — pierces any silence. **Expecting a
call** rings everything for two hours, then expires. A heuristic can silence.
It can never eat one.

First week is **observe mode**: everything rings, the log records what it
would have done. You flip enforcement on. It is never flipped for you.

**Installed. Holds `ROLE_DIALER` and `ROLE_CALL_SCREENING`. Not proven against
a live SIM.**

```
package: com.piercingxx.xxdialer    minSdk 31
```

Uninstall the old `com.piercingxx.xxphone` first if it is still on the device.
Roles do not follow a rename.

## Build

```sh
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
./gradlew :core:test :app:testDebugUnitTest
```

[design.md](design.md) · [todo.md](todo.md)

## License

All rights reserved. See [LICENSE](LICENSE). Nothing this app sees leaves the
device.
