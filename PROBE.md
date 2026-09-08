# PROBE.md — WS0 on-device procedure

Target: Pixel 9 Pro (`caiman`), GrapheneOS. The probe APK
(`com.piercingxx.xxdialer.probe`) is a throwaway that answers four platform
questions before anything else is trusted on them.

## Why WS0 exists

todo.md rule #1: WS0 is not optional. design.md §4 rests on four facts that
are source-verified on AOSP but unproven on the GrapheneOS build in hand,
each marked **[VERIFY]**:

| # | Flag | Where |
|---|---|---|
| V1 | Does GrapheneOS enforce, relax, or modify the Restricted Settings gate on `ROLE_DIALER` for sideloaded apps? | §4.1 |
| V2 | Does a CallStyle notification on an `IN_CALL_SERVICE_RINGING` channel ring, and does `Settings.System.DEFAULT_RINGTONE_URI` indirection follow system-ringtone changes at play time? | §4.3 |
| V3 | What STIR/SHAKEN verification statuses does the real carrier deliver across contact / unknown / withheld / spam callers? | §4.4 |
| V4 | Does Contact Scopes filter `PhoneLookup` at call time? | §4.5 |

The whole app leans on these. If channel-ringing fails, D2 falls back to
self-played audio (§17) — better known before WS3 than after v1. Nope-Mode's
factory reset stays a lesson, not a repeat.

## Build and install

This is **not a suite app**. `testOnly`, no `LAUNCHER` icon. Do not run
`./gradlew installDebug` at the repo root — that used to drop XX-Probe on
the home list. `:probe:installDebug` is disabled.

```
./gradlew :probe:installProbe
adb shell am start -n com.piercingxx.xxdialer.probe/.MainActivity
```

Equivalent by hand (`-t` is required because the APK is test-only):

```
./gradlew :probe:assembleDebug
adb install -t -r probe/build/outputs/apk/debug/probe-debug.apk
adb shell am start -n com.piercingxx.xxdialer.probe/.MainActivity
```

Before installing, snapshot the current role holders so teardown can restore
them exactly:

```
adb shell cmd role holders android.app.role.DIALER
adb shell cmd role holders android.app.role.CALL_SCREENING
```

Have the second phone ready — §2 and §3 need real incoming calls. The probe
holds no INTERNET permission; every byte leaves the device only by your own
hand (share sheet).

Every log line is ISO-timestamped and self-contained:
`2026-08-23T09:18:00.123-04:00 [section] key=value ...`. Lines mirror to
logcat under tag `XXProbe` (`adb logcat -s XXProbe`) and append to
`files/probe_log.txt`.

---

## 1 · Roles and the Restricted-Settings refusal signature (V1)

On the XX-Probe screen, section **1 · ROLES**:

1. Press **Request ROLE_DIALER**. Accept the system dialog.
2. Press **Request ROLE_CALL_SCREENING**. Accept.
3. Press **Audit roles + device**.

Expected shapes when all is well:

```
[roles] role=android.app.role.DIALER request_result=RESULT_OK held_after_request=true
[roles] outcome=granted role=android.app.role.DIALER
[audit] role=android.app.role.DIALER held=true available=true holders=com.piercingxx.xxdialer.probe
[audit] default_dialer_package=com.piercingxx.xxdialer.probe ...
```

**The refusal signature to watch for** (the gate this test exists for):

```
[roles] role=android.app.role.DIALER request_result=RESULT_OK held_after_request=false
[roles] outcome=not_held role=android.app.role.DIALER signature=request_ok_but_not_held_or_user_cancelled
[roles] hint=App_Info -> three_dot_menu -> Allow_restricted_settings then re-request the role
```

`request_result=RESULT_OK held_after_request=false` — or `RESULT_CANCELED`
with the dialog never having rendered a normal decline — is the gate biting.
The screen shows the walk-through hint (`restricted_settings_hint`): App Info
→ ⋮ → *Allow restricted settings*, then re-request. Record which path it took;
that single line decides how much of Setup needs to be support documentation.

