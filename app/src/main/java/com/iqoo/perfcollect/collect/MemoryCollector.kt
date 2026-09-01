package com.iqoo.perfcollect.collect

import com.iqoo.perfcollect.SafeRead
import org.json.JSONObject

object MemoryCollector {

    private val KEYS = listOf(
        "MemTotal", "MemFree", "MemAvailable", "Buffers", "Cached", "Shmem",
        "SwapTotal", "SwapFree", "Dirty", "Writeback", "Slab", "SReclaimable", "SUnreclaim"
    )

    fun collect(out: JSONObject) {
        val m = JSONObject()
        val text = SafeRead.read("/proc/meminfo")
        if (text != null) {
            for (line in text.lines()) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon)
                if (key in KEYS) {
                    val valStr = line.substring(colon + 1).trim().substringBefore(" kB").trim()
                    val value = valStr.toLongOrNull()
                    if (value != null) m.put(key, value)
                }
            }
        }
        val zram = SafeRead.firstLine("/sys/block/zram0/disksize")
        if (zram != null) m.put("zram_bytes", zram.toLongOrNull())
        out.put("mem", m)
    }
}