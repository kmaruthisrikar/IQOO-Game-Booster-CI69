package com.iqoo.perfcollect.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PerfDb private constructor(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "perf.db"
        const val DB_VERSION = 1
        const val T_SESSIONS = "sessions"
        const val T_SAMPLES = "samples"
        const val T_EVENTS = "events"

        @Volatile private var instance: PerfDb? = null
        fun getInstance(context: Context): PerfDb =
            instance ?: synchronized(this) {
                instance ?: PerfDb(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_SESSIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at_ms INTEGER NOT NULL,
                ended_at_ms INTEGER,
                device TEXT,
                os TEXT,
                soC TEXT,
                sample_count INTEGER DEFAULT 0,
                size_bytes INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $T_SAMPLES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                t_ns INTEGER NOT NULL,
                w_ms INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $T_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                t_ns INTEGER NOT NULL,
                label TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_samples_session ON $T_SAMPLES(session_id, t_ns)")
        db.execSQL("CREATE INDEX idx_events_session ON $T_EVENTS(session_id, t_ns)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe migration: preserve existing sessions/samples on version bumps.
        // v1 → v2+: create tables if missing (no destructive drop).
        if (oldVersion < 1) {
            onCreate(db)
            return
        }
        // Copy-and-recreate placeholder for future schema changes:
        // for now ensure indexes exist after any future ALTERs.
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_samples_session ON $T_SAMPLES(session_id, t_ns)") } catch (_: Exception) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_session ON $T_EVENTS(session_id, t_ns)") } catch (_: Exception) {}
        android.util.Log.w("PerfDb", "onUpgrade $oldVersion→$newVersion: no destructive migration; data preserved")
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }
}