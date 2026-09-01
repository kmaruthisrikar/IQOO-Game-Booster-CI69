package com.iqoo.perfcollect.collect

import com.iqoo.perfcollect.SafeRead
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ThermalCollector {

    private data class Zone(val type: String, val tempPath: String)

    @Volatile private var zones: List<Zone>? = null

    private fun discoverZones(): List<Zone> {
        zones?.let { return it }
        val list = ArrayList<Zone>()
        try {
            val base = File("/sys/class/thermal")
            base.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { dir ->
                val type = SafeRead.read("${dir.absolutePath}/type")
                if (type != null && type.isNotEmpty()) {
                    list.add(Zone(type, "${dir.absolutePath}/temp"))
                }
            }
        } catch (e: Exception) {
            // leave list empty
        }
        list.sortBy { it.type }
        // cache only real results — an empty scan is a transient failure, retry next call
        if (list.isEmpty()) return list
        zones = list
        return list
    }

    fun collect(out: JSONObject) {
        val arr = JSONArray()
        val zones = discoverZones()
        for (z in zones) {
            val temp = SafeRead.readLong(z.tempPath) ?: continue
            arr.put(JSONObject().put("t", z.type).put("v", temp))
        }
        out.put("thermal", arr)
    }

    fun zoneCount(): Int = discoverZones().size

    fun zoneValue(type: String): Long? {
        val z = discoverZones().firstOrNull { it.type.equals(type, ignoreCase = true) } ?: return null
        return SafeRead.readLong(z.tempPath)
    }

    /** max temp across zones whose type contains any of the given substrings (e.g. "mdmss", "ltepa_ntc").
     *  Excludes trip/threshold pseudo-zones (e.g. cpu-hw-trip = fixed shutdown limit, not a sensor). */
    fun zoneMaxBySubstring(substrings: List<String>): Long? {
        val vals = discoverZones()
            .filter { z -> substrings.any { z.type.contains(it, ignoreCase = true) } && !z.type.contains("trip", ignoreCase = true) }
            .mapNotNull { SafeRead.readLong(it.tempPath) }
            .filter { it > 0 }
        return vals.maxOrNull()
    }
}