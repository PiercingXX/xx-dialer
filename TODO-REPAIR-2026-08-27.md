# xx-dialer — repair TODO

Reviewed 2026-08-27. **Do not implement from this file until a repair mill is
explicitly tasked.** This is an inventory, not a contract.

- Repo: `/media/Working-Storage/GitHub/Phone-Projects/android/xx-dialer`
- GitHub: `PiercingXX/xx-dialer` (`https://github.com/PiercingXX/xx-dialer`)
- Package: `com.piercingxx.xxdialer`
- Target: Pixel 9 Pro (`caiman`), GrapheneOS
- `origin/main`: `3dc61c60b12a88f7837138e2b2408476003c6e79` — `Deliver xx-dialer-v6`
- Working tree: clean except untracked `contracts/`
- Product brief (do not replace): [`todo.md`](todo.md)

**Bottom line:** mill merged V0–V6 as DONE. The screening dialer, `ROLE_DIALER`,
theme-sync, long-press `1` → `CallManager.placeVoicemail`, and the off-by-default
toggle/tab shell are real. **Visual voicemail as a mailbox is not.** There is
no IMAP client, no ACTIVATE/DEACTIVATE SMS, no SMS filter registration, and no
`ACTION_FETCH_VOICEMAIL` handler. The JVM suite pins seams and stubs. Nagatha
PASSed every slice because the kit does not read Kotlin behavior and the
task verifies were boolean/source tests, not the workstream done-when.

---

## GitHub / CI if any

| Check | Result |
|---|---|
| `.github/workflows` | **Absent.** No workflow files in the repo. |
| `gh api repos/PiercingXX/xx-dialer/actions/workflows` | `total_count: 0` |
| `gh api …/actions/runs` | `0` |
| Check-runs on `3dc61c6` | `total_count: 0` |
| Commit status on `main` | `state: pending`, `statuses: []`, `total_count: 0` |
| `gh issue list` | empty |
| `gh pr list` | empty |
| `gh run list` | empty |

Laundry-bot **skipped GitHub push** of the estate branches
(`laundry-bot/queue-xx-dialer-v*`). Deliver commits still landed on
`origin/main` via the mill merge path. There is **no GitHub Actions failure
to fix** because there is no CI. That is itself a gap: VVM “green” was mill
GATE + Nagatha, never GitHub.

**This session’s GATE:** `./gradlew testDebugUnitTest --offline` is
**BLOCKED**. `~/Android/Sdk` has `platforms/android-34|35` and
`build-tools/34.0.0|35.0.0`, but **no `java` on PATH and `JAVA_HOME` unset**.
`ANDROID_HOME` was empty until we pointed it at the SDK. Mill v6 claimed
`BUILD SUCCESSFUL` / 329 tests / 0 failures inside its worktree; that number
is **unverified here**.

Unmerged laundry (not on `origin/main`):

| Branch | vs `origin/main` |
|---|---|
| `laundry-bot/queue-xx-dialer-v7` | **2 commits** (`42e25dc`, `b4daa0f`) — V7 T1/T2 only. **Do not merge as-is.** |
| `keep/xx-dialer-v4-progress` | extra commit already superseded by merged V4/V5 |
| `keep/xx-dialer-v5-progress` | extra commit already superseded by merged V5 |
| `skippy/session-20260827-0604` | adds `contracts/xx-dialer.md` only |
| `laundry-bot/queue-xx-dialer-v0` … `v6` | ancestors of `origin/main` (merged) |

---

## Mill claimed vs true (V0–V7)

Mill queue: `/home/piercingxx/skippy-queue/archive/xx-dialer-v{0–6}` all have
`MERGED` + `NAGATHA-AUDIT.md` **VERDICT: PASS**. Active:
`/home/piercingxx/skippy-queue/xx-dialer-v7` (incarnation 1, no `DONE`/`MERGED`).

