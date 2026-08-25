# XX-Dialer — Research Notes

Findings behind [design.md](../design.md), from two research passes
(2026-08-23): platform mechanics (Android docs + AOSP `android17-release`
source), and the dialer/blocker landscape (listings, help pages, reviews,
GrapheneOS forum — **no GPL source opened**, per the cleanroom rule).

---

## Platform facts (source-verified)

- **Dialer-owned ringing:** `TelecomManager.METADATA_IN_CALL_SERVICE_RINGING`
  (API 24). Verified in Telecom's `Ringer.getRingerAttributes()` —
  `letDialerHandleRinging` short-circuits system ringtone *and* vibration;
  unchanged on `android17-release`. Per-call tone choice is therefore real,
  decided in `onCallAdded` with no deadline.
  https://developer.android.com/develop/connectivity/telecom/dialer-app
- **Role eligibility** (from `PermissionController` `roles.xml`): `ACTION_DIAL`
  activity without data + with `tel:`, exported `InCallService` with
  `IN_CALL_SERVICE_UI`; `IN_CALL_SERVICE_CAR_MODE_UI` metadata is a
  disqualifier. Role auto-grants phone/contacts/notifications sets.
  `ACTION_CHANGE_DEFAULT_DIALER` dead since Q — use `RoleManager`.
- **One screener only:** `CallsManager.setUpCallFilterGraph()` picks the
  `ROLE_CALL_SCREENING` holder if it differs from the default dialer, else
  the default dialer. Hold both roles.
- **Contacts bypass refuted for us:** `CallScreeningServiceFilter` skips
  contact calls only for user-chosen screeners; `PACKAGE_TYPE_DEFAULT_DIALER`
  passes `hasReadContactsPermission()` unconditionally. Verified on 17.
- **Screening timeout:** `CALL_SCREENING_FILTER_TIMEOUT = 5000` ms; timeout →
  call proceeds (fail-open). Screening `Call.Details` has only 5 fields.
  Withheld/payphone presentation never delivered to the screener.
- **`setSilenceCall` (29):** call still sent to the dialer, answerable;
  stamps `USER_MISSED_CALL_SCREENING_SERVICE_SILENCED` — reason we silence at
  ring time instead. `setSkipCallLog` effectively carrier-only (code vs doc
  discrepancy). Never compile against `setRejectedAsMissed` — unreleased
  through 37.1.
- **BlockedNumberContract (24):** default dialer + default SMS app may
  read/write; insert with `COLUMN_ORIGINAL_NUMBER`, no update op. Platform
  rejects blocked calls before any screener and keeps data across role loss.
  Management UI: `TelecomManager.createManageBlockedNumbersIntent()`.
- **STIR/SHAKEN:** `Call.Details.getCallerNumberVerificationStatus()` (30) →
  `Connection.VERIFICATION_STATUS_{NOT_VERIFIED,PASSED,FAILED}`. Only FAILED
  carries signal; NOT_VERIFIED dominates off-IMS and means nothing.
  https://developer.android.com/develop/connectivity/telecom/dialer-app/prevent-spoofing
- **Emergency:** incoming emergency-mode calls bypass the entire filter
  graph (`PROPERTY_EMERGENCY_CALLBACK_MODE`, `PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL`);
  callback window flagged via `Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS`
  (26); platform blocking suppressed 7200 s post-emergency. Outgoing must use
  `TelecomManager.placeCall()`, never `ACTION_CALL` (InCallService javadoc).
- **DND while self-ringing:** `Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB`
  (34) pre-stamped; `NotificationManager.matchesCallFilter()` public since
  33 (`READ_CONTACTS` suffices). Channel-based ringing gets DND handling
  from the platform. Android 15+: apps can't set global DND — irrelevant,
  we only read.
- **Call log:** Telecom's `CallLogManager` writes all normal entries; the
  dialer only mutates (`WRITE_CALL_LOG`) for user actions. Missed-call
  notification can be owned via `ACTION_SHOW_MISSED_CALLS_NOTIFICATION`.
- **Notification channels:** sound immutable after creation;
  delete-and-recreate same ID resurrects old settings → versioned channel
  IDs. CallStyle (31) has system-owned Answer/Decline labels and the
  status-bar chip (since 12). FSI is special app access on 14+ —
  handle `canUseFullScreenIntent() == false`.
  https://developer.android.com/develop/connectivity/telecom/voip-app/notifications
- **Restricted Settings (CDD 15+ §9.8):** `ROLE_DIALER` is a Restricted
  Setting for sideloaded apps — user must clear it from App Info
  (`EnhancedConfirmationManager`). GrapheneOS enforcement unconfirmed; forum
  evidence of the gate hitting ACR Phone:
  https://discuss.grapheneos.org/d/21743-how-to-allow-a-third-party-phone-app
