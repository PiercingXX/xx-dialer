# XX-Phone

The phone app that knows who is allowed to ring.

A default dialer for GrapheneOS with a ring-policy engine: spam never rings,
starred contacts always ring, and everyone else rings only when their window
says so — unknown numbers on their own ringtone, 9 to 5. All screening is
local. The app declares no `INTERNET` permission, and that is checkable.

**Status:** specification only. Nothing built.
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
