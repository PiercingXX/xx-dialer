# XX-Dialer keeps R8 rules minimal until release hardening lands.
# Nothing here may ever relax the no-INTERNET claim (design §13/R8).

# screen_log / Recents match Verdict.token(); keep the nested types anyway.
-keep class com.piercingxx.xxdialer.core.Verdict { *; }
-keep class com.piercingxx.xxdialer.core.Verdict$* { *; }
-keep class com.piercingxx.xxdialer.core.Reason { *; }
