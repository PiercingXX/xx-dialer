XX-Dialer visual voicemail (Skippy)

Repo: `/media/Working-Storage/GitHub/Phone-Projects/android/xx-dialer`
Package: `com.piercingxx.xxdialer`
Target: Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37, live SIM.

Existing product: default dialer + call screening. Tabs today: Recents · Keypad · People · Rules. Long-press `1` on the keypad already dials `voicemail:` via `CallManager.placeVoicemail`. **That path stays forever**, including when visual voicemail is off.

Skippy reads `design.md`, `todo.md`, `TabBar.kt`, `RulesActivity.kt`, `SettingsRepository.kt`, `CallManager.kt`, `KeypadActivity.kt`, and `app/build.gradle.kts` (`verifyNoInternet`) before writing code.

---

## Product: opt-in visual voicemail

Visual voicemail is **off by default**. A user who never wants the dialer on the network leaves the toggle off. A user who wants an inbox turns it on.

**Settings toggle** (Rules screen — that is this app’s settings):

Label: **Visual voicemail**
Annotation: **On: a Voicemail tab, carrier mailbox over the network. Off: long-press 1 only. Default off.**

| Toggle | Tab | Telephony | Network |
|---|---|---|---|
| **Off (default)** | Voicemail tab **absent** (not hidden — gone). Hide-chip for it is gone too. | `VisualVoicemailService` methods **return immediately** (`task.finish()`). No ACTIVATE SMS. No IMAP. No sockets. | Zero use of INTERNET even if the OS granted Network |
| **On** | Voicemail tab **appears** (hideable like Recents). Functionality runs. | Cell-connect / VVM SMS handled. ACTIVATE if needed. IMAP sync. | IMAP only to the host in the carrier STATUS SMS |

Turning **off** after it was on: send DEACTIVATE SMS if we previously activated, unregister the voicemail source, drop or stop displaying provider rows from our package, remove the tab, never open another socket.

`SEND_TO_VOICEMAIL` on a contact (ring policy row 2) is unrelated — that is “send this caller to the mailbox,” not the inbox UI.

---

## INTERNET permission (be honest in the UI)

Android cannot add `INTERNET` at runtime. The APK **declares** `INTERNET` + `ACCESS_NETWORK_STATE` so IMAP can work **after** the toggle. Declaring it is not the same as using it.

- **In-app toggle off** = this process never opens a network socket. That is the product control.
- **GrapheneOS App info → Network** = OS kill switch. If the user revokes Network, the toggle may be on but IMAP fails; the Voicemail tab says so and points at long-press 1.
- Setup / Rules copy must say both: *Visual voicemail talks to your carrier’s mailbox. Leave it off if you do not want this app on the network. GrapheneOS can also revoke Network for this app.*

Replace `verifyNoInternet` with **`verifyVvmInternetOnly`**: INTERNET is allowed; fail the build if any *other* unexpected network permission appears; unit-test that the IMAP stack is the only socket site (package prefix `…vvm` / `…voicemail`). Update README: screening stays local; VVM is the one opt-in network feature.

Default off is how “each user decides.” Do not phone home, do not use INTERNET for screening, contacts, or telemetry.

---

## Locked decisions

| ID | Decision |
|---|---|
| D1 | Toggle **off** by default. Key e.g. `SettingsRepository.KEY_VISUAL_VOICEMAIL` = `"0"` in `designDefaults`. Backup JSON whitelist the key. |
| D2 | Toggle **on** shows the Voicemail tab **and** starts the VVM client. One setting, two effects. No tab without functionality, no IMAP without the tab. |
| D3 | `VoicemailContract` is the store. No Room table of audio. |
| D4 | Implement `VisualVoicemailService`. When the toggle is off, every callback no-ops and finishes. Telephony may still bind the default dialer; we must not activate. |
| D5 | Protocols: OMTP 1.1, CVVM, VVM3 as CarrierConfig `KEY_VVM_TYPE_STRING`. IMAP credentials **only** from STATUS SMS. Prefer TLS if offered; never log `pw`. |
| D6 | Cellular data when `KEY_VVM_CELLULAR_DATA_REQUIRED_BOOLEAN`. |
| D7 | If `KEY_CARRIER_VVM_PACKAGE_NAME_STRING` is installed, do not fight it unless the user enabled our toggle anyway (then we are the default dialer and own the bind). |
| D8 | Fail honest: empty / activating / no carrier config / network denied / IMAP failed. Always keep long-press 1. |
| D9 | No greeting editor, no PIN UI, no cloud transcription, no archive. Carrier `text/plain` transcription column is OK to show. |

---

## Cleanroom (Skippy does this himself)