| WS | Mill claimed | True on `origin/main` (plus v7 laundry) |
|---|---|---|
| **V0** CarrierConfig dump | MERGED `c9e7b0b`. Nagatha PASS after a structured `[fail]` on duplicate BLOCKED lines. Laundry-bot exit **1**. | **Not done.** `docs/vvm-carrier-config.txt` is seven BLOCKED lines (one long + six duplicates). No `dumpsys` output. `todo.md` records the empty-type decision and the BLOCKED marker. Gate was `./gradlew testDebugUnitTest --offline`, not `adb`. |
| **V1** perms / key / guard / README | MERGED `7c6e9dc`. | **Mostly true as declarations.** Manifest has INTERNET, ACCESS_NETWORK_STATE, ADD/READ/WRITE_VOICEMAIL, SEND_SMS. `KEY_VISUAL_VOICEMAIL` defaults `"0"`, backup-whitelisted. `verifyVvmInternetOnly` replaced the assemble hook. README states local screening / opt-in VVM. **Leftovers:** `NoInternetGuard` + tests still encode “INTERNET is forbidden”. `VvmSocketSiteGuardTest` comment still says IMAP does not exist — and it still doesn’t. README still says Pixel 6 / `compileSdk` 35. |
| **V2** Rules toggle + TabBar | MERGED `e574f1d` / `fb34bc6`. | **Shell is real, two product bugs.** Switch + annotation + `Tab.VOICEMAIL` + `visibleTabs` exist. Long-press `1` ungated. Hide-chip **does not persist** `"voicemail"` into `hiddenTabs`. `applyHidden` GONEs the **label** only — the fifth `layout_weight=1` **indicator** stays, so a blank slot remains in the hairline row. |
| **V3** `XxVisualVoicemailService` ACTIVATE/DEACTIVATE | MERGED `bd53ea1` / `d85341a`. Nagatha: “T3 sends ACTIVATE”. | **Facade.** Service + BIND + action exist. `VvmGate` is three booleans. `onCellServiceConnected` has `// TODO T4: TelephonyManager.sendVisualVoicemailSms(ACTIVATE).` DEACTIVATE is the same TODO, wired to **`onSimRemoved`**, not the Rules toggle. `setVisualVoicemailActivated` is **never called**. `setVisualVoicemailSmsFilterSettings` is never called. Toggle off only writes `"0"`. |
| **V4** STATUS/SYNC + encrypted creds + host gate | MERGED `cba0d63`. T2 verify was **`passed: false`, abandoned: true** then still marked done. | Parser only accepts `//VVM:STATUS:` / `//VVM:SYNC:` with mill’s `srv`/`ipt` keys — **not** the contract’s `h`/`p` body or `MBOXUPDATE?` form. `VvmCredentialStore` uses `security-crypto:1.1.0-alpha06`; `backupJson`/`logLine` are helpers **not** wired to `BackupJson.export`. Host policy is a **process-global** `lastStatusHost` (`canConnectTo`), **never called** by the worker. V4 verify method `imapHostMustEqualStatusHost` **does not exist** (renamed by V5). |
| **V5** IMAP → `VoicemailContract` | MERGED `968a0e3`. Nagatha: worker “runs IMAP fetch”. | **No IMAP.** Production fetch in `XxVisualVoicemailService` is `{ _ -> emptyList() }`. Zero `Socket`/`SSLSocket`/`HttpURLConnection` in `app/src/main`. `VvmImapSyncWorker` can insert rows **if** given messages; the live client never produces any. `VvmImapPolicy.canSync` / `shouldUseTls` and `VvmImapHostPolicy.canConnectTo` are **uncalled**. `KEY_VVM_CELLULAR_DATA_REQUIRED_BOOLEAN` is comments only. Tests inject a fake fetch. |
| **V6** fetch audio + player | MERGED `3dc61c6`. Mill: 329 tests green. Nagatha PASS. | **Broadcast into the void.** `VvmAudioFetcher` sends `VoicemailContract.ACTION_FETCH_VOICEMAIL`; **no receiver, no IMAP download, `HAS_CONTENT` never flips to 1.** `VvmAudioPlayer.play` on a content-less row only broadcasts and returns true. Direct `MediaPlayer` path is dead on device because V5 writes `HAS_CONTENT=0`. `VoicemailActivity.playVoicemail` is never called from UI. Contract itself deferred “the fetch target that downloads the audio” — mill still stamped V6 done. |
| **V7** list/detail UI | Queue live. Laundry 2 commits. Contract measured V6 as **not landed** (`968a0e3`) and scoped a second `MediaPlayer` (`VvmDetailPlayer`). | **Incomplete and should not merge.** T1 `VvmListState` + T2 `VvmListQuery` exist on the branch. T3 `VvmDetailPlayer` / T4 layouts / `VoicemailActivityTest` **missing**. `VoicemailActivity` is still a title+tab-bar shell (`queryList()` unused). `decide()` precedence is **inverted vs the V7 contract** (network-revoked loses to activating / no-config). Query does not project `HAS_CONTENT`, does not prefer ContactMirror. Honest-state Views do not exist. |

