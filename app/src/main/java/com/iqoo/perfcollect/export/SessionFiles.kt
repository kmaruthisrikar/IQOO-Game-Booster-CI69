package com.iqoo.perfcollect.export

import android.content.Context
import java.io.File

/**
 * Keeps exported artifacts in sync with the DB: deleting a session also deletes
 * its exported JSONL + CSV (+ events) files under /sdcard/iqoo-data.
 */
object SessionFiles {

    private fun base(context: Context): File = SessionExporter.targetExportDir(context)

    fun sessionsDir(context: Context): File = File(base(context), "sessions")
    fun csvDir(context: Context): File = File(base(context), "csv")

    fun deleteForSession(context: Context, id: Long) {
        deleteMatching(sessionsDir(context), "session_${id}_*")
        deleteMatching(csvDir(context), "session_${id}_*")
    }

    fun deleteAll(context: Context) {
        deleteMatching(sessionsDir(context), "session_*")
        deleteMatching(csvDir(context), "session_*")
    }

    private fun deleteMatching(dir: File, pattern: String) {
        if (!dir.exists()) return
        val re = Regex("^" + pattern.replace("*", ".*") + "$")
        dir.listFiles()?.forEach { f ->
            if (re.matches(f.name)) {
                try { f.delete() } catch (_: Exception) {}
            }
        }
    }
}