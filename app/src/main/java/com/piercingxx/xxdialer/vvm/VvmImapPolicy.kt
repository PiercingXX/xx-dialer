package com.piercingxx.xxdialer.vvm

/**
 * Pure IMAP decision seams (todo.md D5/D6). The IMAP sync worker (T3) consults
 * these before opening a socket: whether a mailbox sync may run at all on the
 * current transport, and whether to use TLS for the connection. Both are pure
 * decisions — no Android types, no I/O — so they are trivially unit-testable
 * and safe to call from any thread.
 *
 * - Cellular-data gate (D6): some carriers require the mailbox to be reached
 *   only over cellular data (`KEY_VVM_CELLULAR_DATA_REQUIRED_BOOLEAN`). When
 *   that flag is set, sync must not run over WiFi — the connection is refused
 *   unless the device is actually on a cellular-data transport.
 * - TLS preference (D5): the OMTP spec lets the carrier offer TLS; when it
 *   does, we must use it rather than falling back to plaintext IMAP.
 */
object VvmImapPolicy {

    /**
     * True only when a mailbox sync may run on the current transport.
     *
     * When [cellularDataRequired] is false the mailbox may be reached over any
     * transport (WiFi or cellular). When it is true, sync is gated on
     * [onCellularData]: a device on WiFi (or with no data connection) must not
     * open the IMAP socket, because the carrier requires the mailbox to be
     * reached only over cellular data.
     */
    fun canSync(cellularDataRequired: Boolean, onCellularData: Boolean): Boolean =
        !cellularDataRequired || onCellularData

    /**
     * True only when the IMAP connection should use TLS.
     *
     * The carrier signals whether it offers TLS for the mailbox; when it does
     * ([tlsOffered]) we prefer TLS over plaintext. When it does not, we fall
     * back to the plaintext port rather than refusing the mailbox.
     */
    fun shouldUseTls(tlsOffered: Boolean): Boolean = tlsOffered
}