- **Android 17 misc:** `IS_BUSINESS_CALL`/`ASSERTED_DISPLAY_NAME` (35) are
  carrier-asserted business-caller flags — naming collision with our tier;
  per-`PhoneAccount` ringtones (37); contacts provider strictness at
  targetSdk 37 (account columns off `Data`, strict SQL — `PhoneLookup`
  unaffected); `CallAudioState` deprecated at 34 → `CallEndpoint`;
  call-waiting limits `MAXIMUM_RINGING_CALLS = 1`.

## GrapheneOS facts

- Stock dialer = fork of AOSP `packages/apps/Dialer`; spam framework is a
  `SpamStub` (always "not spam"). Role policy unpatched; third-party default
  dialers work. https://grapheneos.org/features
- **Exposed "block callers not in contacts"** (commit flipping
  `show_option_to_block_callers_not_in_contacts` to true): kills unknown
  callers upstream with `BLOCK_REASON_NOT_IN_CONTACTS`; setting unreadable —
  detect via call-log rows.
- **Contact Scopes:** app believes it has Contacts permission; reads return
  the granted subset (possibly empty), photos withheld on single-contact
  grants, account identity hidden, **all writes blocked**. Role auto-grant
  does not defeat it. https://grapheneos.org/usage
- `CAPTURE_AUDIO_OUTPUT` allowlisted to `com.android.dialer` only → call
  recording impossible for a third-party dialer on this OS.
- No `INTERNET` declared beats GrapheneOS's per-app Network toggle — nothing
  to audit. Community context: https://discuss.grapheneos.org/d/27726 ·
  https://discuss.grapheneos.org/d/3412-enabling-call-screening

## Landscape facts that shaped the design

- **Google deleted the granularity (May 2023):** Call Screen's 4-category ×
  3-action matrix (Spam / Faked / First-time / Hidden × Ring / Silently
  decline / Screen) collapsed to Basic/Medium/Maximum, to public criticism.
  https://9to5google.com/2023/05/29/google-pixel-call-screen-settings-change/
- **Google Phone IA (Aug 2025, M3E):** three tabs, keypad as center tab (FAB
  dead), contacts demoted to a drawer, favorites as a collapsible carousel
  above the log, filter chips surfaced; call-log grouping removed and users
  revolted → grouping is a toggle here.
  https://9to5google.com/2025/08/21/google-phone-material-3-expressive-redesign/
- **iOS:** Silence Unknown Callers = silenced-not-rejected + Recents entry;
  emergency call disables screening 24 h; repeat-caller 3 min (Android DND:
  15 min). https://support.apple.com/en-us/111106
- **No app shows *why* a call was silenced.** Across every surveyed blocker,
  no per-call reason string exists — YACB's rating notification is the
  closest. The reason string is the differentiator.
- **False positives cluster on medical/school/delivery** (Cloaked n=1,005,
  2026: 2 in 3 missed a legitimate call; medical 45%). Mitigations shipped
  before blocking: repeat-caller override, contacts exemption on patterns
  (Should I Answer / Hiya precedent), one-tap recovery from the log.
- **Rule-priority publication:** only Call Control documents its precedence;
  its digit-slider mask builder (`425-555-XXXX`) is the pattern-authoring
  model adopted here.
  https://help.callcontrol.com/en_US/call-control-android/block-similar-numbers-mask-on-android
- **Near-neighbors (never opened):** SpamBlocker (MIT per F-Droid/GitHub —
  verify LICENSE before ever reading; community's standing GrapheneOS
  recommendation), Silence (GPL), Saracroche (GPL, on-device DB), Fossify
  Phone (GPL; tab-hiding beloved; the silent-ring half-configured-role bug
  report), Should I Answer (proprietary; offline DB + contacts exemption).
- **Licensing map:** every live FOSS dialer is GPL-3.0 (Fossify, Emerald,
  Koler); YACB is AGPL (doubly off-limits); AOSP Dialer is Apache-2.0 but
  deprecated upstream; Simple Mobile Tools sold to ZipoApps Dec 2023 and
  went proprietary+ads — the argument that a license is not a durable
  privacy guarantee; the OS-verifiable no-INTERNET claim is.

## Unverified / watch list

- GrapheneOS behavior of the Restricted Settings gate (WS0).
- `DEFAULT_RINGTONE_URI` indirection resolving at play time in a channel (WS0).
- Contact Scopes filtering of `PhoneLookup` at call time (WS0).
- Carrier-delivered STIR statuses on the real SIM (WS0).
- SpamBlocker LICENSE file text (MIT vs one GPLv3 claim) — only matters if
  it is ever to be read, which it currently is not.
- Whether Groups has a ringtone column was never confirmed — moot under D4.