Intact (not mill-broken):

- Long-press `1` → `CallManager.placeVoicemail` → `telecom.placeCall(voicemail:)` — ungated on VVM.
- `ROLE_DIALER` / `ROLE_CALL_SCREENING` Setup + `DialActivity` intent forms + `XxInCallService`.
- Theme-sync: `ThemeSyncReceiver` + `ThemeGroundApplier` on every activity resume (Voicemail tab included).
- No GPL dialer source in tree. `LICENSE` is all-rights-reserved.
- No `localhost` string in tracked sources (estate 127.0.0.1 rule: nothing to fix).
- INTERNET is unused while the toggle is off — **and unused while it is on**, because nothing opens a socket.

---

## P0

### P0-1 — There is no IMAP client; V5 “live mailbox → VoicemailContract” is a stub

**Problem.** Production never talks to a carrier mailbox. `XxVisualVoicemailService`
constructs `VvmImapSyncWorker` with a fetch that returns `emptyList()`. No
socket, TLS, LOGIN, or FETCH exists under `…vvm`.

**Evidence.**

```37:42:app/src/main/java/com/piercingxx/xxdialer/vvm/XxVisualVoicemailService.kt
    private val syncWorker: VvmImapSyncWorker by lazy {
        VvmImapSyncWorker(this) { _ ->
            // T5 implements the real IMAP fetch (VvmImapClient). Until then the
            // sync runs and writes no rows.
            emptyList()
        }
    }
```

`VvmImapSyncWorker.sync` only writes whatever `fetch` returns. Grep of
`app/src/main` for `Socket` / `SSLSocket` / `HttpURLConnection` is empty.
`VvmSocketSiteGuardTest` still documents “Today the IMAP stack does not exist
yet (workstream V5)”.

**Why mill/Nagatha missed.** V5 T3 verify is a Robolectric test with an
**injected** fetch lambda (`VvmImapSyncWorkerTest.imapRunsOffMainThreadIntoVoicemailContract`).
That proves IO dispatcher + `contentResolver.insert`, not IMAP. Nagatha traced
`syncWorker.sync(sms)` and called it IMAP. Vacuous-guard does not flag an
`emptyList()` production lambda. Kit reachability is Python/TS-only (VACUOUS
on this repo).

**Acceptance.** A real IMAP client in `com.piercingxx.xxdialer.vvm` opens a
socket **only** after `VvmImapHostPolicy` allows the STATUS host, honors
`VvmImapPolicy` (cellular-required + TLS preference), authenticates with
encrypted STATUS creds, and inserts live messages into `VoicemailContract`.
Toggle off ⇒ zero sockets. `VvmSocketSiteGuardTest` still fails if a socket
appears outside `vvm`/`voicemail`. JVM tests must fail if the production
worker is still `{ emptyList() }`.

**Size.** large. **Owner.** mill (new V5-corrective). Do not start until P0-2
can actually obtain STATUS creds.

### P0-2 — ACTIVATE / DEACTIVATE / SMS filter are TODO comments; toggle-off does not tear down

**Problem.** The default-dialer VVM bind is registered, but the carrier is
never ACTIVATEd, STATUS SMS is never filtered, DEACTIVATE is never sent, and
the “previously activated” flag is never written. Turning the Rules switch
off only persists `"0"`.

