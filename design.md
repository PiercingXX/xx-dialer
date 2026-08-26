# XX-Dialer — Design Specification

Android default dialer for GrapheneOS. A ring-policy engine wearing a phone
app: spam never rings, starred contacts always ring, everyone else rings only
when their window says so — unknown numbers on their own ringtone, 9 to 5.

The dialer exists because the policy needs it: only the default dialer is
allowed to own the ringer, and owning the ringer is the only way to give
unknown callers a different ringtone. A screening-only app cannot do it
(§4.3). So XX-Dialer is the whole phone: keypad, recents, contacts, in-call —
and the rules engine that is the actual point.

**Status:** specification only. Nothing built.
**Build plan:** [todo.md](todo.md) — workstreams, gates, and the order to do them in.
**Screens:** [design/xx-dialer-screens.html](design/xx-dialer-screens.html) — the mockup.
**Research:** [design/research.md](design/research.md) — sourced findings behind this spec.
**Target:** Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37.

---

## 1. Cleanroom provenance

This repository is **all rights reserved**. Every live FOSS dialer (Fossify
Phone, Emerald Dialer, Koler) is GPL-3.0, and the one meaningful FOSS blocker
that isn't (SpamBlocker, MIT) still gets treated as read-with-care. Copying
GPL source would force this project to GPL.

**What was studied:** official Android documentation and CDD, F-Droid and
Play listings, vendor help pages, published screenshots, press coverage,
reviews, and GrapheneOS forum threads. Full citations in
[design/research.md](design/research.md).

**What was never opened:** the source of Fossify Phone, Fossify Contacts,
Simple Dialer, Emerald Dialer, Koler, Silence, Saracroche, PhoneBlock, or
Yet Another Call Blocker (AGPL — doubly radioactive).

**What may be consulted:** AOSP Dialer/Telecom (Apache-2.0 — reference only;
copying would drag NOTICE obligations into an all-rights-reserved repo, so
reimplement instead), `libphonenumber` (Apache-2.0, already in the framework),
and SpamBlocker **only after** first-hand verification of its LICENSE file —
F-Droid and GitHub metadata say MIT, one secondary source claims GPLv3.
Until verified, treat it as behavior-reference only.

Anyone extending this project holds the same line: **read their docs, never
their source.**

### Prior art and what each contributed

