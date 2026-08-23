# XX-Phone — Build Plan

Spec: [design.md](design.md). Target: Pixel 9 Pro (`caiman`), GrapheneOS,
Android 17 / SDK 37.

**Status: nothing built.** The repo holds `design.md`, this file, and
`design/` (the screen mockup + research notes).

---

## Read this before starting

**1. WS0 is not optional.** The design leans on four platform behaviors that
are source-verified on AOSP but unproven on the GrapheneOS build in hand:
the Restricted Settings gate on `ROLE_DIALER` for sideloaded apps, channel
ringing with the `DEFAULT_RINGTONE_URI` indirection, Contact Scopes' effect
on `PhoneLookup` at call time, and what STIR statuses the real carrier
delivers. Nope-Mode cost one factory reset to learn its provisioning window
the hard way; the probe APK costs a day and is thrown away. Do it first.

**2. The failure direction is a law, not a preference.** Every failure —
timeout, stale mirror, empty Contact Scopes grant, crashed screener — must
resolve to *the phone rings*. Blocks come only from explicit signals (design
D5). If a change makes any failure path quieter instead of louder, it is
wrong regardless of how reasonable it looks.

**3. The tone mechanism is notification channels, and channels are
append-only.** A channel's sound is immutable after creation, and
delete-and-recreate with the same ID resurrects the old settings. Every
tone change mints a new versioned channel ID (`ring_unknown_v2`). The
`channel_registry` table exists for exactly this; there is no shortcut.

**4. Hold both roles.** Default dialer alone is not enough — if any other
app holds `ROLE_CALL_SCREENING`, XX-Phone's screener is silently never
invoked. Setup claims both and detects the conflict (design §4.1).

**5. The pure core carries the correctness burden.** `RingPolicy`, `Windows`,
and `E164` import nothing from `android.*` and are fully testable on the
JVM. WS2 finishes — the whole §6 truth table green — before any Android
code consumes a verdict. This is the same discipline as Nope-Mode WS3, for
the same reason: the part that must be right is the part that needs no
device.

**6. Observe mode is a gate, not a branch.** The verdict path runs
identically whether enforcing or observing; observe differs only at the
final gate (design §6, D13). If observe mode ever needs its own code path,
the design has been violated — a week of observation is only worth anything
because it exercises the real code. Dogfooding at the WS7 gate happens in
observe mode first.

### One decision still open

**O1: Views or Compose?** (design §18.) The spec assumes Views (family
default — Launcher, Nope-Mode); Vitals went Compose by operator ruling.
Blocks WS3's screen work, not WS0–2. Needs the operator's call; if Compose,
nothing else in the spec changes.

---

## Workstreams

| WS | Scope | Gate / exit criterion |
|---|---|---|
| 0 | **Probe APK** (throwaway, separate module): request both roles on-device; post a CallStyle notification on a channel with `IN_CALL_SERVICE_RINGING` set and confirm it rings; flip the system ringtone and confirm the indirection follows; enable Contact Scopes with a partial grant and observe `PhoneLookup`; log STIR status for a few real calls | Written answers to all four questions pasted into design §4 replacing the [VERIFY] flags |
| 1 | Skeleton — gradle, manifest (§13), packages (§14), vendored brand tokens, shipped fonts, launcher icon | Builds, installs, is visibly a PiercingXX app |
| 2 | `core/` — `RingPolicy` (repeat-pierce on every Silence, recent-outgoing 48 h, Expecting-a-call bypass, observe gate), `Windows`, `E164`, `Verdict`; the full §16 JVM suite | **Every row of the §6 table has a named passing test** |
| 3 | Dialer plumbing — Setup flow (role requests, Restricted Settings walk-through, held-state verification), `XxInCallService`, place/receive with a bare-bones screen, `placeCall()` everywhere | Can daily-drive basic calls, ugly |
| 4 | FactStore — contact mirror, observer, on-foreground sweep, Business tier storage, Scopes-degradation states | Empty-grant run classifies everyone unknown, no crash, no re-prompt |
| 5 | `XxCallScreeningService` + system blocklist writes + pattern rules with contacts exemption + STIR action | Spam block live end-to-end; screener answers well inside 5 s from the mirror |
| 6 | Ringer — `ChannelRegistry`, verdict → channel routing, silent channel, FSI/heads-up, `onSilenceRinger`, **observe gate wired last-in-chain** | **The headline works: unknown caller rings the unknown tone 9–17, silent with reason outside — and observe mode provably changes only the gate** |
| 7 | Incoming + in-call UI — CallStyle, answer-interaction setting, CNAP context line, in-call controls, call waiting (hold/swap), audio routing both paths | **Daily-driver gate: stock dialer replaced for real, in observe mode, including one deliberate call-waiting test** |
| 8 | Recents (chips, verdict rows, starred strip, row actions) + Keypad (T9 match) + People (tiers, contact sheet) | |
| 9 | Rules screen — enforcement switch + observe banner, precedence list, window editors, mask builder + neighbor-spoof preset, tone picker, silenced-notification policy, Test-a-number, full log with reasons + weekly tallies; missed-call notification ownership with inline actions (Call back · Ring next time · Block) and burst batching | R7 complete: every screened call explains itself |
| 10 | Gson backup/restore (tiers, patterns, settings, windows); **Expecting-a-call bypass + QS tile**; polish — hidden-caller policy, repeat toggle, offline blocklist import; upstream-setting detection warnings | v1 |

WS3–6 produce a functionally complete headless policy dialer with an ugly
screen. UI is deliberately late: the ring policy is the part that has to be
right, and it is the part that can be proven correct without a device.

## Standing rules

- A workstream with failing tests is not done.
- `aapt2 dump permissions` shows no `INTERNET` at every WS exit — it is the
  brand claim, and it rots exactly once.
- Instrumented tests for WS5–7 run on `caiman` itself; the emulator does not
  have GrapheneOS's Contact Scopes, its exposed block-unknown setting, or a
  real carrier's STIR behavior.
