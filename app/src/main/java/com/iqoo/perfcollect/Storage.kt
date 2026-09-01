package com.iqoo.perfcollect

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/** Single source of truth for external storage paths.
 *  Primary = app-scoped external dir (writable with NO permission). Legacy
 *  /sdcard/iqoo-data is read + copied once so pre-2.1 models/exports survive
 *  without MANAGE_EXTERNAL_STORAGE. */
object Storage {
    const val LEGACY = "iqoo-data"

    fun baseDir(ctx: Context): File =
        ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "external")

    /** MODELS live in the user-visible /sdcard/iqoo-data/models (drop .bin files there);
     *  falls back to app-scoped automatically when the all-files grant is missing */
    fun modelsDir(ctx: Context): File {
        val pub = File(legacyBase(), "models")
        return if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                if (pub.isDirectory || pub.mkdirs()) pub else File(baseDir(ctx), "models").apply { mkdirs() }
            } else {
                File(baseDir(ctx), "models").apply { mkdirs() }
            }
        } else {
            if ((pub.isDirectory || pub.mkdirs()) && pub.canWrite()) pub
            else File(baseDir(ctx), "models").apply { mkdirs() }
        }
    }

    fun csvDir(ctx: Context) = File(baseDir(ctx), "csv").apply { mkdirs() }
    fun sessionsDir(ctx: Context) = File(baseDir(ctx), "sessions").apply { mkdirs() }
    fun benchDir(ctx: Context) = File(baseDir(ctx), "bench-results").apply { mkdirs() }

    fun legacyBase(): File = File(Environment.getExternalStorageDirectory(), LEGACY)

    /** idempotent one-time copy of legacy data into the app-scoped store.
     *  models are NOT copied here — they stay pub-primary; instead anything
     *  written to the app-scoped models dir (fallback window) converges back */
    @Synchronized
    fun migrateLegacyOnce(ctx: Context) {
        try {
            val from = legacyBase()
            if (from.isDirectory) {
                listOf("csv" to csvDir(ctx), "sessions" to sessionsDir(ctx))
                    .forEach { (sub, dst) ->
                        File(from, sub).listFiles()?.filter { it.isFile }?.forEach { f ->
                            val out = File(dst, f.name)
                            if (!out.exists() || out.length() != f.length()) {
                                try { f.copyTo(out, overwrite = true) } catch (_: Exception) {}
                            }
                        }
                    }
            }
            // converge fallback-dir models back to pub (idempotent)
            val pub = modelsDir(ctx)
            if (pub != File(baseDir(ctx), "models")) {
                File(baseDir(ctx), "models").listFiles()?.filter { it.isFile }?.forEach { f ->
                    val out = File(pub, f.name)
                    if (!out.exists() || out.length() != f.length()) {
                        try { f.copyTo(out, overwrite = true) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }
}