| Project | License | What was taken (behavior only) |
|---|---|---|
| Google Phone | proprietary | The IA (3 tabs, favorites carousel above the log, filter chips); spam UX; and the strongest validation available: Google shipped a **4-category × 3-action screening matrix** (Spam / Faked / First-time / Hidden × Ring / Silently decline / Screen) and **deleted it in May 2023** to public criticism. The granularity is wanted, and nobody ships it. |
| iOS Silence Unknown Callers / Focus | proprietary | Silenced-not-rejected as the correct disposition (call lands in Recents, still answerable); repeat-caller override; the emergency safety valve — calling emergency services disables screening for 24 h. |
| Android DND / Modes | platform | Starred contacts as the always-ring set (same `STARRED` flag, so the concept is already in the user's head); the 15-minute repeat-caller window. |
| Call Control | proprietary | The only app that **publishes its rule-priority model**; digit-mask pattern building (`425-555-XXXX`); three distinct block dispositions. |
| Should I Answer | proprietary | Proof that a fully-offline classification DB is viable; the contacts-exemption guarantee on pattern rules ("matching Contacts will not be blocked"). |
| SpamBlocker | MIT (unverified) | Proof the GrapheneOS audience exists — it is the community's standing recommendation; contact-group rules + time windows + STIR in one screening app. Nobody combines those with a dialer. That gap is this product. |
| Fossify Phone | GPL-3.0 — never opened | Tab-hiding as a loved setting; offline dialer table stakes; full-screen call alerts. |

---

## 2. Requirements

**R1.** Replace the stock dialer completely: place and receive calls, keypad,
recents, contact list, in-call screen, call waiting (hold/swap), audio routing.
**R2.** Spam is rejected **before the phone rings** and never produces a sound.
**R3.** Saved contacts ring through any time, on the normal ringtone.
**R4.** Unknown numbers ring only **09:00–17:00**, on a **distinct ringtone**.
Outside the window they are silenced, not rejected.
**R5.** Starred contacts ring through any time. Starred beats everything below it.
**R6.** Business contacts ring only **09:00–19:00**, unless also starred.
**R7.** Every screened call is visible afterwards **with the rule that fired**
— "Silenced · Business, outside 09:00–19:00". No call ever silently vanishes.
**R8.** No `INTERNET` permission, ever. All classification is local. This is
verifiable with `aapt2 dump permissions`, and on GrapheneOS the app shows no
Network permission at all — nothing to audit, nothing to toggle.
**R9.** **Fail open.** Any failure in the policy path — timeout, stale data,
crash — must resolve to *the phone rings*. A bug may cause one nuisance ring;
it must never eat a real call.
**R10.** Emergency calls are never screened. After an outgoing emergency call,
all screening is disabled for 24 hours (the iOS rule, adopted verbatim).
**R11.** Enforcement has an **observe mode**: every call rings normally while
the log records what XX-Dialer *would have* done. Setup defaults to observe
for the first week — a phone app earns trust before it silences anything.
The switch doubles as the troubleshooting escape hatch, with evidence intact.

### Non-goals

- SMS/MMS. XX-Dialer never takes the SMS role.
- Visual voicemail. Carrier VVM needs IMAP over the network — R8 forbids it.
  Dial-in voicemail works like it's 2004. GrapheneOS itself hasn't solved VVM;
  deferring is defensible.
- Call recording — **permanently**, not deferred. GrapheneOS allowlists
  `CAPTURE_AUDIO_OUTPUT` to the stock dialer package specifically
  (`/data/etc/com.android.dialer.xml`); a third-party dialer cannot record
  calls on this OS. Users who need it keep the stock dialer.
- Cloud caller-ID, reverse lookup, crowdsourced spam DBs. That entire product
  category works by uploading every caller's number; R8 exists to make the
  opposite claim.
- "Frequently contacted." Android 10 zeroed all contact-affinity data
  permanently. Starring is the only ranking signal the platform offers — this
  is a platform fact, not a choice.
- Video calls; RTT in v1 (not a CDD obligation — an accessibility choice,
  deferred with intent to revisit); conference merge in v1 (hold/swap ships).
- Telemetry, analytics, crash reporting.

---

## 3. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **Full default dialer, not a screening add-on** | `CallScreeningService` alone cannot change what tone plays — the system rings. Only the default dialer may declare it plays its own ringtone (§4.3). R4 is impossible any other way. |
| D2 | **Ringing = a CallStyle notification on a per-tier notification channel** | The documented mechanism for dialer-owned ringing. Channels give per-tier tone/vibration, and DND, ringer mode, and volume are honored **by the platform**, not by our code. The app never touches `MediaPlayer` to ring — that path would bypass DND and Nope-Mode's zen rule, and is the trust failure this app exists to avoid. |
| D3 | **Policy is pure and evaluated at call time** | `decide(now, facts, rules)` — no alarms, no accumulated state, nothing to reconcile after a reboot. Windows are checked when the call arrives, on the wall clock. |
| D4 | **Tier data is app-local, keyed by `LOOKUP_KEY`** | The "Business" label cannot live in `ContactsContract.Groups`: AOSP Contacts won't create groups for local (account-free) contacts, and GrapheneOS Contact Scopes blocks all contact writes. Only `STARRED`, saved-ness, and `CUSTOM_RINGTONE` are read from the provider; everything XX-Dialer owns lives in Room. |
| D5 | **Only explicit signals block; heuristics silence at worst** | The block verdict is reserved for the user's blocklist, the user's pattern rules, and STIR/SHAKEN `FAILED` (configurable). Everything else degrades to silence, which is recoverable. This is R9 as an invariant. |
| D6 | **System `BlockedNumberContract` is the blocklist store** | The platform rejects those numbers upstream of everything, the data survives XX-Dialer losing the role, and `createManageBlockedNumbersIntent()` gives a system-owned management UI. Pattern rules layer on top in Room. |
| D7 | **Kotlin + Views, no Compose** | Family default (Launcher, Nope-Mode). XX-Vitals went Compose by operator ruling, app-specifically. A dialer's incoming-call surface wants the cheapest possible cold inflate. **Open to operator override, same as Vitals D4** — if overruled, nothing else in this spec changes. |
| D8 | **No `INTERNET` permission** | R8. On GrapheneOS this is stronger than "network revoked" — the permission simply doesn't exist on the app, and the community can verify it from the manifest. |
| D9 | **The user tier is "Business" in the UI, never in the code** | Android 15 added `CallLog.Calls.IS_BUSINESS_CALL` / `Call.EXTRA_IS_BUSINESS_CALL` — a *carrier-asserted* "the caller is a business" flag with opposite semantics to our user-assigned tier. Internally the tier is `TIER_BIZ`; the platform extra is consumed only as an input signal when classifying unknown callers. Colliding names here would ship a feature that silently disagrees with a platform flag. |
| D10 | **Repeat-caller override pierces *every* Silence verdict** — on by default, 15 minutes | A second call from the same number inside the window rings through any silence: unknown out-of-window, Business after hours, hidden-caller policy, a silence-pattern. The override is an urgency signal, and urgency doesn't care which rule silenced the first call. It never pierces a Block. 15 min matches Android DND's convention (iOS uses 3); a missed genuine emergency is worse than one unwanted ring — same call Nope-Mode made. Configurable. |
| D11 | **Per-contact `CUSTOM_RINGTONE` beats the tier tone; `SEND_TO_VOICEMAIL` is honored** | Both are existing per-contact routing primitives the user may already have set. Samsung documents the precedence rule ("the ringtone of a contact is preferred") and it's correct — the more specific setting wins. |
| D12 | **Room + Gson, shared debug keystore, `com.piercingxx.xxdialer`** | Same stack as the family; backup JSON conventions carry over. |
| D13 | **Observe mode is the default for the first week** | R11. The verdict path runs identically in both modes — observe differs only at the final gate, so a week of observation exercises the real code. The only thing observe cannot show is system-blocklist rejections: the platform enforces those upstream and they never reach us (§4.4). |
| D14 | **Recent-outgoing callback window: an unknown number dialed in the last 48 h rings through** | The biggest false-positive class in the research is the callback — the pharmacy, plumber, or delivery driver returning *your* call from a direct line. iOS ships this inside Silence Unknown Callers. One `CallLog` outgoing query; 48 h default, configurable. |
| D15 | **"Expecting a call" is a bounded bypass, Nope-Mode-break style** | Unknown and silenced callers ring for the next 2 h, then it expires on its own. There is no indefinite variant, by construction — the family already learned that an unbounded off-switch turns policy into a suggestion. Optional QS tile. |

---

## 4. Platform mechanics

The whole design rests on these; the load-bearing ones are quoted, not
paraphrased. Facts marked **[VERIFY]** are unconfirmed on GrapheneOS and are
WS0's job to prove on-device before anything is built on them (§17).

### 4.1 Becoming the default dialer

`RoleManager.ROLE_DIALER` (API 29), requested via `createRequestRoleIntent()`
— `ACTION_CHANGE_DEFAULT_DIALER` is dead since Q. The required components,
verified against `PermissionController`'s `roles.xml`:

1. An activity handling `ACTION_DIAL` with **no** data element.
2. An activity handling `ACTION_DIAL` with `android:scheme="tel"`.
3. An `InCallService` guarded by `BIND_INCALL_SERVICE`, with metadata
   `IN_CALL_SERVICE_UI = true`, **`android:exported="true"`** (the javadoc
   warns non-exported services can fail to bind), and — disqualifier —
   **no** `IN_CALL_SERVICE_CAR_MODE_UI` metadata, which `roles.xml` marks
   prohibited.

The grant auto-includes the phone, contacts, and notifications permission
sets (`READ_CALL_LOG`, `READ_CONTACTS`, `POST_NOTIFICATIONS`, …) — no
separate prompt cascade. Disabling the InCallService component at runtime
revokes the role and kills the app; don't.

**XX-Dialer holds two roles.** Telecom runs exactly one app screener: the
`ROLE_CALL_SCREENING` holder if one exists and differs from the default
dialer, else the default dialer. If another app (SpamBlocker, say) holds the
screening role, XX-Dialer's screener is silently never invoked. So Setup
claims `ROLE_CALL_SCREENING` too — vacant on stock GrapheneOS — and detects
the conflict when it isn't.

**Outgoing calls always go through `TelecomManager.placeCall()`, never
`Intent.ACTION_CALL`** — the platform hands emergency dialing to the
preloaded dialer, and `ACTION_CALL` on an emergency number bounces through a
confirmation flow the docs themselves call suboptimal.

**The Restricted Settings gate.** Since Android 15 the CDD lists
`ROLE_DIALER` as a Restricted Setting for sideloaded apps: the role request
can be refused until the user opens App Info → ⋮ → *Allow restricted
settings*. This is documented in the wild on GrapheneOS (ACR Phone greyed out
in the default-dialer picker, fixed exactly that way). **This will be the #1
onboarding support problem — the setup screen walks the user through it,
step by step, with the failure detected rather than assumed.** Whether
GrapheneOS enforces, relaxes, or modifies the gate is **[VERIFY]** — the
single highest-risk unknown in this spec.

A half-set role produces silent-ring symptoms (documented with Fossify Phone
on Pixel 8: notifications but no ring). Setup must verify the role is
actually held, not merely requested.

### 4.2 Call screening

`CallScreeningService`, bound before the device rings. The response deadline
is **5 seconds** (`CALL_SCREENING_FILTER_TIMEOUT = 5000` in Telecom, verified
unchanged on the `android17-release` branch); on timeout the platform
proceeds as if allowed — which is R9's fail-open, for free.

`CallResponse` vocabulary: `setDisallowCall`, `setRejectCall`,
`setSkipCallLog`, `setSkipNotification` (API 24), `setSilenceCall` (API 29 —
"the call will still be sent to the default dialer app", i.e. silenced but
surfaced). Do **not** compile against `setRejectedAsMissed` — it is
unreleased through 37.1.

**The contacts gate — resolved in our favor.** The docs say third-party
screeners don't see calls from contacts. Verified in AOSP source
(`CallScreeningServiceFilter`, unchanged on `android17-release`): the skip
applies to *user-chosen* screeners only — **the default dialer's screener
receives every screened call, contacts included, unconditionally.**
Screening still produces **block verdicts only** (contacts are never blocked,
D5); tone and window enforcement happens at ring time in the InCallService,
which has the full `Call.Details`, no 5-second clock, and no
`USER_MISSED_*` stamp on the log row — the screening `Call.Details` carries
just five fields, and `setSilenceCall` would mark the row
`CALL_SCREENING_SERVICE_SILENCED`, which is the platform blaming us for a
policy the user wrote.

**What never reaches the screener:** calls with restricted/unavailable/
payphone presentation (withheld caller ID) — only `tel:` handles with
allowed presentation are delivered. The hidden-caller policy therefore lives
at ring time (§6).

### 4.3 Owning the ringer

The default dialer declares `TelecomManager.METADATA_IN_CALL_SERVICE_RINGING`
and becomes responsible for playing the ringtone. Verified on
`android17-release`: `letDialerHandleRinging` still short-circuits the system
`Ringer`. The documented implementation is a **notification channel with the
desired sound**, posted as a `Notification.CallStyle.forIncomingCall` (API
31+ — system-owned Answer/Decline labels, top ranking, the status-bar call
chip since Android 12).

Channel mechanics that shape the design:

- **A channel's sound is immutable after creation**, and delete-and-recreate
  with the same ID *un-deletes* the old settings. Changing the unknown-caller
  tone in-app therefore mints a **new channel ID** and deletes the old one —
  the channel registry (§11) exists for this.
- The default-tier channel is created with `Settings.System.DEFAULT_RINGTONE_URI`
  — the indirection URI, so it follows the user's system ringtone without
  churning channels. **[VERIFY]** that the indirection resolves at play time
  on the target build.
- Because ringing goes through the notification pipeline, **DND, ringer mode,
  per-channel user overrides, and Nope-Mode's `AutomaticZenRule` all apply
  natively.** XX-Dialer contains no "should I be quiet?" logic at all — that
  is the platform's job, and taking it over is how a dialer becomes malware
  with a keypad. Two belt-and-suspenders reads exist if ever needed:
  `Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB` (API 34, stamped on the call
  before the InCallService sees it — used for the log annotation "suppressed
  by DND") and `NotificationManager.matchesCallFilter()` (public since 33,
  `READ_CONTACTS` suffices).
- Obligations that come with owning the ringer: `onSilenceRinger()` must
  stop the ring (the user pressed volume), and a call carrying
  `EXTRA_SILENT_RINGING_REQUESTED` must not ring at all.

Full-screen intent (`USE_FULL_SCREEN_INTENT`) is special app access on 14+;
calling apps are auto-granted, but handle `canUseFullScreenIntent() == false`
by falling back to the heads-up presentation and deep-linking the grant screen.

### 4.4 Caller verification and blocking

- **STIR/SHAKEN:** `Call.Details.getCallerNumberVerificationStatus()` —
  `VERIFICATION_STATUS_PASSED / FAILED / NOT_VERIFIED` (Android 11+). Only
  pass/fail is exposed, not FCC attestation levels; requires VoLTE and
  carrier support, so `NOT_VERIFIED` dominates and **must not be treated as
  suspicious**. Only `FAILED` — a failed signature check, i.e. likely
  spoofing — is a spam signal. What the target carrier actually delivers is
  **[VERIFY]**.
- **`BlockedNumberContract`:** readable/writable by the default dialer;
  matching calls are rejected by the platform upstream of screening and
  survive XX-Dialer losing the role. Management UI via
  `TelecomManager.createManageBlockedNumbersIntent()` rather than a
  hand-rolled screen.
- Blocked-number matching runs in the platform's filter graph **before** the
  carrier screener, our screener, or any ringing — a blocked call never
  reaches XX-Dialer at all. The platform also suppresses its own blocking for
  2 hours after an emergency call (`KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT`,
  AOSP default 7200 s) — independent of, and weaker than, R10's 24 h.
- Two upstream user settings can starve the policy engine, and Setup must
  detect and name both:
  - *Blocked numbers → Unknown* — blocks withheld numbers, making the
    hidden-caller policy (§6) moot while enabled.
  - **GrapheneOS's "block callers not in contacts"** — GrapheneOS flips the
    AOSP config to expose this hidden setting. Enabled, `BlockCheckerFilter`
    kills every unknown number with `BLOCK_REASON_NOT_IN_CONTACTS` before
    anything of ours runs, and the unknown-caller tier silently dies. The
    setting itself is unreadable (`SystemContract`); detection is empirical —
    `BLOCK_REASON_NOT_IN_CONTACTS` rows in the call log — and the warning
    says exactly which setting to turn off.

### 4.5 GrapheneOS specifics

- Stock dialer is the **AOSP Dialer fork** (`com.android.dialer`, plus call
  recording). Its spam framework is wired to a `SpamStub` that answers
  "not spam" to everything — the gap XX-Dialer fills is structural, not an
  oversight. GrapheneOS's role policy and Telecom fork are unpatched in this
  area; third-party default dialers are permitted and work (forum-documented).
  The remaining on-device unknown is the Restricted Settings gate (§4.1),
  hence WS0.
- **Contact Scopes:** the app *believes* it has Contacts permission while
  reads return a user-chosen subset — possibly empty, photos withheld under
  single-contact grants, account identity always hidden, and **all writes
  blocked**. XX-Dialer must render a partial or empty address book without
  crashing or re-prompting, never assume a contact write succeeded (there are
  none in v1 — D4 exists partly for this), and fall back to deterministic
  monogram avatars. Whether Scopes also filters `PhoneLookup` at call time is
  **[VERIFY]** — if it does, scoped-out callers classify as unknown, which is
  the correct degradation.
- Device identifiers (IMEI etc.) are unavailable to the dialer role. Nothing
  in this design wants them.
- Contacts provider tightening at `targetSdk 37`: account columns are gone
  from the `Data` table and strict SQL applies. `PhoneLookup` is unaffected;
  any `Data`-table query gets audited in WS4.

---

## 5. Architecture

```
                       ┌──────────────────────────────────┐
                       │        RingPolicy (pure)         │
                       │  decide(now, facts, rules)       │
                       │  imports nothing from android.*  │
                       └────────▲───────────────▲─────────┘
                                │               │
              pre-ring, blocks  │               │  ring time, every call
                     only       │               │
        ┌───────────────────────┴──┐   ┌────────┴──────────────────┐
        │  XxCallScreeningService  │   │ XxInCallService + Ringer  │
        │  Disallow / allow within │   │ pick channel by verdict:  │
        │  5 s; never silences a   │   │ tier tone · silent · FSI  │
        │  contact                 │   │ + in-call UI              │
        └───────────▲──────────────┘   └────────▲──────────────────┘
                    │                           │
              ┌─────┴───────────────────────────┴─────┐
              │              FactStore               │
              │  Room: tiers, patterns, log, settings │
              │  warm mirror of starred/saved/tone    │
              │  (5-second budget → no cold queries)  │
              └───────────────────────────────────────┘
```

`RingPolicy` computes *what should happen*; the two services merely apply it
at their stage of the pipeline. It imports nothing from `android.*` — the
part that must be correct is testable on the JVM with no device (the
Nope-Mode discipline, unchanged).

The split of labor:

| Stage | Sees | May decide | May not |
|---|---|---|---|
| Platform (upstream) | everything | reject system-blocked numbers, withheld numbers if the user set that | — |
| `XxCallScreeningService` | non-contacts at minimum (§4.2) | **Block** (spam/blocklist/pattern) | silence or ring — those are ring-time decisions |
| `XxInCallService` ringer | every ringing call | tier → channel (tone), silence-with-reason | block — too late, and not its job |

---

## 6. The ring policy

The heart of the app. First match wins, and the precedence is **printed in
the UI** on the Rules screen — Call Control is the only shipping app that
publishes its priority model, and the confusion every other app generates is
the lesson.

| # | Condition | Verdict | Tone |
|---|---|---|---|
| 1 | Emergency: call carries `EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS`, or within 24 h of an outgoing emergency call | **Ring** | default |
| 2 | Contact has `SEND_TO_VOICEMAIL` set | platform routes to voicemail — honored, logged | — |
| 3 | Number in system blocklist, or matches a **block** pattern rule | **Block** | — |
| 4 | STIR/SHAKEN `FAILED` (setting: block / silence / off, default block) | **Block** | — |
| 5 | ★ Starred contact | **Ring**, any time | default |
| 6 | Business tier, inside 09:00–19:00 | **Ring** | default |
| 7 | Business tier, outside the window | **Silence** · "Business, outside 09–19" | — |
| 8 | Saved contact | **Ring**, any time | default |
| 9 | Unknown, but dialed by the user within 48 h (D14) | **Ring**, any time · "you called them Tue" | unknown tone |
| 10 | Matches a **silence** pattern rule (e.g. the neighbor-spoof preset, §8) | **Silence** · "pattern 425-555-XXXX" | — |
| 11 | Unknown, inside 09:00–17:00 | **Ring** | **unknown tone** |
| 12 | Unknown, outside the window | **Silence** · "Unknown, outside 09–17" | — |

Overrides and modifiers:

- **Repeat caller pierces any Silence** (D10): same number within 15 min →
  **Ring**, unknown tone, reason "repeat caller". Never pierces a Block.
- **Expecting a call** (D15): while the bounded 2 h bypass is active, every
  Silence verdict becomes **Ring** (unknown tone), logged
  "rang · expecting a call". Blocks still block.
- **Observe mode** (R11/D13): the verdict is computed and logged as above,
  then discarded — every call rings on the default tone, log rows marked
  `observed`. Enforcement is the last gate, not a different code path.
- A per-contact `CUSTOM_RINGTONE` beats the tier tone on any Ring (D11).
- Hidden/withheld numbers take the hidden-caller policy: **treat as unknown**
  (default) / always silence / block. They never reach the screener, so this
  is enforced at ring time.
- `EXTRA_IS_BUSINESS_CALL` (carrier-asserted) never changes a verdict, and
  the **CNAP display name**, when the carrier sends one, is shown on the
  incoming screen and logged ("carrier says: EVERGREEN DENTAL") — the only
  identity signal an unknown caller gets without a cloud lookup.

Conflict rules, stated so they are decisions rather than accidents:

- **Blocked beats starred.** A number that is both starred and blocked is
  blocked — the blocklist is the sharper, later signal — and the screening
  log entry is flagged loud (`⚠ starred number blocked`) so the contradiction
  is visible and fixable.
- **Starred beats Business** (R6 says so: "unless also starred").
- A Business-tier member who is not saved in contacts cannot exist: the tier
  is assigned from a contact row (§9). Unknown callers have no tier.

`Silence` means: the call proceeds on the silent channel — still surfaced as
an answerable CallStyle notification, still in Recents, ordinary missed-call
flow if unanswered — with the reason string attached to the log entry. It is
iOS's silenced-not-rejected disposition plus the reason, which nothing on the
market shows (R7).

```kotlin
data class CallerFacts(
    val number: String?,          // E.164-normalized; null = withheld
    val saved: Boolean,
    val starred: Boolean,
    val bizTier: Boolean,
    val sendToVoicemail: Boolean,
    val userBlocked: Boolean,     // pattern rules; system blocklist never gets here
    val stirFailed: Boolean,
    val repeatCaller: Boolean,    // same number, last 15 min
    val recentOutgoing: Boolean,  // user dialed this number, last 48 h
    val cnapName: String?,        // carrier-sent display name, context only
    val emergencyWindow: Boolean, // 24 h post-emergency, or the platform's
                                  // EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS
)

sealed interface Verdict {
    data object Block : Verdict
    data class Ring(val tone: Tone) : Verdict          // DEFAULT or UNKNOWN
    data class Silence(val reason: Reason) : Verdict
}

fun decide(now: LocalTime, facts: CallerFacts, rules: Rules): Verdict
```

Number matching runs on E.164 normalization (`PhoneNumberUtils` /
libphonenumber, already in the framework) so `+1 (425) 555-0100`,
`4255550100`, and `001425…` are one caller. Normalization bugs are
misclassification bugs; they get the test weight (§16).

---

## 7. Windows

Nope-Mode's schedule model (§7 there), reused without the alarms:

```kotlin
data class Window(
    val startMinuteOfDay: Int,   // 09:00 -> 540
    val endMinuteOfDay: Int,     // 17:00 -> 1020
    val daysMask: Int,           // bit 0 = Monday .. bit 6 = Sunday
)
```

- Two windows ship: `unknown` (540 → 1020, all days) and `business`
  (540 → 1140, all days). Both editable; days mask included because "unknown
  callers on a Sunday" is a legitimately different opinion.
- Start is inclusive, end is exclusive: at 17:00:00 exactly, an unknown
  caller is silenced.
- `end <= start` wraps past midnight — supported and tested because the model
  supports it, even though neither default uses it.
- Evaluation is wall-clock local time at the moment the call arrives (D3).
  DST needs no special handling — there is no alarm chain to drift — but the
  02:00 boundary cases are tested anyway.

### 7.1 Expecting a call

The one stateful mode in the app, and it is bounded by construction (D15):
**Expecting a call** makes every Silence verdict ring (unknown tone) for the
next 2 hours, then expires on its own. No indefinite variant exists — the
type has no way to express "until I turn it off", the same guarantee as
Nope-Mode's `Break`. Stored as `bypass_until` in settings; evaluated inside
`decide()` like everything else. Surfaced as a button on Rules, an action on
the incoming screen's silenced sibling, and an optional QS tile
(`XxTileService`, label shows minutes remaining — the Nope-Mode tile
conventions). Duration configurable (30 min / 2 h / 8 h; default 2 h).

The repeat-caller override (D10) covers the caller who tries twice; this
covers the one who calls once, outside the window, from a number that
couldn't be dialed first. Between them and the recent-outgoing window (D14),
the "waiting on the clinic" failure class is covered three ways.

---

## 8. Spam and the blocklist

Local signals only, layered:

1. **System blocklist** (`BlockedNumberContract`, D6) — exact numbers, user
   managed, platform enforced. The "Block" action everywhere in the UI writes
   here.
2. **Pattern rules** (Room) — digit masks in Call Control's builder style:
   type a number, slide to mask trailing digits (`425-555-XXXX`). Stored as
   a normalized prefix + wildcard length; no regex — a regex field in a
   phone app is a foot-gun with a syntax manual. Each rule's action is
   **block** or **silence**; user-authored rules default to block.
   **Every pattern rule carries the contacts exemption, non-optionally:** a
   number matching a pattern that resolves to a saved contact rings anyway.
   Should I Answer and Hiya both ship this guarantee; the false-positive
   stories in the research are why.
   One preset ships: **neighbor spoofing** — one tap creates a silence rule
   for the user's own six-digit prefix (Call Control's heuristic). Off by
   default, and it is *silence*, never block, because it is a heuristic (D5).
