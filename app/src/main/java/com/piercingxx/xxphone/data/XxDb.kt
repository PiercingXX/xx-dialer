package com.piercingxx.xxphone.data

import android.content.Context
import androidx.room.Room

/** Process-wide database builder (§14). v1 only — no destructive fallback. */
object XxDb {

    fun build(context: Context): XxDatabase =
        Room.databaseBuilder(context.applicationContext, XxDatabase::class.java, XxDatabase.NAME)
            .build()
}
