package com.iqoo.perfcollect.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.util.Log
import com.iqoo.perfcollect.SafeRead
import com.iqoo.perfcollect.export.SessionFiles
import org.json.JSONObject

class SessionStore(private val context: Context) {

    companion object {
        private const val TAG = "SessionStore"
        private const val MAX_SAMPLES = 500_000L
    }

    private val dbHelper: PerfDb by lazy { PerfDb.getInstance(context) }

    fun deviceInfo(): String {
        val board = SafeRead.read("/sys/devices/soc0/soc_name") ?: Build.BOARD
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        return JSONObject()
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("board", board)
            .put("soc_model", socModel)
            .put("soc_manufacturer", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "")
            .put("release", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .toString()
    }

    fun startSession(): Long = SafeRead.attempt(TAG) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("started_at_ms", System.currentTimeMillis())
            put("device", deviceInfo())
        }
        db.insertOrThrow(PerfDb.T_SESSIONS, null, cv)
    } ?: -1L

    fun endSession(sessionId: Long) {
        SafeRead.attempt(TAG) {
            val db = dbHelper.writableDatabase
            db.execSQL(
                "UPDATE ${PerfDb.T_SESSIONS} SET ended_at_ms = ?, size_bytes = ? WHERE id = ?",
                arrayOf(System.currentTimeMillis(), dbSizeBytes(), sessionId)
            )
        }
    }

    fun addSample(sessionId: Long, tNs: Long, wMs: Long, payload: String) {
        SafeRead.attempt(TAG) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("session_id", sessionId)
                put("t_ns", tNs)
                put("w_ms", wMs)
                put("payload", payload)
            }
            db.beginTransaction()
            try {
                val rowId = db.insertWithOnConflict(PerfDb.T_SAMPLES, null, cv,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                // only bump the counter when the insert actually landed
                if (rowId != -1L) {
                    db.execSQL(
                        "UPDATE ${PerfDb.T_SESSIONS} SET sample_count = sample_count + 1 WHERE id = ?",
                        arrayOf(sessionId)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun addEvent(sessionId: Long, tNs: Long, label: String) {
        SafeRead.attempt(TAG) {
            val db = dbHelper.writableDatabase
            val cv = ContentValues().apply {
                put("session_id", sessionId)
                put("t_ns", tNs)
                put("label", label)
            }
            db.insertOrThrow(PerfDb.T_EVENTS, null, cv)
        }
    }

    fun sampleCount(): Long {
        return SafeRead.attempt(TAG) {
            val db = dbHelper.readableDatabase
            db.query(PerfDb.T_SAMPLES, arrayOf("COUNT(*)"), null, null, null, null, null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        } ?: 0L
    }

    fun dbSizeBytes(): Long {
        return SafeRead.attempt(TAG) {
            val base = context.getDatabasePath(PerfDb.DB_NAME)
            // WAL mode: main file + -wal/-shm sidecars all count
            listOf(base, java.io.File(base.path + "-wal"), java.io.File(base.path + "-shm"))
                .sumOf { if (it.exists()) it.length() else 0L }
        } ?: 0L
    }

    fun sessionCount(): Int {
        return SafeRead.attempt(TAG) {
            val db = dbHelper.readableDatabase
            db.query(PerfDb.T_SESSIONS, arrayOf("COUNT(*)"), null, null, null, null, null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } ?: 0
    }

    fun listSessions(): List<SessionMeta> {
        return SafeRead.attempt(TAG) {
            val db = dbHelper.readableDatabase
            val list = ArrayList<SessionMeta>()
            db.rawQuery(
                "SELECT id, started_at_ms, ended_at_ms, sample_count, size_bytes FROM ${PerfDb.T_SESSIONS} ORDER BY id DESC LIMIT 200",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        SessionMeta(
                            id = c.getLong(0),
                            startedAt = c.getLong(1),
                            endedAt = if (c.isNull(2)) null else c.getLong(2),
                            samples = c.getLong(3),
                            sizeBytes = c.getLong(4)
                        )
                    )
                }
            }
            list
        } ?: emptyList()
    }

    /** trims oldest sessions until under MAX_SAMPLES; never touches excludeSessionId (the live one) */
    fun cleanup(excludeSessionId: Long = -1L) {
        SafeRead.attempt(TAG) {
            val db = dbHelper.writableDatabase
            var total = sampleCount()
            if (total <= MAX_SAMPLES) return@attempt
            val ids = mutableListOf<Long>()
            db.rawQuery(
                "SELECT id FROM ${PerfDb.T_SESSIONS} WHERE id != ? ORDER BY id ASC LIMIT 20",
                arrayOf(excludeSessionId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    ids.add(c.getLong(0))
                }
            }
            db.compileStatement(
                "SELECT COUNT(*) FROM ${PerfDb.T_SAMPLES} WHERE session_id = ?"
            ).use { countStmt ->
                for (sid in ids) {
                    if (total <= MAX_SAMPLES) break
                    countStmt.bindLong(1, sid)
                    total -= countStmt.simpleQueryForLong()
                    // events→samples→sessions in ONE transaction so a crash can't orphan rows
                    db.beginTransaction()
                    try {
                        db.delete(PerfDb.T_EVENTS, "session_id = ?", arrayOf(sid.toString()))
                        db.delete(PerfDb.T_SAMPLES, "session_id = ?", arrayOf(sid.toString()))
                        db.delete(PerfDb.T_SESSIONS, "id = ?", arrayOf(sid.toString()))
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    SessionFiles.deleteForSession(context, sid)
                }
            }
            Log.i(TAG, "cleanup: trimmed to $MAX_SAMPLES samples")
        }
    }

    fun deleteSession(id: Long) {
        SafeRead.attempt(TAG) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(PerfDb.T_EVENTS, "session_id = ?", arrayOf(id.toString()))
                db.delete(PerfDb.T_SAMPLES, "session_id = ?", arrayOf(id.toString()))
                db.delete(PerfDb.T_SESSIONS, "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            SessionFiles.deleteForSession(context, id)
            Log.i(TAG, "deleted session $id")
        }
    }

    fun deleteAllSessions() {
        SafeRead.attempt(TAG) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(PerfDb.T_EVENTS, null, null)
                db.delete(PerfDb.T_SAMPLES, null, null)
                db.delete(PerfDb.T_SESSIONS, null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            SessionFiles.deleteAll(context)
            Log.i(TAG, "deleted all sessions")
        }
    }

    fun readSessionSamples(sessionId: Long): CursorLike {
        val db = dbHelper.readableDatabase
        return CursorLike(
            db.rawQuery(
                "SELECT t_ns, w_ms, payload FROM ${PerfDb.T_SAMPLES} WHERE session_id = ? ORDER BY t_ns ASC",
                arrayOf(sessionId.toString())
            )
        )
    }

    fun readSessionEvents(sessionId: Long): CursorLike {
        val db = dbHelper.readableDatabase
        return CursorLike(
            db.rawQuery(
                "SELECT t_ns, label FROM ${PerfDb.T_EVENTS} WHERE session_id = ? ORDER BY t_ns ASC",
                arrayOf(sessionId.toString())
            )
        )
    }
}

data class SessionMeta(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val samples: Long,
    val sizeBytes: Long
)

class CursorLike(private val cursor: android.database.Cursor) : java.io.Closeable {
    val count: Int get() = cursor.count
    fun moveToNext(): Boolean = cursor.moveToNext()
    fun getLong(col: Int): Long = cursor.getLong(col)
    fun getString(col: Int): String? = if (cursor.isNull(col)) null else cursor.getString(col)
    override fun close() = cursor.close()
}