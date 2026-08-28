# xx-dialer VVM repair — real IMAP, ACTIVATE, fetch handler

Operator 2026-08-27. V0–V6 MERGED as shells. Repair list:
TODO-REPAIR-2026-08-27.md. Do not merge laundry-bot/queue-xx-dialer-v7 as-is.
No GPL phone source. Toggle off remains zero sockets. Always 127.0.0.1.

Production src/main must not keep emptyList fetch or TODO T4 ACTIVATE.
Tests that inject a fake fetch do not count. Do not check a box because a
grep appeared.

## Final gate

- verify: ./gradlew testDebugUnitTest --offline

### T1 — production IMAP client not emptyList

XxVisualVoicemailService production fetch must not be an emptyList lambda.
A real IMAP client in com.piercingxx.xxdialer.vvm opens a socket only after
VvmImapHostPolicy allows the STATUS host. JVM tests fail if the production
worker is still emptyList. Toggle off means zero sockets.

- files: app/src/main/java/com/piercingxx/xxdialer/vvm/XxVisualVoicemailService.kt, app/src/main/java/com/piercingxx/xxdialer/vvm/VvmImapClient.kt, app/src/test/java/com/piercingxx/xxdialer/vvm/VvmImapClientTest.kt
- verify: ./gradlew :app:testDebugUnitTest --tests com.piercingxx.xxdialer.vvm.VvmImapClientTest --offline

### T2 — ACTIVATE DEACTIVATE are telephony calls not TODO

Toggle on plus valid KEY_VVM_TYPE_STRING sends ACTIVATE SMS and registers
the filter. Toggle off sends DEACTIVATE and clears rows. Tests fake
TelephonyManager and fail if the production methods are still TODO T4
comments.

- files: app/src/main/java/com/piercingxx/xxdialer/vvm/XxVisualVoicemailService.kt, app/src/test/java/com/piercingxx/xxdialer/vvm/VvmActivateTest.kt
- verify: ./gradlew :app:testDebugUnitTest --tests com.piercingxx.xxdialer.vvm.VvmActivateTest --offline

### T3 — ACTION_FETCH_VOICEMAIL has a handler that sets HAS_CONTENT

A receiver or service handles VoicemailContract.ACTION_FETCH_VOICEMAIL and
downloads audio so HAS_CONTENT becomes 1. Tests fail if fetch only
broadcasts and returns true.

- files: app/src/main/AndroidManifest.xml, app/src/main/java/com/piercingxx/xxdialer/vvm/VvmAudioFetcher.kt, app/src/test/java/com/piercingxx/xxdialer/vvm/VvmFetchHandlerTest.kt
- verify: ./gradlew :app:testDebugUnitTest --tests com.piercingxx.xxdialer.vvm.VvmFetchHandlerTest --offline