3. **STIR/SHAKEN `FAILED`** (§4.4) — the only heuristic allowed to block, and
   only because a failed signature is an explicit network-level statement
   that the caller ID is forged. Setting: block / silence / off. Default
   block.

Blocked calls: no ring, no notification, but **always in the screening log**
(R7). The platform's own upstream rejections (system blocklist) are annotated
in Recents from `CallLog` block types. User-initiated file import of a
blocklist (one number per line) is offline and fits R8; a subscription-style
updating database does not, and is a non-goal.

What spam blocking is **not** here: there is no community database, no
reputation score, no "suspected spam" label on ringing calls. An unknown
caller inside the window simply rings on the unknown tone — the tone *is*
the label, and it costs no cloud.

---

## 9. Tiers and the fact store

Facts about a caller come from two places, and the split is load-bearing
(D4):

**Read from the provider, mirrored warm:** saved-ness, `STARRED`,
`CUSTOM_RINGTONE`, `SEND_TO_VOICEMAIL`, display name, `LOOKUP_KEY`. A single
`PhoneLookup.CONTENT_FILTER_URI` query returns all of it in one optimized
round trip — that is the live path at ring time, where there is no clock.
The Room mirror (refreshed by a `ContactsContract` observer plus an
on-foreground sweep) serves the screener's 5-second budget and the People
tab, and is the fallback when a live lookup fails.

