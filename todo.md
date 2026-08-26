# XX-Dialer — Build Plan

Spec: [design.md](design.md). Target: Pixel 9 Pro (`caiman`), GrapheneOS,
Android 17 / SDK 37.

## Status ledger — 2026-08-25 (ship-readiness)

WS0–10 are **code-complete at the JVM**. A full-app review against design.md
found the policy core, observe gate (D13), R8 no-INTERNET, and screener
fail-open sound — and found the ringer, mirror, and in-call path are **not**
daily-driver safe. This file is the close-out list for those findings.

**Good to go as:** JVM-complete only. Observe-mode dogfood on a live SIM is
the next experiment after WS11. Enforce-mode daily drive is not.

Device gates remain OPEN: probe answers in [PROBE.md](PROBE.md), live-SIM
incoming/outgoing/waiting, instrumented suites (still `@Ignore`d).

---

## Read this before starting

**1. WS0 is not optional.** The design leans on four platform behaviors that
are source-verified on AOSP but unproven on the GrapheneOS build in hand:
the Restricted Settings gate on `ROLE_DIALER` for sideloaded apps, channel
ringing with the `DEFAULT_RINGTONE_URI` indirection, Contact Scopes' effect
on `PhoneLookup` at call time, and what STIR statuses the real carrier
delivers. Do it on `caiman` before flipping enforcement.

**2. The failure direction is a law, not a preference.** Every failure —
timeout, stale mirror, empty Contact Scopes grant, crashed screener **or
ringer** — must resolve to *the phone rings*. Blocks come only from explicit
signals (design D5). If a change makes any failure path quieter instead of
louder, it is wrong.

**3. Channels are append-only.** A channel's sound is immutable after
creation. Tone changes mint a new versioned id (`ring_unknown_v2`). Never
delete-and-recreate the same id.

**4. Hold both roles.** Default dialer alone is not enough — if any other
app holds `ROLE_CALL_SCREENING`, XX-Dialer's screener is silently never
invoked.

**5. The pure core carries the correctness burden.** `RingPolicy`, `Window`,
and `E164` import nothing from `android.*`. A workstream with failing tests
is not done.

**6. Observe mode is a gate, not a branch.** The verdict path runs
identically; observe differs only at the final gate (D13).

---

## Workstreams 0–10 (historical)

| WS | Scope | Status |
|---|---|---|
| 0 | Probe APK | BUILT-AWAITING-DEVICE |
| 1 | Skeleton | DONE |
| 2 | `core/` §6 truth table | DONE (66 named tests) |
| 3 | Dialer plumbing | CODE-COMPLETE; device gate OPEN |
| 4 | FactStore + mirror | CODE-COMPLETE; device gate OPEN |
| 5 | Call screening | CODE-COMPLETE; device gate OPEN |
| 6 | Ringer / channels | CODE-COMPLETE; device gate OPEN |
| 7 | Incoming + in-call UI | PARTIAL; daily-driver gate OPEN |
| 8 | Recents / keypad / people | DONE-CODE |
| 9 | Rules / log / missed-call | DONE-CODE |
| 10 | Backup / Expecting-a-call / polish | MOSTLY-DONE |

O1 (Views vs Compose) is closed: Views, as spec D7.

The stale "Status: nothing built." line that used to live below the first
ledger is retired. The repo is a working tree, not a spec-only checkout.

---

## WS11 — Ship-readiness (this cycle)

Gate: `./gradlew :core:test :app:testDebugUnitTest` green, plus
`:app:assembleDebug` with `verifyNoInternet`. Device proof is **not** this
gate — it stays OPEN.

### Ringer fail-open (R9)

- [x] **T1.** `XxInCallService` owns `IN_CALL_SERVICE_RINGING`, so the system
      will not ring. Pipeline/`notify()` failure must still present a
      default-tone CallStyle (or `startForeground` fallback) for a still-
      `RINGING` call. Never leave a ringing call unpresented.
- [x] **T2.** After live PhoneLookup, skip `postIncoming` unless
      `call.state == STATE_RINGING`. If it became `ACTIVE` mid-pipeline, go
      ongoing only. If the call is gone (`entry == null`), do not present.