**Study:** [AOSP VVM](https://source.android.com/docs/core/permissions/voicemail), `VoicemailContract`, `VisualVoicemailService`, `VisualVoicemailSms`, `TelephonyManager.setVisualVoicemailSmsFilterSettings` / `sendVisualVoicemailSms`, CarrierConfig `KEY_VVM_*`, GSMA OMTP VVM spec. AOSP Dialer `java/com/android/voicemail` is **Apache-2.0** — read for STATUS/IMAP algorithms, **reimplement** in `com.piercingxx.xxdialer.vvm`. NOTICE if derived. Do not copy Dagger, layouts, icons, loggers.

**Never open:** Fossify/Simple/Koler/Emerald (GPL), Google Phone APK, carrier VVM APKs.

---

## Permissions

android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.ADD_VOICEMAIL android.permission.READ_VOICEMAIL android.permission.WRITE_VOICEMAIL android.permission.SEND_SMS          // ACTIVATE / DEACTIVATE / query

`CHANGE_NETWORK_STATE` only if cellular-bind cannot be done without it.
`BIND_VISUAL_VOICEMAIL_SERVICE` is on the **service** tag (others bind to us), not uses-permission.

Confirm on GrapheneOS whether ROLE_DIALER auto-grants ADD_VOICEMAIL; if not, Setup asks when the user first turns the toggle on (not at first launch).

---

## Architecture

Rules: Visual voicemail [ off | on ]     ← single gate, default off
        │
        ├ off → Tab.VOICEMAIL omitted from TabBar
        │       XxVisualVoicemailService: finish immediately
        │       no SMS, no IMAP, no sockets
        │
        └ on  → Tab.VOICEMAIL visible
                onCellServiceConnected → ACTIVATE if CarrierConfig valid
                onSmsReceived → STATUS/SYNC → creds + sync
                IMAP → VoicemailContract
                VoicemailActivity list/play/delete
                ACTION_FETCH_VOICEMAIL on play if !HAS_CONTENT

`VisualVoicemailTask.finish()` always. IMAP off main thread (WorkManager or app coroutine + WAKE_LOCK). Encrypted prefs for IMAP password. Not in BackupJson.

Filter settings registered only while toggle is on; cleared when turned off.

---

## UI

**Rules row:** switch “Visual voicemail” + annotation above. Turning on the first time: one line that this uses the carrier network. No second confirm dialog unless you already have a confirm pattern in Rules.

**Tab:** `Voicemail`, fifth tab, hideable. When toggle is off, `TabBar` must not show a blank slot.

**Tab states (only if toggle on):** off-is-impossible here; no carrier config; activating; network revoked; IMAP error + Retry; empty; list (name via ContactMirror/PhoneLookup, time, duration, unread). Detail: play/pause, speaker, call back, delete, transcription if present.

**Notification:** only if toggle on. Channel `voicemail_v1`, SECRET, tap → tab.

Theme: existing dialer theme-sync. Views, not Compose.

---

## Tests

**JVM:** STATUS/SYNC parsers; `shouldRunVvm(toggle)` false ⇒ no activate; toggle off after on ⇒ deactivate requested; IMAP host must equal STATUS host.

**Manifest / source:** service + BIND + action; INTERNET declared; `verifyVvmInternetOnly`; Tab.VOICEMAIL; TabBar omits it when setting is 0; long-press 1 still `placeVoicemail`; default key is off.

**Device (do first):**
`adb shell dumpsys carrier_config | grep -i vvm`
Record type/destination/port/cellular-required. Empty type ⇒ ship client + “this SIM does not publish VVM”; do not fake an inbox.

**Live SIM after code:**

1. Fresh install: no Voicemail tab, logcat no IMAP/ACTIVATE.
2. Toggle on: tab appears; ACTIVATE on cellular.
3. Leave a voicemail from another phone; row; play; delete.
4. Toggle off: tab gone; no further sockets; long-press 1 still works.
5. GrapheneOS revoke Network, toggle on: tab explains failure, no crash.

---

## Workstreams

| WS | Scope |
|---|---|
| V0 | CarrierConfig dump on the Pixel; write findings in `todo.md` |
| V1 | Permission + `KEY_VISUAL_VOICEMAIL` default 0 + `verifyVvmInternetOnly` + README |
| V2 | Rules toggle + TabBar show/hide (no IMAP yet) |
| V3 | `XxVisualVoicemailService` gated on the toggle; ACTIVATE/DEACTIVATE |
| V4 | STATUS/SYNC parser + encrypted creds |
| V5 | IMAP → VoicemailContract |
| V6 | Fetch audio + player |
| V7 | List/detail UI |
| V8 | Delete/seen upload |
| V9 | New-voicemail notification |
| V10 | Error/empty copy + live-SIM dogfood |

V2 can land before IMAP so the operator can see the tab appear/disappear.

---

## Stop conditions

- IMAP or ACTIVATE while the toggle is off → reject.
- Socket to a host not in the last STATUS SMS → reject.
- Removing long-press 1 → reject.
- Copying AOSP Dialer files verbatim, or any GPL dialer → reject.
- Using INTERNET for anything except VVM IMAP → reject.



---

## VVM findings (V0 — device dump)

**Carrier-config dump: BLOCKED.** No adb device is attached in the build sandbox, so
`adb shell dumpsys carrier_config | grep -i vvm` could not be run against the target SIM.
See `docs/vvm-carrier-config.txt` for the BLOCKED marker. Re-run V0 on a host with the
Pixel 9 Pro connected to capture `KEY_VVM_TYPE_STRING` / destination / port / cellular-required.

**Empty-type decision (recorded now):** if the carrier publishes no
`KEY_VVM_TYPE_STRING` (empty `vvm_type`), ship the client anyway and surface the honest
"this SIM does not publish VVM" state from D8 — do not fake an inbox. The client must
tolerate an empty `vvm_type` and never activate or open IMAP without a real carrier
config.