**Owned by XX-Dialer in Room:** the Business tier — a set of `LOOKUP_KEY`s
assigned in-app from a contact's detail sheet ("Add to Business"). This is a
real feature, not a workaround: on an account-free GrapheneOS device the
stock Contacts app offers **no way at all** to put a contact in a group, so
XX-Dialer's tier UI is the only label-assignment surface the user has.

Staleness consequences are asymmetric by design: a stale mirror can play the
wrong tone or ring outside a window — it can never block, because blocks
come only from the blocklist and patterns (D5). If the mirror is empty
(Contact Scopes, first run), every caller classifies as unknown, which
degrades to "the phone rings 9–5 on the unknown tone" — noisy, never lossy.

If a Business-tier `LOOKUP_KEY` no longer resolves (contact deleted, scope
revoked), the membership is pruned on the next sweep and the pruning is
logged — silently orphaned rules are how apps get haunted.

---

## 10. Ringing

One notification channel per outcome, created at first run and registered in
`channel_registry` (§11):

| Channel | Sound | Importance | Notes |
|---|---|---|---|
| `ring_default_v2` | shipped tone `xx_ringtone.mp3` | high | starred, saved, in-window Business |
| `ring_unknown_v1` | shipped tone `xx_unknown.ogg`, user-swappable | high | version bumps on tone change (§4.3) |
| `ring_silent_v1` | none, no vibration | high (heads-up, no sound) | silenced calls — surfaced, answerable |
| `ongoing_v1` | none | default | in-call, non-dismissable |