Then uninstall-and-reinstall once with roles already granted elsewhere absent:
confirm a sideload-fresh install hits the same gate (or doesn't) reproducibly.
Note also whether the role grant auto-carried the phone/contacts/notification
permission set (§4.1) — `[perms] event=state permission=... granted=true` rows
in the audit answer that without a prompt cascade.

Paste the roles block into design.md **§4.1**, replacing the [VERIFY] flag.

## 2 · Channel ring + DEFAULT_RINGTONE_URI indirection (V2)

Section **2 · CHANNEL RING**. Keep ROLE_DIALER held from §1 — the
end-to-end leg needs it.

1. Manual leg: press **Post CallStyle test ring now**
   (`source=manual_button`). A CallStyle incoming-call notification posts on
   channel `ring_probe_v1` (HIGH, sound =
   `Settings.System.DEFAULT_RINGTONE_URI`, vibration on).
   Expected log shape:

   ```
   [channel_ring] event=channel_ensured channel_id=ring_probe_v1 requested_importance=HIGH requested_sound=content://settings/system/ringtone audio_usage=USAGE_NOTIFICATION_RINGTONE vibration=true note=...
   [channel_ring] event=call_style_posted source=manual_button channel_id=ring_probe_v1 can_use_full_screen_intent=true expectation=ring with current Settings-Sound-Phone ringtone via DEFAULT_RINGTONE_URI indirection
   ```

   Question 1: does it ring, with the current system tone?

2. End-to-end leg: have the second phone call this one. The probe's
   InCallService logs the incoming call and posts the same notification
   (`source=incoming_call_ringing`). Answer or decline; note the taps:

   ```
   [incall] event=call_added state=RINGING handle=+1555... presentation=ALLOWED caller_display_name=none verification_status=... silent_ring_requested=false emergency_callback_time_present=false
   [channel_ring] event=user_tap tap=answer
   ```

   Question 2: does a real call ring through the channel?

3. Indirection leg: change **Settings → Sound → Phone ringtone** to something
   unmistakable, call again. Question 3: did the tone FOLLOW without any new
   channel being minted?
   `[channel_ring] event=channel_state ... stored_sound=content://settings/system/ringtone`
   should still show the indirection URI before and after.

Also exercise volume-down mid-ring — expect
`[incall] event=on_silence_ringer trigger=user_volume_press action=ringer_stopped`.

If ringing fails here, D2 falls back to self-played
`USAGE_NOTIFICATION_RINGTONE` audio gated by `matchesCallFilter()` +
ringer mode (§17). Paste the block into design.md **§4.3**.

## 3 · STIR status logging (V3)

No button — the status rides along on every call. With ROLE_DIALER +
ROLE_CALL_SCREENING held, place/receive calls from each scenario and read
`verification_status=` out of the `[screening]` and `[incall]` lines:

| Scenario | Expectation |
|---|---|
| Contact (saved number) | screening row present — default dialer sees contacts unconditionally (§4.2); status per carrier |
| Unknown mobile | `NOT_VERIFIED(0)` dominates unless carrier+VoLTE say otherwise |
| Withheld caller | **no** `[screening]` row — restricted presentation never reaches the screener (§4.2); only `[incall] presentation=RESTRICTED` |
| Known spam line | whatever the carrier attests — `FAILED(2)` is the signal; `NOT_VERIFIED(0)` must stay non-suspicious |

Line shapes:

```
[screening] event=screened direction=INCOMING handle=+1555... presentation=ALLOWED verification_status=PASSED(1) silent_ring_requested=false note=contacts calls reach the screener only when it is also the default dialer
[screening] event=screened direction=INCOMING handle=+1555... presentation=ALLOWED verification_status=FAILED(2) ...
```

Record which statuses actually appear per scenario. Only pass/fail is
exposed, not FCC attestation levels (§4.4). Paste into design.md **§4.4**.

## 4 · Contact Scopes PhoneLookup filtering (V4)

Prerequisite: contact **XX Probe 5550100** exists with number **555-0100**
(section 3 · CONTACT SCOPES / PHONELOOKUP says so on-screen).

1. Full grant: press **Run PhoneLookup probe** (`run=initial`). Expect rows:

   ```
   [phone_lookup] run=initial filter_form=555-0100 rows=1
   [phone_lookup] run=initial form=+15550100 row=0 display_name=XX Probe 5550100 normalized_number=+15550100 photo_uri=absent_or_null columns_non_null=[...]
   ```

2. Limited grant: **Settings → Apps → XX-Probe → Contacts** — narrow scopes
   to a subset excluding XX Probe 5550100. Press **Re-run after Contacts
   scope change** (`run=scope_recheck`). Compare `rows=` counts.
   Scopes filtering `PhoneLookup` ⇒ the scoped-out number now returns
   `rows=0`, i.e. scoped-out callers classify unknown at call time — the
   correct degradation (§4.5).

3. Empty grant: remove all scopes, re-run. `rows=0` everywhere, no crash, no
   re-prompt loop. This is WS4's empty-grant exit criterion observed at the
   provider level.

Each run also logs a `contacts_data` row counting non-null `account_name`
cells — evidence for the targetSdk-37 Data-table tightening noted in §4.5.

Paste the three runs into design.md **§4.5**.

## 5 · Log export

Two transports, both offline:

- On-device: press **Share probe_log.txt (offline ACTION_SEND)** — share
  sheet via FileProvider
  (`[export] event=share_uri_issued authority=com.piercingxx.xxdialer.probe.fileprovider bytes=... transport=ACTION_SEND chooser; app holds no INTERNET permission`).
- Over USB:

  ```
  adb shell run-as com.piercingxx.xxdialer.probe cat files/probe_log.txt
  ```

Paste verbatim blocks into design.md §4.x — the lines are self-contained by
design.

## 6 · Teardown

1. Uninstall the probe — both roles release automatically:

   ```
   adb uninstall com.piercingxx.xxdialer.probe
   ```

2. Restore the stock dialer (AOSP Dialer, `com.android.dialer`, per §4.5):
   Settings → Apps → Default apps → Phone app. Shell equivalent, if
   PermissionController permits it from adb:

   ```
   adb shell cmd role add-role-holder android.app.role.DIALER com.android.dialer
   ```

   If the pre-install snapshot showed a different holder, restore that
   package instead.
3. Verify ringing returned: second phone calls again — stock tone plays.
4. Cross-check:

   ```
   adb shell cmd role holders android.app.role.DIALER
   adb shell cmd role holders android.app.role.CALL_SCREENING
   ```

   Both must list the pre-install state, not the probe.

---

## Answers → design.md map

| Probe | [VERIFY] flag answered | Paste into |
|---|---|---|
| §1 Roles + refusal signature | V1 — Restricted Settings gate on GrapheneOS | design.md §4.1 |
| §2 Channel ring + indirection | V2 — `DEFAULT_RINGTONE_URI` resolves at play time | design.md §4.3 |
| §3 STIR statuses | V3 — what the carrier delivers | design.md §4.4 |
| §4 PhoneLookup under Scopes | V4 — Scopes filters `PhoneLookup` at call time | design.md §4.5 |

Not covered by this probe: the fifth **[VERIFY]** at design.md §10 (dual-SIM
per-`PhoneAccount` ringtones vs channel sounds) — needs a dual-SIM device;
stays open until then. When V1–V4 are pasted, todo.md WS0 flips from
BUILT-AWAITING-DEVICE to DONE.
