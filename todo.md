# XX-Dialer — Remaining work

**2026-09-05.** Core dialer + ring policy exist. Ring-repeat, lock-screen
in-call, and audio-route polish landed (`c19f6b6`). Recents chips, missed
clear, hide-voicemail persist, and VvmListState.decide landed (`d3870d7`).
V1 toggle-off teardown + V2 fetch/notify honesty landed in this pass.

Package: `com.piercingxx.xxdialer`  
Target: Pixel 9 Pro (`caiman`), GrapheneOS, live SIM.  
Spec: [design.md](design.md). Repair archaeology: [TODO-REPAIR-2026-08-27.md](TODO-REPAIR-2026-08-27.md).

```
Status: default dialer + screening + Rules engine are built. Visual
voicemail is a code-complete client that still needs a live-SIM STATUS
transcript (V0) before IMAP can fetch real audio. design.md header still
says “nothing built.”
```

Screening stays **local**. VVM is the only opt-in network feature. Long-press
`1` → `voicemail:` **never** breaks, including when VVM is off.

---

## Locked now (2026-09-04)

| ID | Decision |
|---|---|
| V1 | **Finish VVM end-to-end, then live-SIM dogfood.** Do not park the client. |
| D1–D9 | Still as the old brief: off by default, `VoicemailContract` store, fail-honest states, IMAP host from STATUS SMS only, no greeting/PIN/transcription cloud. |

Recent on-device work (routes / mute armed / lock-screen hang-up / ring once)
is in the tree. Do not reopen unless it regresses.

---

## Workstreams

The WS0 probe (`:probe`) is **test-only** and has **no launcher icon**.
Install only with `./gradlew :probe:installProbe`. Uninstall when the
dump is done. Never leave `com.piercingxx.xxdialer.probe` on the daily
driver as a suite app.

### V0 — Carrier dump (blocks honest IMAP)

- [ ] Run the probe on caiman (`PROBE.md`). Capture `docs/vvm-carrier-config.txt`
  (today: seven BLOCKED lines).
- [ ] Record `KEY_VVM_TYPE_STRING`, STATUS SMS shape, IMAP host, cellular-required.
- **Accept:** a real STATUS transcript in-repo, or a written “this SIM has no
  VVM” and the UI stays on the NoCarrierConfig state.

### V1 — Toggle-off teardown

- [x] Rules toggle **off** sends DEACTIVATE if we previously activated, clears
  the SMS filter, stops displaying our package’s `VoicemailContract` rows,
  resets `visual_voicemail_activated`, removes the tab.
- [x] Shared `VvmActivationController` — SIM yank and the Rules switch both
  tear down. Persisting `"0"` is no longer the only off path.
- **Accept:** toggle on → inbox works → toggle off → zero sockets, no tab,
  long-press 1 still dials the carrier mailbox. Live-SIM half is V4.

### V2 — Fetch + play + honest states

- [x] `ACTION_FETCH_VOICEMAIL` has a non-exported receiver (`VvmFetchHandler`)
  and the fetcher also runs the downloader in-process. `HAS_CONTENT` flips
  only when IMAP returns audio. No STATUS host / credentials ⇒ fail honest
  (never invent IMAP host).
- [x] `VoicemailActivity` goes through `VvmListState.decide()` — Activating /
  NoCarrierConfig / ImapError / network-revoked are **shown**, not skipped.
- [x] `VvmImapPolicy.canSync` (cellular-required) is consulted before any
  IMAP socket (`VvmImapClient.fetch` / `fetchAudio` / `VvmAudioDownloader`).
- [x] New-voicemail notification (`voicemail_v1`, SECRET/1000) fires once per
  new row when a row appears, gated on the toggle.
- [x] First toggle-on requests `ADD_VOICEMAIL` / `SEND_SMS` if ROLE_DIALER
  did not auto-grant.
- **Accept:** leave a VM, see the row, tap, hear it, delete. GrapheneOS
  Network revoke shows the honest copy and points at long-press 1.
  **Needs live SIM (V0 + V4).** Code path is wired; audio cannot land without
  a real STATUS `srv`/`u`/`pw`.

### V3 — TabBar

- [x] Hide `tab_ind_*` with the labels when VVM is off (no blank fifth slot).
- [x] Persist `"voicemail"` in the hidden-tabs set (`TabBar.hiddenNames` +
  Rules listener). Hide Voicemail survives process death.
- **Accept:** toggle off → four tabs, no ghost indicator. Hide Voicemail,
  kill process, it stays hidden.

### V4 — Live-SIM gate (after V1–V3)

Record in this file or `PROBE.md`.

- [ ] Restricted Settings / default-dialer grant on GrapheneOS.
- [ ] Starred always rings; unknown uses the unknown channel + window.
- [ ] STIR / screening statuses look like the network, not a fake.
- [ ] Contact Scopes: denied contacts do not leak into People as a lie.
- [ ] xx-contacts Recents **Save** still opens the contacts editor (F1).
- [ ] VVM dogfood: fresh install, toggle on/off, leave/play/delete, Network revoke.
- **Accept:** dated notes. README may drop “Not proven against a live SIM”
  only after this list is filled.

### V6 — Why silenced

The policy engine is the product. Recents must say why a row was quiet.

- [x] Silenced / screened Recents row shows a one-line reason via
  `VerdictLines` (`Unknown, outside 09–17` / pattern mask / blocklist /
  send-to-voicemail — the `screen_log` / `RingPolicy` reason, not a guess).
- [ ] Tap the reason opens Rules on the matching surface (window chip,
  pattern, blocklist). Starred rows never need this. Left as a follow-up —
  not a small add (Rules has no deep-link extras yet).
- **Accept:** unknown caller in a closed window → Recents says so.
  A pattern match names the pattern. Enforce-off / observe still logs
  honestly. Display half is done; tap-to-Rules is leftover.

### V5 — Docs / tests

- [ ] `design.md` header is no longer “specification only.”
- [ ] Retire or rewrite `NoInternetGuardTest` (VVM made INTERNET legal).
  Keep `verifyVvmInternetOnly`.
- [ ] compileSdk/targetSdk: 35 in Gradle vs 37 in docs — pick one and match.
- **Accept:** a stranger can read README and know screening is local and
  VVM is opt-in.

---

## Stop conditions

- Network used for screening, contacts, or telemetry → reject.
- IMAP to a host that was not in the last STATUS SMS → reject.
- Breaking long-press 1 → reject.
- GPL dialer source → reject.
- Tick VVM DONE without V0 + V2 on a real SIM → reject.

---

## Suggested order

1. ~~V3 (cheap, user-visible)~~
2. V6 tap-to-Rules (display is done)
3. ~~V1 + V2 code~~ — live-SIM still V0/V4
4. V0 on the Pixel as soon as a SIM is in it
5. V4 dogfood
6. V5 last
