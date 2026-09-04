# XX-Dialer — Remaining work

**2026-09-04.** Core dialer + ring policy exist. Ring-repeat, lock-screen
in-call, and audio-route polish landed (`c19f6b6`). This file is no longer
only a VVM brief — it is the remaining product work.

Package: `com.piercingxx.xxdialer`  
Target: Pixel 9 Pro (`caiman`), GrapheneOS, live SIM.  
Spec: [design.md](design.md). Repair archaeology: [TODO-REPAIR-2026-08-27.md](TODO-REPAIR-2026-08-27.md).

```
Status: default dialer + screening + Rules engine are built. Visual
voicemail is a partial client. Live-SIM / WS0 carrier dump never recorded.
design.md header still says “nothing built.”
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

### V0 — Carrier dump (blocks honest IMAP)

- [ ] Run the probe on caiman (`PROBE.md`). Capture `docs/vvm-carrier-config.txt`
  (today: seven BLOCKED lines).
- [ ] Record `KEY_VVM_TYPE_STRING`, STATUS SMS shape, IMAP host, cellular-required.
- **Accept:** a real STATUS transcript in-repo, or a written “this SIM has no
  VVM” and the UI stays on the NoCarrierConfig state.

### V1 — Toggle-off teardown

- [ ] Rules toggle **off** sends DEACTIVATE if we previously activated, clears
  the SMS filter, stops displaying our package’s `VoicemailContract` rows,
  resets `visual_voicemail_activated`, removes the tab.
- [ ] Today only `onSimRemoved` tears down. Persisting `"0"` is not teardown.
- **Accept:** toggle on → inbox works → toggle off → zero sockets, no tab,
  long-press 1 still dials the carrier mailbox.

### V2 — Fetch + play + honest states

- [ ] `ACTION_FETCH_VOICEMAIL` has a receiver (or the fetcher is called
  in-process). `HAS_CONTENT` flips; `VvmDetailPlayer` can play.
- [ ] `VoicemailActivity` goes through `VvmListState.decide()` — Activating /
  NoCarrierConfig / ImapError / network-revoked are **shown**, not skipped.
- [ ] `VvmImapPolicy.canSync` (cellular-required) is actually consulted.
- [ ] New-voicemail notification (`voicemail_v1`) fires once per new row.
- [ ] First toggle-on requests `ADD_VOICEMAIL` / `SEND_SMS` if ROLE_DIALER
  did not auto-grant.
- **Accept:** leave a VM, see the row, tap, hear it, delete. GrapheneOS
  Network revoke shows the honest copy and points at long-press 1.

### V3 — TabBar

- [ ] Hide `tab_ind_*` with the labels when VVM is off (no blank fifth slot).
- [ ] Persist `"voicemail"` in the hidden-tabs set (listener currently omits it).
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

1. V3 (cheap, user-visible)
2. V1 + V2 (the client is unfinished)
3. V0 on the Pixel as soon as a SIM is in it
4. V4 dogfood
5. V5 last
