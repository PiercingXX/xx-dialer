package com.piercingxx.xxdialer.vvm

/**
 * Pure IMAP host-constraint seam (todo.md D5, T3). Visual Voicemail mailboxes
 * must be reached only over the host the carrier's last STATUS notification
 * named (`srv`), never a host we guessed, cached from an earlier mailbox, or
 * accepted from a non-STATUS message. This object owns that single rule: it
 * records the last STATUS host and answers whether a candidate host may be
 * connected to. The IMAP connect path (T5) consults [canConnectTo] before
 * opening a socket, and [recordStatusHost] is fed from the STATUS SMS handler,
 * so the constraint is enforced at the one place a connection is made.
 */
object VvmImapHostPolicy {

    /** The `srv` host from the most recent STATUS notification, or null before any. */
    private var lastStatusHost: String? = null

    /**
     * Records [host] as the mailbox host named by a STATUS notification. Only a
     * STATUS message may set this — a SYNC message carries no new host and must
     * not widen the allowed set.
     */
    fun recordStatusHost(host: String) {
        lastStatusHost = host
    }

    /** Forget the STATUS host — toggle-off / SIM yank must leave no socket target. */
    fun clear() {
        lastStatusHost = null
    }

    /**
     * True only when [host] equals the last STATUS host. Before any STATUS
     * notification has been seen, or when [host] differs from it, the connection
     * must be refused — there is no mailbox host we may reach.
     */
    fun canConnectTo(host: String): Boolean = host == lastStatusHost
}