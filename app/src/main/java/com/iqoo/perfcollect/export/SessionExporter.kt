package com.iqoo.perfcollect.export

import android.content.Context
import android.util.Log
import com.iqoo.perfcollect.data.CursorLike
import com.iqoo.perfcollect.data.SessionMeta
import com.iqoo.perfcollect.data.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionExporter(private val context: Context) {

    companion object {
        private const val TAG = "Exporter"

        fun targetExportDir(context: Context): File =
            try { com.iqoo.perfcollect.Storage.baseDir(context).apply { mkdirs() } }
            catch (e: Exception) { File(context.filesDir, "export") }
    }

    data class ExportResult(val jsonl: File?, val csv: File?, val eventsCsv: File?)

    fun exportSession(session: SessionMeta): ExportResult {
        return try {
            val store = SessionStore(context)
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val name = "session_${session.id}_${fmt.format(Date(session.startedAt))}"

            val jsonl = exportJsonl(store, session, name)
            val csv = exportCsv(store, session, name)
            val eventsCsv = exportEventsCsv(store, session, name)

            Log.i(TAG, "exported session ${session.id}: jsonl=$jsonl csv=$csv events=$eventsCsv")
            ExportResult(jsonl, csv, eventsCsv)
        } catch (e: Exception) {
            Log.e(TAG, "export failed: ${e.message}")
            ExportResult(null, null, null)
        }
    }

    private fun exportJsonl(store: SessionStore, session: SessionMeta, name: String): File? {
        val sessionsDir = File(targetExportDir(context), "sessions")
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        val dataFile = File(sessionsDir, "$name.jsonl")
        val tmpFile = File(sessionsDir, "$name.jsonl.tmp")
        return try {
            BufferedWriter(FileWriter(tmpFile)).use { writer ->
                store.readSessionSamples(session.id).use {
                    while (it.moveToNext()) {
                        writer.write(it.getString(2) ?: "")
                        writer.newLine()
                    }
                }
                store.readSessionEvents(session.id).use {
                    while (it.moveToNext()) {
                        // JSONObject escapes quotes/newlines — raw labels would break JSONL
                        writer.write(JSONObject().put("event", it.getString(1) ?: "").put("t_ns", it.getLong(0)).toString())
                        writer.newLine()
                    }
                }
                writer.write("{\"meta\":")
                writer.write(store.deviceInfo())
                writer.write("}")
                writer.newLine()
            }
            dataFile.delete()
            if (tmpFile.renameTo(dataFile)) {
                try { android.media.MediaScannerConnection.scanFile(context, arrayOf(dataFile.absolutePath), null, null) } catch (_: Exception) {}
                dataFile
            } else { tmpFile.delete(); null }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "jsonl export failed", e)
            null
        }
    }

    private fun exportCsv(store: SessionStore, session: SessionMeta, name: String): File? {
        val csvDir = File(targetExportDir(context), "csv")
        if (!csvDir.exists()) csvDir.mkdirs()
        val csvFile = File(csvDir, "$name.csv")
        val tmpFile = File(csvDir, "$name.csv.tmp")
        return try {
            // pass 1: discover column set from all samples
            val keySet = LinkedHashSet<String>()
            store.readSessionSamples(session.id).use { c ->
                while (c.moveToNext()) {
                    val json = c.getString(2) ?: continue
                    val flat = LinkedHashMap<String, String>()
                    flattenInto(flat, "", JSONObject(json))
                    keySet.addAll(flat.keys)
                }
            }
            val cols = keySet.sorted()
            BufferedWriter(FileWriter(tmpFile)).use { writer ->
                writer.write(cols.joinToString(",") { csvEsc(it) })
                writer.newLine()

                // pass 2: write rows
                store.readSessionSamples(session.id).use { c ->
                    while (c.moveToNext()) {
                        val json = c.getString(2) ?: continue
                        val flat = LinkedHashMap<String, String>()
                        flattenInto(flat, "", JSONObject(json))
                        val row = cols.map { flat[it] ?: "" }.joinToString(",") { csvEsc(it) }
                        writer.write(row)
                        writer.newLine()
                    }
                }
            }
            csvFile.delete()
            if (tmpFile.renameTo(csvFile)) {
                try { android.media.MediaScannerConnection.scanFile(context, arrayOf(csvFile.absolutePath), null, null) } catch (_: Exception) {}
                csvFile
            } else { tmpFile.delete(); null }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "csv export failed", e)
            null
        }
    }

    private fun exportEventsCsv(store: SessionStore, session: SessionMeta, name: String): File? {
        val csvDir = File(targetExportDir(context), "csv")
        if (!csvDir.exists()) csvDir.mkdirs()
        val eventsFile = File(csvDir, "${name}_events.csv")
        val tmpFile = File(csvDir, "${name}_events.csv.tmp")
        return try {
            BufferedWriter(FileWriter(tmpFile)).use { writer ->
                writer.write("t_ns,label")
                writer.newLine()
                store.readSessionEvents(session.id).use {
                    while (it.moveToNext()) {
                        writer.write("${it.getLong(0)},${csvEsc(it.getString(1) ?: "")}")
                        writer.newLine()
                    }
                }
            }
            eventsFile.delete()
            if (tmpFile.renameTo(eventsFile)) {
                try { android.media.MediaScannerConnection.scanFile(context, arrayOf(eventsFile.absolutePath), null, null) } catch (_: Exception) {}
                eventsFile
            } else { tmpFile.delete(); null }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "events export failed", e)
            null
        }
    }

    private fun flattenInto(out: LinkedHashMap<String, String>, key: String, v: Any?) {
        when (v) {
            is JSONObject -> {
                val it = v.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    flattenInto(out, if (key.isEmpty()) k else "$key.$k", v.opt(k))
                }
            }
            is JSONArray -> {
                var wrote = false
                for (i in 0 until v.length()) {
                    val el = v.optJSONObject(i)
                    if (el != null && el.has("t") && el.has("v")) {
                        out["$key.${el.optString("t")}"] = el.opt("v")?.toString() ?: ""
                        wrote = true
                    }
                }
                if (!wrote) out[key] = v.toString()
            }
            JSONObject.NULL, null -> out[key] = ""
            else -> out[key] = v.toString()
        }
    }

    private fun csvEsc(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
    }

    fun exportAllSessions(): List<ExportResult> {
        val store = SessionStore(context)
        val out = ArrayList<ExportResult>()
        for (s in store.listSessions()) {
            if (s.samples > 0) out.add(exportSession(s))
        }
        return out
    }
}