**Evidence.**

```50:58:app/src/main/java/com/piercingxx/xxdialer/vvm/XxVisualVoicemailService.kt
    override fun onCellServiceConnected(...) {
        gate(task) {
            ...
            if (VvmGate.shouldActivate(toggleOn = true, carrierConfigValid = carrierConfigValid)) {
                // TODO T4: TelephonyManager.sendVisualVoicemailSms(ACTIVATE).
            }
        }
    }
```

```96:109:app/src/main/java/com/piercingxx/xxdialer/vvm/XxVisualVoicemailService.kt
    override fun onSimRemoved(...) {
        ...
            if (VvmGate.shouldDeactivate(toggleOn = false, previouslyActivated = previouslyActivated)) {
                // TODO T4: TelephonyManager.sendVisualVoicemailSms(DEACTIVATE),
                // setVisualVoicemailSmsFilterSettings(null), drop provider rows.
            }
```

`setVisualVoicemailActivated` is only defined in `SettingsRepository` — no
production caller. `RulesActivity` toggle listener only `persistSetting(KEY_VISUAL_VOICEMAIL, "1"|"0")`.
`onSimRemoved` is SIM-yank, not toggle-off.

**Why mill/Nagatha missed.** V3 T3/T4 verifies are `VvmGateTest` booleans
(`shouldActivateTrueOnlyWhenCarrierConfigValid`,
`shouldDeactivateOnlyWhenPreviouslyActivated`). Nagatha explicitly treated
`shouldActivate(...)` as “sends ACTIVATE” and `onSimRemoved` as the
toggle-off path. A v3 loop reviewer marked T3 **blocking**; mill filed it as
false-positive and merged.

**Acceptance.** Toggle on + valid `KEY_VVM_TYPE_STRING` ⇒ `sendVisualVoicemailSms(ACTIVATE)`,
filter settings registered, `visualVoicemailWasActivated()==true`. Toggle off
after that ⇒ DEACTIVATE SMS, filter cleared, `VoicemailContract` rows for our
package deleted, flag cleared, no further sockets. SIM-removed may share the
teardown, but **must not be the only path**. Tests must assert the telephony
calls (fake `TelephonyManager`), not only the booleans. `ADD_VOICEMAIL` /
`SEND_SMS` requested when the user first turns the toggle on if ROLE_DIALER
did not auto-grant (todo.md Permissions).

**Size.** normal. **Owner.** mill (V3-corrective).

### P0-3 — `ACTION_FETCH_VOICEMAIL` has no handler; audio never lands; “fetch-then-play” tests encode the stub as success

**Problem.** V6 broadcasts a fetch intent and returns true. Nobody in this APK
receives it. Nothing downloads IMAP audio. Nothing sets `HAS_CONTENT=1`.
Playback of a real row cannot start. Tests treat “broadcast was sent” as
“fetched and plays”.

**Evidence.** `VvmAudioFetcher.fetchIfMissingContent` → `context.sendBroadcast(Intent(ACTION_FETCH_VOICEMAIL).setData(uri))`.
Manifest has no fetch receiver. `VvmAudioPlayer.play` on `hasContent=false`
calls the fetcher and **does not play**. V5 always writes `HAS_CONTENT=0`.
`VvmAudioPlayerTest.playsContentOrFetchesThenPlays` asserts the broadcast and
`assertTrue(fetched)`. V6 contract text even admits the download “is a later
workstream” while still declaring V6 done.

**Why mill/Nagatha missed.** The named verify is a source/Robolectric test
that the **request** was broadcast. Nagatha passed `fetchIfMissingContent`
and `play` branches. The mill V6 GATE fight was `InMemoryVoicemailProvider`
authority (`com.android.voicemail`), not “does audio exist”.

**Acceptance.** A content-less row, with VVM on, results in audio bytes in
`VoicemailContract` and `HAS_CONTENT=1`, then `MediaPlayer` starts from the
row URI. Content-bearing rows play without a new IMAP fetch. Toggle off ⇒ no
fetch sockets. Tests must fail if the only “fetch” is an unhandled broadcast.
Release the player (no leaked `MediaPlayer`).

