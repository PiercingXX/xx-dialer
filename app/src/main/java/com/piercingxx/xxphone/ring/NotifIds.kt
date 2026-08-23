package com.piercingxx.xxphone.ring

/**
 * Notification id vocabulary (§10/§12). Pairwise-disjoint BY CONTRACT: a
 * shared id lets one stream's cancel/notify evict another's card, and a
 * silenced call's surfacing must persist until answered/missed (R7, B2).
 * Literals are frozen — pinned by NotifIdsTest.
 */
internal object NotifIds {
    const val INCOMING = 1 // ringing/silenced-but-answerable incoming call (§6, CallStyle)
    const val ONGOING = 2 // in-call foreground service (§10)
    const val MISSED = 1001 // Telecom-handover missed-call record (§13)
    const val SILENCED = 1100 // post-hoc silenced-call record / burst (§12)
}
