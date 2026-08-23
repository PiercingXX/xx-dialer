package com.piercingxx.xxphone

import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * design.md §16, instrumented bullet "Contact Scopes matrix (full / partial /
 * empty grants)" — the V4 [VERIFY]: does GrapheneOS Contact Scopes filter
 * `PhoneLookup` at call time, i.e. does a scoped-out caller classify as unknown
 * at ring time? That is the correct degradation per §4.5 and WS4's empty-grant
 * exit criterion.
 *
 * WS gate: WS0/V4 → WS4 (FactStore mirror).
 *
 * Automates PROBE.md §4 · CONTACT SCOPES / PHONELOOKUP with the same fixture:
 * contact "XX Probe 5550100", number 555-0100. Parameterized over the three
 * grant modes; the operator applies each grant by hand between invocations —
 * scopes cannot be self-granted mid-run (that is exactly what is under test).
 *
 * Expected row counts (rows = PhoneLookup matches for the probe number):
 *   FULL    → 1  (saved ⇒ known; screener sees contacts when also default dialer)
 *   PARTIAL → 0  (probe contact scoped out ⇒ filtered at call time)
 *   EMPTY   → 0  (no crash, no re-prompt loop)
 */
@Ignore("requires caiman — see PROBE.md")
@RunWith(Parameterized::class)
class ScopesMatrixTest(private val grant: GrantMode) {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun phoneLookup_rowCount_matches_grantMode_expectation() {
        val rows = phoneLookupRowsFor(PROBE_FILTER_FORM)

        // TODO-on-device: run once per mode with the operator having applied the
        // matching Contacts scope in Settings → Apps → XX-Phone → Contacts
        // BEFORE invoking this parameterized instance (PROBE.md §4 steps 1–3).
        val expected = grant.expectedRows
        if (grant == GrantMode.FULL) {
            assertTrue(
                "FULL grant but PhoneLookup returned $rows rows for $PROBE_FILTER_FORM",
                rows >= expected,
            )
        } else {
            assertEquals(
                "$grant grant should filter the probe number out of PhoneLookup",
                expected,
                rows,
            )
        }
    }

    /**
     * The exact provider read the screening/mirror path performs at call time
     * (§4.5 warm-vs-cold boundary aside: this is the cold reference query).
     */
    private fun phoneLookupRowsFor(filterForm: String): Int {
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(filterForm)
            .build()
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NORMALIZED_NUMBER,
            ),
            null,
            null,
            null,
        )?.use { cursor -> return cursor.count }
        return 0
    }

    /** Also log the contacts_data account_name evidence PROBE.md §4 asks for. */
    @Test
    fun contactsData_nonNullAccountName_cells_logged_for_targetSdk_tightening() {
        // TODO-on-device: count non-null account_name cells in ContactsContract.RawContacts
        // per §4.5 targetSdk-37 Data-table tightening note; record alongside the
        // three runs pasted into design.md.
    }

    enum class GrantMode(val expectedRows: Int) {
        FULL(1),
        PARTIAL(0),
        EMPTY(0),
    }

    companion object {
        const val PROBE_FILTER_FORM = "555-0100"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<GrantMode> = GrantMode.entries.toList()
    }
}