**Size.** normal–large (depends on P0-1). **Owner.** mill (V6-corrective).
Wire into V7 play, do not invent a second player.

---

## P1

### P1-1 — V0 never measured this SIM; BLOCKED dump was merged as DONE

**Problem.** No `adb shell dumpsys carrier_config | grep -i vvm` from the
Pixel 9 Pro. Client cannot know type / destination / port / cellular-required.
Empty-type decision is recorded; the dump is not.

**Evidence.** `docs/vvm-carrier-config.txt` lines 1–7 are BLOCKED repeats.
`todo.md` “VVM findings (V0)” says the dump was BLOCKED. Nagatha structured
finding `[fail] docs/vvm-carrier-config.txt:2-7` duplicate lines; estate
**VERDICT: PASS** (`GROUNDS: (kit: no FAIL)`). V0 laundry-bot exit code 1.

**Why mill/Nagatha missed.** Plan allowed a BLOCKED one-liner when adb was
missing; mill duplicated it. Nagatha kit ignored its own `[fail]`. GATE was
Gradle, not adb.

**Acceptance.** On a host with `caiman` + live SIM: replace the file with the
verbatim grep (or a single honest “no VVM keys” line). Update the `todo.md`
findings with type / destination / port / cellular-required. No duplicate
BLOCKED lines. Do not fake an inbox if type is empty (decision already
recorded — keep it).

**Size.** small. **Owner.** operator (device). Not a mill-sandbox task.

### P1-2 — JVM tests encode the wrong product; they will keep a stub “green”

**Problem.** The suite that mill and Nagatha treated as the exam asserts
facades.

**Evidence.**

- `VvmGateTest` — booleans only; ACTIVATE TODO still green.
- `VvmImapSyncWorkerTest` — injected fetch, not a socket.
- `VvmAudioFetcherTest` / `VvmAudioPlayerTest` — broadcast == fetch/play.
- `VvmCredentialStoreTest.passwordNeverInBackupOrLog` — redacts helpers;
  does not round-trip `save`/`load`; V4 T2 was **abandoned failed** then merged.
- `VvmImapHostPolicyTest` — method is `allowedHostEqualsStatusHost`, not
  contract `imapHostMustEqualStatusHost`; mutates a process-global
  `lastStatusHost` with no reset (`refusesAnyHostBeforeFirstStatus` is
  order-dependent).
- `VvmSmsParserTest` pins `srv=` bodies; would **fail** the V4 contract
  example `h=…;p=993` / `MBOXUPDATE?`.
- `NoInternetGuardTest` still flags INTERNET as dirty (old R8 product).
- `VisualVoicemailRulesScreenTest` only tests
  `RulesScreenVoicemail.voicemailHideChipVisible(Boolean)` — not XML, not
  persist.
- `XxVisualVoicemailServiceManifestTest` / `VvmManifestPermissionTest` —
  `File.readText()` substring matches.
- Unmerged `VvmListStateTest.decidesHonestState` encodes mill’s inverted
  precedence as the truth.

**Why mill/Nagatha missed.** Contracts scoped “pure seams” and source-reading
verifies so the sandbox GATE could pass without a carrier. Nagatha
claim-vs-tree / contract-coverage only checks that the **named test method
exists**.

**Acceptance.** Corrective contracts require tests that fail on today’s tree
(empty fetch lambda, TODO ACTIVATE, unhandled fetch broadcast, hide-chip not
persisting, indicator blank slot). Delete or rewrite `NoInternetGuard*` so
they cannot resurrect “no INTERNET ever”. Reset `VvmImapHostPolicy` between
tests or stop using a mutable object singleton.

**Size.** normal. **Owner.** mill (test repair, lands with the P0 correctives).

### P1-3 — Voicemail hide-chip does not persist; tab indicator leaves a blank slot

**Problem.** V2 done-when: toggle on ⇒ hideable like Recents; toggle off ⇒ no
blank slot. Both halves fail.

