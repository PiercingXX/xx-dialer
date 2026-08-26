package com.piercingxx.xxdialer

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piercingxx.xxdialer.data.XxDatabase
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * design.md §16, instrumented bullet "Room migrations" — the FactStore (§11) is
 * version 1 today with `exportSchema = false`; the moment a schema change ships,
 * it must carry a REAL migration (no destructive fallback, XxDatabase KDoc).
 *
 * WS gate: any future bump of XxDatabase version (WS4 lineage).
 *
 * v2 shipped (contact_mirror composite PK). Un-ignore on caiman after
 * exporting schemas; the production migration is [com.piercingxx.xxdialer.data.XxDb.MIGRATION_1_2].
 */
@Ignore("requires caiman — see PROBE.md")
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        XxDatabase::class.java,
        emptyList(), // AutoMigrationSpecs — none until an auto-migration ships
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** Creates + closes a clean v1 database: the baseline every future test starts from. */
    @Test
    fun createV1_baseline_database() {
        helper.createDatabase(TEST_DB, 1).close()
    }

    /**
     * The shape to copy for every future bump: build from the v1 baseline, run
     * through the shipped migration, validate the resulting schema against the
     * exported v2 JSON.
     */
    @Test
    fun migrate1_to_2_validatesAgainstExportedSchema() {
        // TODO-on-device / TODO-on-v2:
        //   helper.createDatabase(TEST_DB, 1).close()
        //   val db = helper.runMigrationsAndValidate(
        //       TEST_DB, 2, /* validateDroppedTables = */ true, MIGRATION_1_2,
        //   )
        //   // spot-check the §11 invariant that matters per column added:
        //   db.query("SELECT … FROM …").use { … }
        //   db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