- The default tier ships its own tone (`res/raw/xx_ringtone.mp3`).
  `ring_default_v1` pointed at `DEFAULT_RINGTONE_URI` (indirection — followed
  the system setting); channel sound being immutable, the baked tone rode a
  §4.3 version bump to `v2`. That bump happened under the old
  `com.piercingxx.xxphone` application id. The rename to
  `com.piercingxx.xxdialer` makes this a new package with an empty
  NotificationManager, so **no install of this app can carry `v1`** — first
  run mints `ring_default_v2` directly (`ChannelRegistry` seeds its mint walk
  at `firstVersion - 1`) and the below-floor supersede path finds nothing to
  do. The floor is deliberately *not* reset to 1: channel versions are
  append-only counters rather than app versions, a retired id stays retired,
  and the floor machinery is what the next tone change will ride. The
  Setup/§15 channel-health checks match by *purpose* (`ring_default_v*`), so
  they track whichever version is live.
- The unknown tone ships in `res/raw` as an original short tone — mono-ish,
  brand-adjacent, deliberately less urgent than a ringtone. Swapping it mints
  `ring_unknown_v2` and deletes `v1`.
- Vibration follows the channel; per-tier vibration patterns are a plausible
  v2 (Pixel 11 just legitimized per-contact patterns as an accessibility
  feature) — noted, not built.