**Evidence.** `RulesActivity` hidden-set builder adds recents/keypad/people
only — **not** `voicemail`, even though `chipTabVoicemail` is in the listener
list (`RulesActivity.kt` ~722–734). `TabBar.applyHidden` sets `tab.itemId`
GONE but never the `tab.indicatorId` row; `view_tab_bar.xml` keeps five
`layout_weight=1` hairlines. `chipTabVoicemail` XML default is visible until
the coroutine runs.

**Why mill/Nagatha missed.** Verifies were `TabBarVisibilityTest` (pure set)
and `VisualVoicemailRulesScreenTest` (one-line chip-visible helper).

**Acceptance.** Checking VOICEMAIL hide-chip persists `"voicemail"` in
`hiddenTabs` and hides **label + indicator**. Toggle off ⇒ both GONE, four
tabs, no empty weight cell. No flash of the chip on first Rules draw.

**Size.** small. **Owner.** mill.

### P1-4 — STATUS parser does not match the V4 contract (or CVVM / `MBOXUPDATE`)

**Problem.** `VvmSmsParser` requires `//VVM:STATUS:` / `//VVM:SYNC:` and known
keys `srv`,`ipt`,`u`,`pw`,… It drops `h`/`p`/`server`/`name`/`port`. No
`MBOXUPDATE?` form. Tests pin mill’s dialect.

**Evidence.** `VvmSmsParser.kt` `KNOWN_FIELDS` / prefixes.
`contracts/xx-dialer-v4.md` T1 example:
`//VVM:STATUS:h=mail.example.com;p=993;u=eg@example.com;pw=secret` and
`MBOXUPDATE?server=…;port=…;name=…;pw=…`. Host recording uses `sms.fields["srv"]`
only.

**Why mill/Nagatha missed.** Mill wrote the test to match the parser, not the
contract example. Nagatha: “implementation matches the test”.

**Acceptance.** After P1-1 dump: parse **this SIM’s** STATUS/SYNC (and OMTP 1.1
`h`/`p` plus `srv`/`ipt`, plus `MBOXUPDATE?`). Malformed → null. Password
never logged. JVM tests include the V4 contract bodies **and** a fixture
copied from the live dump.

**Size.** normal. **Owner.** mill (needs P1-1).

### P1-5 — Host / TLS / cellular gates exist and are unused

**Problem.** D5/D6 stop conditions are dead code. A future IMAP client can
ignore them and still pass today’s tests.

**Evidence.** `VvmImapHostPolicy` / `VvmImapPolicy` have no callers in
`VvmImapSyncWorker` or a client (only STATUS `recordStatusHost("srv")` in
`onSmsReceived`). `KEY_VVM_CELLULAR_DATA_REQUIRED_BOOLEAN` never read from
`CarrierConfigManager`. Host is in-memory; process death forgets STATUS host
even if creds remain in encrypted prefs.

**Why mill/Nagatha missed.** V4 and V5 both shipped the same “pure object”;
V5 T3 verify does not mention `allowedHost` or `canSync`.

**Acceptance.** Worker/client **must** call both policies before connect;
mismatch or cellular-required-without-cellular ⇒ no socket. Persist last
STATUS host with the encrypted creds (not Room backup). Tests fail if the
client connects without consulting the gates.

**Size.** small–normal. **Owner.** mill (with P0-1).

### P1-6 — Do not merge `laundry-bot/queue-xx-dialer-v7`; list/detail is not a product

**Problem.** V7 is a partial laundry slice on top of a stub stack. Contract
wrongly assumed V6 had not landed and specified a second player. `decide()`
precedence contradicts the operator contract (network-revoked should win so
GrapheneOS Network revoke is not shown as “activating”).

**Evidence.** Diff vs main: `VvmListState.kt`, `VvmListQuery.kt`, tests, +12
lines on `VoicemailActivity`. No `VvmDetailPlayer`, no `item_voicemail_row.xml`,
no failure-state Views, `activity_voicemail.xml` still title+tab bar.
`VvmListState.decide`: `!carrierConfigValid` → `!activated` → `networkRevoked`.
V7 CONTRACT.md: `networkRevoked` first. Query omits `HAS_CONTENT`; name
resolution is PhoneLookup only (injectable), not ContactMirror-then-lookup.

