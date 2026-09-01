package com.iqoo.perfcollect.export

import android.content.Context
import java.io.File

/**
 * Saved-model store under /sdcard/iqoo-data/models (user-visible primary;
 * app-scoped fallback when the all-files grant is missing). Snapshot naming:
 *   trained_<mode>_<yyMMdd_HHmmss>.bin   (mode = performance/balanced/battery/cool)
 * Keep at most MAX_SNAPSHOTS snapshots (oldest evicted), plus favorites.
 */
object ModelsDir {
    const val MAX_SNAPSHOTS = 5
    const val KEY_SLOTS = "max_snapshots"
    private const val PREF = "models"
    private const val KEY_FAV = "fav_models"

    fun dir(context: Context): File = baseDir(context)

    fun maxSlots(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_SLOTS, MAX_SNAPSHOTS)

    fun setMaxSlots(context: Context, n: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_SLOTS, n).commit()
        evict(context)
    }

    /** primary store: user-visible /sdcard/iqoo-data/models when writable
     *  (drop .bin files there); app-scoped fallback otherwise */
    private fun baseDir(context: Context): File =
        com.iqoo.perfcollect.Storage.modelsDir(context)

    /** old all-files-grant location, read-only fallback for pre-2.1 snapshots */
    private fun pubLegacyDir(): File =
        File(com.iqoo.perfcollect.Storage.legacyBase(), "models")

    /** old app-scoped fallback location (pre-centralization) */
    private fun legacyDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    private fun searchDirs(context: Context): List<File> =
        listOf(baseDir(context), legacyDir(context), pubLegacyDir()).distinctBy { it.absolutePath }

    /** search app-scoped then legacy locations — regular files ONLY */
    fun findFile(context: Context, name: String): File? {
        searchDirs(context).forEach {
            val f = if (it.isDirectory) File(it, name) else it
            if (f.isFile) return f
        }
        return null
    }

    /** newest favourite snapshot belonging to `mode` (trained_<mode>_…), or null */
    fun preferredFavourite(context: Context, mode: String): String? {
        val favs = fav(context)
        val cands = favs.filter { it == mode || it.startsWith("trained_${mode}_") }
            .mapNotNull { n -> if (findFile(context, "$n.bin") != null) n else null }
        return cands.maxOrNull()
    }

    /** write-temp-then-rename so a crash mid-write never leaves a truncated model */
    fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, "${f.name}.tmp")
        try {
            f.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) { tmp.delete(); f.writeBytes(bytes) }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** text variant of atomicWrite (exports, meta, small files) */
    fun atomicWriteText(f: File, content: String) = atomicWrite(f, content.toByteArray())

    /** saves a snapshot, evicting the oldest if we exceed MAX_SNAPSHOTS */
    fun saveModel(context: Context, name: String, bytes: ByteArray): File {
        val f = File(baseDir(context), "$name.bin")
        atomicWrite(f, bytes)
        evict(context)
        return f
    }

    fun loadModel(context: Context, name: String): File? = findFile(context, "$name.bin")

    fun saveMeta(context: Context, name: String, meta: String) {
        try { atomicWrite(File(baseDir(context), "$name.json"), meta.toByteArray()) } catch (_: Exception) {}
    }

    fun getMeta(context: Context, name: String): String? =
        findFile(context, "$name.json")?.readText()

    fun listModels(context: Context): List<String> {
        val names = LinkedHashSet<String>()
        searchDirs(context).forEach { d ->
            d.listFiles()
                ?.filter { it.name.endsWith(".bin") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { names.add(it.name.removeSuffix(".bin")) }
        }
        return names.toList()
    }

    fun deleteModel(context: Context, name: String) {
        findFile(context, "$name.bin")?.delete()
        findFile(context, "$name.json")?.delete()
        removeFav(context, name)
    }

    fun deleteAll(context: Context) {
        searchDirs(context).forEach { d ->
            d.listFiles()?.forEach { it.delete() }
        }
        fav(context).forEach { removeFav(context, it) }
    }

    /** deletes the app-internal trained_<mode>.bin cache (filesDir), not just snapshots */
    fun deleteTrainedCache(context: Context) {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith("trained_") && it.name.endsWith(".bin") }
            ?.forEach { it.delete() }
    }

    fun deleteAllCaches(context: Context) {
        deleteAll(context)
        deleteTrainedCache(context)
    }

    private fun evict(context: Context) {
        val limit = maxSlots(context)
        if (limit <= 0) return
        val favs = fav(context).toSet()
        val dir = baseDir(context)
        val all = dir.listFiles()
            ?.filter { it.name.startsWith("trained_") && it.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList()
            ?: mutableListOf()
        var i = 0
        while (all.size > limit && i < all.size) {
            val name = all[i].name.removeSuffix(".bin")
            if (name in favs) {
                if (all.subList(i, all.size).all { it.name.removeSuffix(".bin") in favs }) break
                i++
                continue
            }
            val victim = all.removeAt(i)
            victim.delete()
            File(dir, "$name.json").delete()
            findFile(context, "$name.bin")?.delete()
            findFile(context, "$name.json")?.delete()
            removeFav(context, name)
        }
    }

    // ---------- favorites (cap follows the pool-size setting; 0 = unlimited) ----------

    fun fav(context: Context): List<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAV, emptySet())?.toList() ?: emptyList()

    fun isFav(context: Context, name: String): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAV, emptySet())?.contains(name) ?: false

    fun toggleFav(context: Context, name: String): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FAV, emptySet())!!.toMutableSet()
        val nowFav: Boolean
        if (set.contains(name)) { set.remove(name); nowFav = false }
        else {
            val cap = maxSlots(context)
            if (cap > 0 && set.size >= cap) return false
            set.add(name); nowFav = true
        }
        prefs.edit().putStringSet(KEY_FAV, set).commit()
        return nowFav
    }

    private fun removeFav(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FAV, emptySet())!!.toMutableSet()
        if (set.remove(name)) prefs.edit().putStringSet(KEY_FAV, set).commit()
    }
}