- Incoming presentation: full-screen intent on the lock screen, heads-up
  when the device is in use. Both are the same CallStyle notification.
- **Nope-Mode interplay, in full:** none required. XX-Dialer rings through
  the notification pipeline; Nope-Mode's `AutomaticZenRule` (starred-only
  calls) filters that pipeline. When Nope-Mode is active, an in-window
  unknown caller is silenced by DND even though XX-Dialer said Ring — correct,
  by layering: XX-Dialer decides *whether this call deserves a ring*,
  DND decides *whether the phone is accepting rings at all*. The one rule is
  D2's: never ring outside the pipeline.
- Dual-SIM: per-`PhoneAccount` ringtones (API 37) are honored for the
  default tier when set — **[VERIFY]** interaction with channel sounds; if
  they conflict, the channel wins and the setting says so.

---

## 11. Data model (Room)

```
tier_member       lookupKey TEXT PK, tier TEXT ('biz'), addedAt INTEGER
pattern_rule      id INTEGER PK, e164Prefix TEXT, wildcards INT,
                  action TEXT ('block'|'silence'), preset TEXT NULL,
                  createdAt INTEGER
contact_mirror    lookupKey TEXT PK, e164 TEXT (indexed), displayName TEXT,
                  starred INT, customRingtone TEXT NULL, sendToVoicemail INT,
                  refreshedAt INTEGER
screen_log        id INTEGER PK, at INTEGER, e164 TEXT NULL,
                  presentation INT, verdict TEXT, reason TEXT,
                  tier TEXT NULL, stir TEXT, cnapName TEXT NULL,
                  mode TEXT ('enforced'|'observed'), answered INT
channel_registry  purpose TEXT PK, channelId TEXT, toneUri TEXT NULL, version INT
setting           key TEXT PK, value TEXT
emergency_marker  id INTEGER PK (=1), lastEmergencyCallAt INTEGER
```

- The system blocklist is **not** mirrored — `BlockedNumberContract` is the
  single source of truth (D6).
- `screen_log` is the reason-string store behind R7 and feeds the Recents
  annotations; capped at 1000 rows, pruned oldest-first.
- Repeat-caller detection is a query over `screen_log` (same `e164`, last
  15 min) — no separate table to drift out of sync. Recent-outgoing (D14) is
  a `CallLog` outgoing query, last 48 h — the platform already keeps that
  table; mirroring it would be a second copy that can lie.
- `bypass_until` (Expecting a call), enforcement mode, observe-week end,
  silenced-notification policy, and window durations all live in `setting`.
- Backup/restore is the family Gson JSON: tiers, patterns, settings, windows.
  Not the log, not the mirror — both rebuild.

---

## 12. UI

