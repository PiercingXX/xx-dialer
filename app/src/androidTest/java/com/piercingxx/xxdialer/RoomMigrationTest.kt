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
 * SKELETON — two prerequisites before this can execute, neither of which is a
 * gradle edit on this branch:
 *  1. Baseline export: set `exportSchema = true` on XxDatabase and add
 *     `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` to :app, so the
 *     v1 JSON is emitted; then copy app/schemas → androidTest assets, e.g.:
 *         sourceSets { getByName("androidTest") { assets.srcDir("$projectDir/schemas") } }
 *     (or commit schemas/ and wire it when this test un-ignores).
 *  2. A real MIGRATION_1_2 object in data/ once v2 exists — replace the
 *     placeholder below with the production one so the test validates THE
 *     migration that ships, not a copy.
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
