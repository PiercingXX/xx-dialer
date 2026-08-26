package com.piercingxx.xxdialer.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Process-wide database builder (§14). No destructive fallback. */
object XxDb {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS contact_mirror_v2 (
                    lookupKey TEXT NOT NULL,
                    e164 TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    starred INTEGER NOT NULL,
                    customRingtone TEXT,
                    sendToVoicemail INTEGER NOT NULL,
                    refreshedAt INTEGER NOT NULL,
                    PRIMARY KEY(lookupKey, e164)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contact_mirror_v2
                    (lookupKey, e164, displayName, starred, customRingtone, sendToVoicemail, refreshedAt)
                SELECT lookupKey, e164, displayName, starred, customRingtone, sendToVoicemail, refreshedAt
                FROM contact_mirror
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE contact_mirror")
            db.execSQL("ALTER TABLE contact_mirror_v2 RENAME TO contact_mirror")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_mirror_e164 ON contact_mirror (e164)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_mirror_lookupKey ON contact_mirror (lookupKey)")
        }
    }

    fun build(context: Context): XxDatabase =
        Room.databaseBuilder(context.applicationContext, XxDatabase::class.java, XxDatabase.NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
}
