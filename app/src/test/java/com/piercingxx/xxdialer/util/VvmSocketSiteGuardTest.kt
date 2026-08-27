package com.piercingxx.xxdialer.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source gate: the IMAP socket site lives ONLY in the …vvm / …voicemail package
 * (todo.md D1 / "verifyVvmInternetOnly"). The dialer is offline by default; the
 * one thing that may touch the network is the opt-in Visual voicemail client's
 * IMAP connection to the carrier mailbox, and that code must be confined to a
 * package whose first segment under com.piercingxx.xxdialer is `vvm` or
 * `voicemail`. Any socket-opening code anywhere else is a signal that the dialer
 * is on the network beyond VVM and fails the build.
 *
 * Today the IMAP stack does not exist yet (workstream V5), so the scan passes
 * trivially; the guard exists to fail the moment a future edit drops a socket
 * call outside the VVM package. It is the source-side complement to
 * VvmInternetGuard (permission-level) and VvmManifestPermissionTest.
 */
class VvmSocketSiteGuardTest {

    /** Package prefixes under com.piercingxx.xxdialer that may open sockets. */
    private val allowedPackageSegments = setOf("vvm", "voicemail")

    /**
     * Word-boundary markers for code that opens a network connection. Anything
     * that puts the process on the wire (a raw Socket, an SSL socket, a socket
     * channel, a datagram socket, or an HTTP client) is a socket site.
     */
    private val socketMarkers = listOf(
        "\\bSocket\\b",
        "\\bSSLSocket\\b",
        "\\bSocketFactory\\b",
        "\\bSSLSocketFactory\\b",
        "\\bSocketChannel\\b",
        "\\bDatagramSocket\\b",
        "\\bHttpURLConnection\\b",
        "\\bHttpsURLConnection\\b",
        "\\bOkHttpClient\\b",
    )

    /** The com.piercingxx.xxdialer source root, whichever cwd the test runs from. */
    private fun sourceRoot(): File =
        sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer"),
            File("app/src/main/java/com/piercingxx/xxdialer"),
        ).first { it.isDirectory }

    /** All .kt/.java files under the dialer package root. */
    private fun sourceFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

    /** True when the file references any socket-opening API. */
    private fun opensSocket(file: File): Boolean {
        val text = file.readText()
        return socketMarkers.any { Regex(it).containsMatchIn(text) }
    }

    /** First package segment under com.piercingxx.xxdialer for a file, or null. */
    private fun packageSegment(root: File, file: File): String? =
        root.toPath().relativize(file.toPath())
            .takeIf { it.nameCount > 0 }
            ?.getName(0)
            ?.toString()

    @Test
    fun `socket sites live only under the vvm or voicemail package`() {
        val root = sourceRoot()
        val offenders = sourceFiles(root)
            .filter { opensSocket(it) }
            .filter { packageSegment(root, it) !in allowedPackageSegments }

        assertTrue(
            offenders.isEmpty(),
            "socket-opening code found outside the …vvm/…voicemail package " +
                "(todo.md D1):\n" +
                offenders.joinToString("\n") { it.relativeTo(root).path },
        )
    }
}