package com.piercingxx.xxdialer.util

/**
 * Canonical detector for the brand rule "no INTERNET permission, ever"
 * (design.md R8/D8). Consumes raw `aapt2 dump permissions` output.
 *
 * The build enforces the same rule via `verifyNoInternet` in
 * app/build.gradle.kts and probe/build.gradle.kts, which carry a verbatim
 * copy of [violations] — keep all three in sync.
 */
object NoInternetGuard {

    /** Lines from the dump that mention the forbidden permission. */
    fun violations(dump: String): List<String> =
        dump.lineSequence()
            .map { it.trim() }
            .filter { it.contains("android.permission.INTERNET", ignoreCase = true) }
            .toList()

    /** True when the APK's permission dump is free of INTERNET. */
    fun isClean(dump: String): Boolean = violations(dump).isEmpty()
}