**Why mill/Nagatha missed.** V7 not audited yet. T1/T2 verifies compile if the
objects exist. T3/T4 unchecked.

**Acceptance.** Finish V7 **after** P0-1/2/3. One player (`VvmAudioPlayer`).
List: name (ContactMirror then PhoneLookup), time, duration, unread. Detail:
play/pause, speaker, call back, delete, carrier transcription. Honest states
with Retry on IMAP error. Theme-sync already global — keep Views, no Compose.
`decide()`: network revoked → no config → activating → IMAP error → empty →
list. Tests that would fail on the current laundry branch.

**Size.** normal. **Owner.** mill (V7 redo, not a merge of the laundry
branch).

### P1-7 — `KEY_VISUAL_VOICEMAIL_ACTIVATED` is a backup landmine; creds helpers are not the backup path

**Problem.** The activation flag lives in Room. `BackupJson.export` dumps
**all** settings. The key is **not** in `IMPORTABLE_SETTING_KEYS`. The day
P0-2 starts writing it, export/import fails closed. Password is safe today
only because it is not in Room; `VvmCredentialStore.backupJson` is unused by
`BackupJson`.

**Evidence.** `SettingsRepository.KEY_VISUAL_VOICEMAIL_ACTIVATED` vs
`BackupJson.IMPORTABLE_SETTING_KEYS` (has `KEY_VISUAL_VOICEMAIL` only).
V4 T2 abandoned a real EncryptedSharedPreferences round-trip.

**Why mill/Nagatha missed.** T2 test only checks `backupJson(creds)` string
redaction.

**Acceptance.** Activation flag: whitelist it **or** keep it out of Room.
Password never in JSON, never in log, `clear()` on toggle-off. `save`/`load`
tested (or a test double for KeyStore — not a redaction-only helper). Prefer
a **stable** Jetpack Security artifact over `1.1.0-alpha06` if one exists for
this SDK.

**Size.** small. **Owner.** mill.

### P1-8 — Setup never asks for VVM permissions; ROLE_DIALER path is otherwise intact

**Problem.** todo.md: confirm whether ROLE_DIALER auto-grants `ADD_VOICEMAIL`;
if not, Setup asks on **first toggle on**, not first launch. No VVM mention in
`SetupActivity`. Toggle-on has no permission request.

**Evidence.** Grep `SetupActivity.kt` for voicemail / `ADD_VOICEMAIL` is empty.
`ROLE_DIALER` request itself works (`SetupActivity`, `RoleAcquisitionTest`).

**Why mill/Nagatha missed.** Out of V1–V6 task lists.

**Acceptance.** First toggle-on requests any missing voicemail/SMS permission.
Denial ⇒ honest copy, long-press `1` still works, no ACTIVATE. Document
GrapheneOS ROLE_DIALER grant behavior from the device.

**Size.** small. **Owner.** mill + operator (grant check on `caiman`).

---

## P2

### P2-1 — No GitHub Actions

**Problem.** `PiercingXX/xx-dialer` has zero workflows. Mill GATE never runs
on GitHub. Laundry branches were not pushed.

**Evidence.** `gh` totals above; laundry_bot_run.json `"skipped github.com push"`.

**Why mill/Nagatha missed.** Out of scope.

**Acceptance.** A workflow that runs `./gradlew testDebugUnitTest` (and
assemble + `verifyVvmInternetOnly`) on `main`/PR. Failures visible in `gh`.

**Size.** small. **Owner.** mill / operator.

### P2-2 — README / SDK drift vs the brief

**Problem.** Brief: Pixel 9 Pro, Android 17 / SDK 37. README: Pixel 6,
`compileSdk`/`targetSdk` 35. Status still “not proven against a live SIM”.

**Evidence.** `README.md` Status + Build; `app/build.gradle.kts` `compileSdk = 35`.

