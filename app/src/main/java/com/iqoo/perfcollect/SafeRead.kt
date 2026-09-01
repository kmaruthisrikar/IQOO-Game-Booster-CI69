package com.iqoo.perfcollect

import android.util.Log
import java.io.File

object SafeRead {
    private const val TAG = "SafeRead"

    fun read(path: String): String? = try {
        File(path).readText().trim()
    } catch (e: Exception) {
        Log.w(TAG, "read failed $path: ${e.message}")
        null
    }

    fun readLong(path: String): Long? = read(path)?.toLongOrNull()

    fun readInt(path: String): Int? = read(path)?.toIntOrNull()

    fun readFloat(path: String): Float? = read(path)?.toFloatOrNull()

    fun firstLine(path: String): String? = try {
        File(path).bufferedReader().use { it.readLine() }
    } catch (e: Exception) {
        Log.w(TAG, "firstLine failed $path: ${e.message}")
        null
    }

    fun <T> attempt(tag: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(tag, "collector failed: ${e.message}")
        null
    }
}