- [x] **T4.** Second call while in-call: heads-up only, no FSI, no second
      ringtone over the active audio path (design §15). Drive accept via
      `CallGrid.answerWaiting()`.
- [x] **T5.** `onCallAdded` of an already-`ACTIVE`/`HOLDING` call (rebind,
      crash, role grant mid-call) must `markOngoing` + `maybeRecordEmergency`.
- [x] **T15.** CallStyle `setSmallIcon` uses a monochrome status drawable
      (`ic_phone_incoming`), never the adaptive launcher mipmap.

### Policy / facts

- [x] **T6.** `EmergencyWindow`: if `nowElapsed < markerElapsed`, treat
      elapsed as a reboot (unusable) and close on wall time only. The OR is
      for clock skew, not monotonic-clock reset.
- [x] **T7.** `SEND_TO_VOICEMAIL` is honored: D10/D15 must not pierce it;
      ring-time routes to `None` and rejects toward voicemail. Observe mode
      still rings (D13 last gate).
- [x] **T8.** Hidden-caller policy applies only when presentation is
      withheld. An ALLOWED number that fails E.164 is unknown, never hidden.
      Add `CallerFacts.withheld`.
- [x] **T10.** Persist a stable verdict token (`Block`/`Silence`/`Ring`)
      independent of `KClass.simpleName`. Keep those names through R8.
      `proguard-rules.pro` keeps `Verdict` as belt-and-suspenders.

### Mirror

- [x] **T3.** `contact_mirror` is keyed by `(lookupKey, e164)` — Room v2 +
      real migration, no destructive fallback. `MirrorRows.dedupe` keeps
      every resolvable number. People list still shows one row per contact;
      the sheet lists every number. Screening `findByE164` then sees the
      second number as saved (contacts exemption / D5).

### In-call UI

- [x] **T9.** `CallGrid` listeners are a list. `InCallActivity.onDestroy`
      removes only its own listener so rotation cannot wipe the next
      instance.
- [x] **T11.** `swap()` waits for `STATE_HOLDING` before `unhold` of the
      parked call. Fallback timer if the hold callback never arrives.

### Setup / Recents / missed-call

- [x] **T12.** Setup warns (does not hard-gate `isFullyConfigured`) when
      notifications are disabled or ring channels are `IMPORTANCE_NONE`.
      Offer the system notification screen / `POST_NOTIFICATIONS`.
- [x] **T13.** Silenced-notification `"never"` applies only to enforced
      Silence. A ringing-then-missed call (including observed-silence, which
      rang) still posts Telecom's missed-call card.
- [x] **T14.** Recents merge pairs withheld platform rows with withheld
      `screen_log` rows on time proximity so R7 glyphs still attach.

### Tests

- [x] **T16.** `testInstrumentationRunner` on `:app`. Leave on-device
      androidTest `@Ignore`d (still needs `caiman`). Add JVM tests for:
      T6 reboot, T7 no-pierce voicemail, T8 unparseable-vs-hidden, T3
      multi-number dedupe, T11 swap sequencing, T13 missed-notif policy,
      T14 withheld Recents join, unknown-window 08:59/09:00/16:59/17:00,
      repeat 14:59/15:01 and recent-outgoing 47:59/48:01 against a fake
      clock.

WS11 JVM gate: **350 tests green** (`:core` 72, `:app` 278),
`assembleDebug` + `verifyNoInternet` PASS. Device proof still OPEN.

### After WS11 (still OPEN — not this cycle)

- Run [PROBE.md](PROBE.md) on `caiman`; paste V1–V4 into design §4.
- One real incoming, one real outgoing, one call-waiting, one
  voicemail-contact, one short-code — **observe mode, live SIM**.
- Un-ignore a smoke subset of androidTest on that Pixel.
- Then, and only then, offer enforcement.

---

## Standing rules

- A workstream with failing tests is not done.
- `aapt2 dump permissions` shows no `INTERNET` at every WS exit.
- Instrumented tests for WS5–7 run on `caiman` itself; the emulator does not
  have GrapheneOS's Contact Scopes, its exposed block-unknown setting, or a
  real carrier's STIR behavior.