**Why mill/Nagatha missed.** V1 T6 only required the word “voicemail” and the
`verifyVvmInternetOnly` rename.

**Acceptance.** README matches the live target and the local/opt-in-VVM split
without claiming a working mailbox until P0 is done.

**Size.** small. **Owner.** mill.

### P2-3 — `VvmSocketSiteGuardTest` comment and vacuous pass

**Problem.** Guard is the right shape; comment and green result still say
“no IMAP yet” after a merged V5.

**Acceptance.** After P0-1, the guard still passes **and** at least one socket
site exists under `vvm`. Comment updated.

**Size.** small. **Owner.** mill (with P0-1).

### P2-4 — D7 carrier VVM package / CVVM / VVM3

**Problem.** todo.md D5/D7: OMTP 1.1, CVVM, VVM3; do not fight an installed
carrier VVM package unless our toggle is on. No `KEY_CARRIER_VVM_PACKAGE_NAME_STRING`
check.

**Acceptance.** After P1-1, implement only the types this SIM publishes. If a
carrier VVM package is present and our toggle is off, do not ACTIVATE.

**Size.** small–normal. **Owner.** mill (after P1-1).

### P2-5 — MediaPlayer lifecycle / speaker / pause

**Problem.** `VvmAudioPlayer` creates a `MediaPlayer`, never releases, no
pause/speaker. V7 T3 wanted those on `VvmDetailPlayer` — duplicate surface.

**Acceptance.** One player with play/pause/speaker and `release()`. V7 UI
calls it.

**Size.** small. **Owner.** mill (with P0-3 / V7).

### P2-6 — Untracked `contracts/` and keep/skippy branches

**Problem.** `contracts/` is untracked on the main worktree. Keep branches are
superseded leftovers. `skippy/session-20260827-0604` only adds
`contracts/xx-dialer.md`.

**Acceptance.** Track the V0–V7 contracts if they are the mill source of
truth; delete or archive keep branches so they are not mistaken for unmerged
product.

**Size.** small. **Owner.** operator.

### P2-7 — `verifyVvmInternetOnly` does not run on `testDebugUnitTest`

**Problem.** The permission guard is `finalizedBy` `assembleDebug` only. A
test-only GATE never aapt2-dumps the APK.

**Acceptance.** Either depend the unit GATE on the verify task after a debug
APK, or keep it on assemble and add CI assemble.

**Size.** small. **Owner.** mill.

### P2-8 — Chip XML default-visible; first-frame VVM chip flash

**Problem.** `chipTabVoicemail` has no `gone` default; `syncVoicemailGate()` is
async.

**Acceptance.** XML default GONE; show only after toggle reads `"1"`.

**Size.** small. **Owner.** mill (with P1-3).

---

## What not to “repair”

- **Long-press `1`.** Stays forever. Do not gate it on the VVM toggle.
- **`ROLE_DIALER` Setup / `DialActivity` forms / in-call FGS.** Screening
  product. Out of VVM repair unless a later slice proves a regression.
- **Theme-sync.** Already applied on resume for every activity.
- **Cleanroom.** Do not open Fossify / Simple Mobile Tools / Google Phone /
  carrier VVM APKs. AOSP `VisualVoicemailService` / `VoicemailContract` /
  Apache-2.0 Dialer VVM algorithms may be **reimplemented** under
  `com.piercingxx.xxdialer.vvm` with NOTICE if derived.
- **Merging `laundry-bot/queue-xx-dialer-v7`.** Redo V7 after P0.
- **Bouncing Skippy / working in xx-phone.** Out of scope for this inventory.

## Suggested order

P1-1 (operator dump) → P0-2 (ACTIVATE/DEACTIVATE/filter/toggle-off) → P1-4
(parser for this SIM) → P0-1 (IMAP) + P1-5 (gates actually called) → P0-3
(real fetch+play) → P1-3 / P1-7 / P1-8 → P1-6 (V7 UI) → P1-2 (rewrite the
exam so it cannot go green on stubs) → P2 as needed.

V8 delete/seen, V9 notification, V10 live-SIM dogfood are **not started**.
Do not enqueue them on top of this facade.