Four tabs, Views + viewBinding, AMOLED-black monochrome, matching the
Launcher. Tab-hiding is a setting (the Fossify lesson: this audience loves
removing what they don't use).

1. **Recents** — the home tab. A collapsible starred strip up top (avatar
   monograms, deterministic — photos may not exist under Contact Scopes),
   then the log with filter chips: **All · Missed · Silenced · Blocked**.
   Every screened row carries its verdict inline, glyph-first:
   `✓` rang · `→ Silenced · Unknown, outside 09–17` · `✗ Blocked · pattern
   425-555-XXXX`. Row actions: call back, add to contacts, block, and on
   silenced rows **Ring next time** (stars) / **Add to Business**. Grouping
   of consecutive same-number calls is a setting, default on (Google removed
   grouping in 2025 and users revolted; it's contested, so it's a toggle).
2. **Keypad** — center tab, not a FAB (the FAB pattern is dead even at
   Google). Digits in Space Mono, oversized, tabular. Match-as-you-type
   against the mirror (digits and T9 letters); long-press 1 dials voicemail.
3. **People** — the mirror, sectioned ★ Starred / Business / Everyone.
   A contact sheet shows number(s), tier controls (star ↔ provider when
   writable; Business ↔ Room always), custom-ringtone note if set, and
   call/history actions. Under Contact Scopes with an empty grant this tab
   states plainly why it is empty and works anyway.
4. **Rules** — the product. Top: the **enforcement switch** (Enforcing /
   Observing — the switch reads "Silence and block calls", and in observe mode
   the whole screen wears a "watching only — every call still rings" banner;
   "enforcement" is state-machine vocabulary and stays out of the UI) and the
   **Expecting a call** button with its countdown
   when active. Then the precedence list (§6) rendered as the actual
   evaluation order with live windows — this screen *is* the documentation.
   Then: window editors (time + days), unknown-tone picker, hidden-caller
   policy, STIR action, repeat-caller toggle, silenced-notification policy
   (immediately / daily summary / never), pattern rules with the mask
   builder and the neighbor-spoof preset, a link to the system
   blocked-numbers screen, and **Test a number** — type any number, see the
   verdict and rule that would fire *right now*, powered by the same
   `decide()`. The screening log lives here too, full-length with reasons,
   plus the running tallies (this week: screened / silenced / blocked —
   Should I Answer's About-screen counter was the entire state of the art;
   a row of Space Mono numerals clears it).

**Incoming call** — caller name/number huge in Space Mono, one context line
under it: the tier and why it rang (`Unknown · rings 9–17` / `★ Starred` /
`you called them Tue`), plus the CNAP name when the carrier sends one
(`carrier says: EVERGREEN DENTAL`).
Answer is a full-width Signal-white block (the accent — inversion, not hue);
Decline is a hairline-outlined block; Reply-with-text beneath (editable
canned responses). Answer interaction is a setting: **two-button tap**
(default) or horizontal pill slider — the two gestures Google kept after
publicly abandoning vertical swipe as error-prone.

**In-call** — duration in tabular Space Mono, primary row Keypad · Mute ·
Speaker/route · More; overflow holds Hold, Add call. Audio routing renders
`CallEndpoint` devices (BLE Audio and hearing aids included) — implement
both the `CallEndpoint` path (34+) and the deprecated `CallAudioState` one. Call waiting:
heads-up CallStyle for the second call, hold-and-answer on accept, swap via
the top banner. The status-bar call chip comes free with CallStyle.

**Notifications** — the silenced/missed notification carries the recovery
affordances inline: **Call back · Ring next time (★) · Block** — one step
better than Calls Blacklist's one-tap-from-log, which was the best recovery
affordance in the survey. Bursts batch ("3 silenced calls") so an
out-of-window spam run never stacks seven cards, and the whole category
obeys the three-state policy (immediately / daily summary / never) because
the research found users demanding both "tell me everything" and "the banner
is the spam" in the same threads.

**Setup** — first-run flow: request role → detect the Restricted Settings
refusal and walk through App Info → verify role actually held → channel
creation → contacts access (or Scopes) → **start in observe mode** with a
plain statement of what that means and when enforcement is offered → done. Each step shows its actual
state; a half-configured dialer that looks configured is the Fossify
silent-ring bug, and it is designed against explicitly (R7's spirit,
pre-install).

### 12.1 Design tokens

Source of truth:
[`piercingxx-branding`](https://github.com/PiercingXX/piercingxx-branding) —
`BRAND-GUIDE.md` §3, vendored as `tokens/android-colors.xml` into
`res/values/`, updated by re-copying, never retyped. Both faces ship in
`res/font/` (Space Mono display, JetBrains Mono body), per the checklist.

The rules that bite here:

- **Reserved white:** body text caps at `text` 90%. The Answer block and the
  active tab are the Signal moments. Strong emphasis inverts — white block,
  ink text.
- **One accent, no hue:** verdict states are glyph-first — `✓` at `ok`,
  `→` at `info`, `✗ Blocked` may use `error` because a blocked call is the
  one loud state. Spam gets no orange badge, no red banner; the absence of a
  ring is the feature.
- **Tabular figures everywhere digits live:** keypad, durations, timestamps,
  counters. A reflowing call timer is the fitness-ring tell of dialers.
- XX-Dialer claims no product signal color; family white stands. Rare colors
  stay unused (a Rare Green moment on "0 spam rings this month" was
  considered and rejected — it would be weekly, which is the rule's test for
  wrong).

Type ramp:

| Role | Face | Size |
|---|---|---|
| Incoming caller name, keypad digits | Space Mono | 44 / 34 sp |
| Call duration, stat numerals | Space Mono | 28 sp |
| Body, list rows, reasons | JetBrains Mono | 16 / 14 sp |
| Chips, eyebrows, verdict glyph lines | JetBrains Mono | 11 sp, +0.08em tracking |

### Theme sync with XX-Launcher

XX-Launcher broadcasts `xx.launcher.THEME_CHANGED` carrying the active theme's
display name and its resolved background ARGB, targeted at each family app by
package. All nine subscribe. XX-Dialer's exported receiver resolves the carried
name to a `ThemePreset`, persists it to the ground store so it survives process
death, and the UI repaints.

Eight choices: AMOLED Night, Graphite, Forest Night, Ocean Drift, Burgundy,
Paper, Mist, and Custom. Custom is the one with no preset to resolve, so the
receiver takes the ARGB straight off the broadcast. **A Custom broadcast
arriving without a background persists nothing** rather than guessing at a
ground — a wrong ground is worse than a stale one. Verified live on-device.

XX-Dialer is the family app that consumes the raw ARGB; TxxT keys off the name
only and leaves Custom on the last resolved preset.

---

## 13. Manifest

```xml
<uses-permission android:name="android.permission.READ_CONTACTS"/>
<uses-permission android:name="android.permission.READ_CALL_LOG"/>
<uses-permission android:name="android.permission.WRITE_CALL_LOG"/>
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<!-- deliberately NO android.permission.INTERNET -->
```

Components: `XxInCallService` (`BIND_INCALL_SERVICE`, exported, metadata
`IN_CALL_SERVICE_UI=true`, `IN_CALL_SERVICE_RINGING=true`, no car-mode
metadata — §4.1), `XxCallScreeningService` (`BIND_SCREENING_SERVICE`
exactly), dial activities handling `ACTION_DIAL` / `ACTION_VIEW tel:` (with
and without data — role eligibility), a receiver for
`TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION` so XX-Dialer owns the
missed-call notification and can annotate it with the reason string,
`XxTileService` for the Expecting-a-call tile (manual shade placement, as
ever), the four tab activities, incoming/in-call activities, setup.

No boot receiver, no alarms — D3 means there is nothing to re-arm.

The absent `INTERNET` permission is the verifiable privacy claim (R8), same
as Nope-Mode. Keep it true forever; it is checkable with
`aapt2 dump permissions`.

---

## 14. Package layout

```
com.piercingxx.xxdialer
├── core/       RingPolicy, Windows, Verdict, CallerFacts, Reason   ← pure JVM
├── telecom/    XxInCallService, XxCallScreeningService, CallManager,
│               EmergencyMarker
├── ring/       ChannelRegistry, RingRouter (verdict → channel),
│               SilencedNotifier (actions, batching), XxTileService
├── data/       entities, DAOs, XxDatabase, ContactMirror, BackupJson
├── ui/         RecentsActivity, KeypadActivity, PeopleActivity,
│               RulesActivity, IncomingCallActivity, InCallActivity,
│               SetupActivity
└── util/       E164 (normalization), Monogram
```

---

## 15. Failure modes

| Scenario | Required behaviour |
|---|---|
| Screening service slow or crashed | Platform times out at 5 s and the call proceeds — fail open (R9). Never extend work past the deadline; verdicts come from warm data or not at all. |
| Contact mirror empty or stale (Contact Scopes, first run) | Caller classifies as unknown → rings 9–17 on the unknown tone. Wrong tone possible; blocked call impossible (D5). |
| Dialer role revoked / never granted | XX-Dialer degrades to a screening-only app if it holds that role, else inert; Setup shows exactly which tier of function is live. Never claim protection that isn't running (the Nope-Mode R8 rule). |
| Restricted Settings refusal on role request | Detected (role absent after request) → Setup walks through App Info → *Allow restricted settings*, then re-requests. |
| `canUseFullScreenIntent()` false | Heads-up presentation only + a Setup line offering the grant screen. Calls still ring. |
| User deletes/mutes a ring channel in system settings | Detected on reconcile-at-call; surfaced on Setup as a warning ("unknown-caller ring is muted at the system level"). The user's system-settings choice is respected, not fought. |
| System ringtone changed | Default channel uses the indirection URI — follows automatically. If [VERIFY] fails, fall back to versioned channels on detected change. |
| Unknown-tone change requested | New channel ID, old deleted (§4.3). Registry keeps the mapping. |
| Nope-Mode window active / any DND | The notification pipeline filters the ring. XX-Dialer logs its own verdict as computed; Recents shows the call. No fighting, no detection needed. |
| Starred number is also blocked | Blocked wins; log entry flagged `⚠ starred number blocked` (§6). |
| Second call while in-call | Heads-up only, no FSI over the active call; hold-and-answer on accept. The documented third-party weak spot on Pixels — first-class test target (§16). |
| Reboot mid-anything | Nothing to recover — policy is stateless (D3). Channels persist; the mirror refreshes on next use. |
| Clock/timezone change | Next call evaluates against the new wall clock. The emergency 24 h marker stores elapsed-realtime alongside wall time; the stricter reading wins. |
| Outgoing emergency call | `emergency_marker` set → all screening and silencing bypassed for 24 h (R10). The platform independently bypasses its whole filter graph for emergency-mode calls and suppresses blocking for 2 h (source-verified) — belt and suspenders, ours is the longer belt. |
| Another app takes `ROLE_CALL_SCREENING` | XX-Dialer's screener silently stops running (§4.1). Detected on foreground; Setup names the app and offers the role request. Never show spam protection as active while the role is held elsewhere. |
| InCallService component disabled somehow | Platform revokes the role and closes the app; Setup on next launch starts from the role step. |
| Second call while another is already ringing | Platform auto-misses it (`MAXIMUM_RINGING_CALLS = 1`) — logged, not ours to handle. |
| Withheld number | Never reaches the screener; ring-time hidden-caller policy applies (§6). If the platform's own "block Unknown" setting is on, it never reaches us at all — Setup detects and says so. |
| Contact deleted while in Business tier | Pruned on next sweep, logged (§9). |
| Observe week ends | Enforcement is **offered** (notification + Rules banner), never flipped silently. A user who ignores it observes forever — annoying, honest. |
| Clock moved backwards while Expecting-a-call active | Same guard as the emergency marker: `bypass_until` further out than the maximum duration → cancel the bypass. |
| Number normalization mismatch | Matching is on E.164; raw string kept in the log for forensics. A normalization failure classifies as unknown — noisy, never lossy. |

The failure direction is uniform: **every failure rings more, never less.**

---

## 16. Testing

`RingPolicy`, `Windows`, and `E164` are pure JVM and carry the weight.

- The full §6 truth table — every row, every precedence conflict
  (starred+blocked, starred+business, business outside window + repeat
  caller, hidden + each policy setting).
- Window boundaries: 08:59:59 / 09:00:00 / 16:59:59 / 17:00:00 exact; the
  business 19:00 edge; days-mask boundaries; a wrapping window; DST
  spring-forward and fall-back inside a window.
- Repeat-caller: 14:59 and 15:01 after the first call; a repeat that is
  itself blocked (block still wins); a repeat piercing each Silence flavor —
  Business after hours, unknown out-of-window, hidden-policy, silence-pattern.
- Recent-outgoing: 47:59 and 48:01 after the dial; an outgoing call to a
  number that is *also* silence-patterned (recent-outgoing sits above, rings).
- Expecting a call: active/expired boundary; a Block during the bypass
  (still blocks); expiry mid-ring is not a case — the verdict is taken once.
- Observe mode: every verdict row asserts identical `decide()` output with
  the gate open and closed; log rows marked `observed`; enforcement never
  flips without an explicit user action.
- Emergency marker: inside/outside 24 h; clock moved backwards (elapsed-time
  guard).
- E164: `+1` forms, national forms, `00` prefixes, short codes, garbage —
  asserted against the "one caller, one identity" property.
- Instrumented, on `caiman`: WS0 probes (§17) re-run as tests; role
  acquisition; channel ring end-to-end with each tier; screening timeout
  fail-open (a deliberately slow responder); Contact Scopes matrix (full /
  partial / empty grants); **call waiting hold/swap** — the documented
  third-party failure area gets a dedicated pass; Room migrations.

**A workstream with failing tests is not done.**

---

## 17. Build order

WS0 exists because four platform facts this design leans on are unverified
on the target OS. They cost a day and de-risk everything; the Nope-Mode
lesson (one factory reset to learn the provisioning window) is not getting
re-learned here.

| WS | Scope | Gate |
|---|---|---|
| 0 | **Probe APK**, throwaway: take `ROLE_DIALER` + `ROLE_CALL_SCREENING` on GrapheneOS (Restricted Settings behavior?); ring via CallStyle channel with `IN_CALL_SERVICE_RINGING` (does the `DEFAULT_RINGTONE_URI` indirection track the system tone?); does Contact Scopes filter `PhoneLookup` at call time?; what STIR statuses does the real carrier deliver? | **Everything.** If channel-ringing fails, D2 falls back to self-playing `USAGE_NOTIFICATION_RINGTONE` audio gated by `matchesCallFilter()` + ringer mode — a worse app, and better known now. |
| 1 | Skeleton — gradle, manifest, tokens vendored, fonts, icon | |
| 2 | `core/` — RingPolicy (incl. repeat-pierce, recent-outgoing, bypass, observe gate), Windows, E164, full JVM test suite | The §6 table passes before any Android code consumes it |
| 3 | Default-dialer plumbing — role request + Setup detection, InCallService, place/receive with a bare-bones screen, call log | Dogfood-able for basic calls |
| 4 | FactStore — mirror, observer, tier storage | |
| 5 | Screening service + blocklist + patterns | Spam block live |
| 6 | Ringer — channels, registry, verdict routing, observe gate last-in-chain | The headline feature: unknown tone + windows live; observe provably changes only the gate |
| 7 | Incoming + in-call UI (CNAP line), call waiting, audio routing | **Daily-driver gate: replace the stock dialer for real, in observe mode** |
| 8 | Recents + Keypad + People | |
| 9 | Rules screen (enforcement switch, tallies, silenced-notification policy) + Test-a-number + notification actions/batching + reason strings everywhere | R7 complete |
| 10 | Backup/restore JSON; Expecting-a-call + QS tile; polish (hidden policy, repeat toggle, neighbor preset, import) | v1 |

WS2 before WS3, same reasoning as Nope-Mode: the logic that must be correct
has no Android dependencies — build it against tests first, in isolation.
UI is deliberately late; WS3–6 produce a functionally complete headless
policy dialer with an ugly screen, which is the correct order for finding
out whether the thing works.

---

## 18. Open decisions

| # | Question | Default until overruled |
|---|---|---|
| O1 | Views (family) vs Compose (Vitals precedent)? Operator call, same as Vitals D4. | Views (D7) |
| O2 | Ship a screening-only degraded mode as a supported configuration (the SpamBlocker adoption path — rules without switching dialers, minus tone control), or require the full role? | Supported but undocumented-in-marketing: it exists as the R9 degradation anyway |
| O3 | Unknown-tone default: shipped original tone vs silent-by-default (tone opt-in)? | Shipped tone — R4 asks for a ringtone, not a vibration |
