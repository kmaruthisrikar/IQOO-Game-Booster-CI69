package com.iqoo.perfcollect.ml

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.iqoo.perfcollect.SafeRead
import com.iqoo.perfcollect.collect.ThermalCollector
import java.io.File

/**
 * Live telemetry fusion — samples every readable real-time source each tick so the
 * RL model runs against real device data (the same signals the built-in vivo
 * boosters use):
 *   - ADPF thermal headroom % + thermal status (PowerManager, API 30+)
 *   - CPU: per-core current freq + scaling_max_freq, online cores, big-cluster ratio
 *   - GPU: kgsl gpubusy (busy/total) -> % utilisation
 *   - Thermal zones: chip = max(gpuss/ddr/cpu), skin = battery temp, modem = max(mdmss)
 *   - Battery: temp / level / charge current+voltage (BatteryManager)
 *   - Workload: fps + net (mbps/latency/loss) from the app's generators
 *   - ADPF PerformanceHint frame target/actual (set by the controller)
 * Falls back to safe defaults when a source is unavailable. Also appends a
 * per-tick sensors CSV for offline analysis/retraining.
 */
object LiveTelemetry {

    @Volatile var thermalHeadroomPct = 50f
    @Volatile var thermalStatus = 0
    @Volatile var headroomUpdatedAt = 0L
    fun isHeadroomFresh(): Boolean =
        android.os.SystemClock.elapsedRealtime() - headroomUpdatedAt < 12_000L
    @Volatile var cpuFreqMhz = 0f
    @Volatile var cpuMaxMhz = 0f
    @Volatile var cpuNomMaxMhz = 0f
    @Volatile var coresOnline = 0
    @Volatile var coresTotal = 0
    @Volatile var gpuBusyPct = 0f
    @Volatile var chipC = 0f
    @Volatile var bigCoreFreqMhz = 0f
    private var bigCores: IntArray? = null

    /** detect the fastest CPU cluster from cpufreq policies — adapts per phone
     *  (iQOO 13/15/15R have different cluster layouts & prime-core positions) */
    private fun detectBigCores(): IntArray {
        return try {
            val root = File("/sys/devices/system/cpu/cpufreq")
            var bestMax = -1L; var bestCpus = intArrayOf()
            for (pol in root.listFiles() ?: emptyArray()) {
                if (!pol.name.startsWith("policy")) continue
                val rel = SafeRead.read("${pol.path}/related_cpus") ?: continue
                val cpus = rel.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
                val m = SafeRead.readLong("${pol.path}/scaling_max_freq") ?: 0L
                if (m > bestMax) { bestMax = m; bestCpus = cpus.toIntArray() }
            }
            if (bestCpus.isNotEmpty()) bestCpus else intArrayOf(4, 5, 6, 7)
        } catch (_: Exception) { intArrayOf(4, 5, 6, 7) }
    }
    @Volatile var skinC = 0f
    @Volatile var battBcastC = 0f
    @Volatile var isPlugged = false
    @Volatile var modemC = 0f
    @Volatile var battLevel = -1f
    @Volatile var battCurrentMa = 0f
    @Volatile var battVoltageMv = 0f
    @Volatile var frameTargetMs = 0f
    @Volatile var frameActualMs = 0f
    @Volatile var signalDbm = -1
    @Volatile var signalLevel = 4
    @Volatile var isLowSignal = false

    private var pm: PowerManager? = null
    private var writer: java.io.FileWriter? = null
    private var startedAt = 0L
    private var tc: ThermalCollector? = null
    private var refs = 0
    private val lock = Any()

    /** Ref-counted so GameModeService and CollectorService can run together without
     *  clobbering each other's writer/timer. */
    fun init(context: Context) {
        synchronized(lock) {
            refs++
            if (refs == 1) {
                pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                startedAt = android.os.SystemClock.elapsedRealtime()
                tc = ThermalCollector()
            }
            if (writer == null) {
                try {
                    val f = File(context.filesDir, "gamemode_live.csv")
                    val isNew = !f.exists() || f.length() == 0L
                    writer = java.io.FileWriter(f, true)
                    if (isNew) {
                        writer?.write("t_ms,chipC,skinC,modemC,freqMhz,maxMhz,nomMaxMhz,cores,gpuPct,headroomPct,thermalStatus,fps,mbps,latMs,lossPct,frameTgtMs,frameActMs,battLevel,battCurMa,battMv,action,load,netTier\n")
                        writer?.flush()
                    }
                } catch (e: Exception) {}
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (refs > 0) refs--
            if (refs == 0) {
                try { writer?.flush(); writer?.close() } catch (e: Exception) {}
                writer = null
                startedAt = 0L
                tc = null
            }
        }
    }

    private fun toCelsius(raw: Long): Float = when {
        raw <= 0L -> 0f
        raw > 2_000L -> raw / 1000f
        raw > 200L -> raw / 10f
        else -> raw.toFloat()
    }

    /** Samples everything and returns the 8-dim policy state (unnormalized):
     *  [chipC, skinC, modemC, freqRatio, headroomProxy(0..100), fps, mbps, tSec] */
    fun sample(
        context: Context,
        fps: Float,
        mbps: Float,
        latencyMs: Float,
        lossPct: Float,
        action: Int,
        load: Float,
        netTier: Int,
        targetFps: Int,
    ): FloatArray = synchronized(lock) {
        val p = pm ?: context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm = p

        // ADPF thermal headroom (% 0..100, 100 = cold) + thermal status
        try {
            if (p != null && android.os.Build.VERSION.SDK_INT >= 30) {
                val h = p.getThermalHeadroom(0)
                if (!h.isNaN()) {
                    thermalHeadroomPct = (100f - h.coerceIn(0f, 5f) * 20f).coerceIn(0f, 100f)
                    headroomUpdatedAt = android.os.SystemClock.elapsedRealtime()
                }
            }
        } catch (e: Exception) {}
        try {
            if (p != null) thermalStatus = p.currentThermalStatus
        } catch (e: Exception) {}

        // CPU: freq ratio + online cores (+ fastest-cluster avg, detected per device)
        var freqSum = 0L; var maxSum = 0L; var nomMaxSum = 0L; var n = 0
        var bigSum = 0L; var bigN = 0
        var online = 0; var total = 0
        val curByCore = LongArray(12)
        try {
            for (i in 0..7) {
                val base = "/sys/devices/system/cpu/cpu$i"
                if (File("$base/online").exists() || (i == 0 && File("$base/cpufreq").exists())) total++
                if ((i == 0 && File("$base/cpufreq").exists()) || SafeRead.readInt("$base/online") == 1) {
                    online++
                    var cur = SafeRead.readLong("$base/cpufreq/scaling_cur_freq") ?: 0L
                    if (cur == 0L) cur = SafeRead.readLong("$base/cpufreq/cpuinfo_cur_freq") ?: 0L
                    if (cur == 0L) {
                        // policy fallback: /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq via related_cpus
                        for (pol in File("/sys/devices/system/cpu/cpufreq").listFiles() ?: emptyArray()) {
                            if (!pol.name.startsWith("policy")) continue
                            val rel = SafeRead.read("${pol.path}/related_cpus") ?: continue
                            if (rel.trim().split(Regex("\\s+")).contains(i.toString())) {
                                cur = SafeRead.readLong("${pol.path}/scaling_cur_freq") ?: cur
                                break
                            }
                        }
                    }
                    val max = SafeRead.readLong("$base/cpufreq/scaling_max_freq")
                        ?: SafeRead.readLong("$base/cpufreq/cpuinfo_max_freq") ?: 0L
                    val nomMax = SafeRead.readLong("$base/cpufreq/cpuinfo_max_freq") ?: max
                    if (i < 12) curByCore[i] = cur
                    if (cur > 0) { freqSum += cur; maxSum += max; nomMaxSum += nomMax; n++ }
                }
            }
        } catch (e: Exception) {}
        if (bigCores == null) bigCores = detectBigCores()
        for (ci in bigCores!!) {
            if (ci < 12 && curByCore[ci] > 0) { bigSum += curByCore[ci]; bigN++ }
        }
        coresOnline = online; coresTotal = total
        // hold last good to avoid 0 flicker when sysfs transiently denied
        if (n > 0) {
            cpuFreqMhz = freqSum / n / 1000f
            cpuMaxMhz = maxSum / n / 1000f
            cpuNomMaxMhz = if (nomMaxSum > 0) nomMaxSum / n / 1000f else cpuMaxMhz
        }
        bigCoreFreqMhz = if (bigN > 0) bigSum / bigN / 1000f else if (n > 0) cpuFreqMhz else bigCoreFreqMhz
        val freqRatio = if (nomMaxSum > 0) (freqSum.toFloat() / nomMaxSum).coerceIn(0f, 1f) else if (maxSum > 0) (freqSum.toFloat() / maxSum).coerceIn(0f, 1f) else 0f

        // GPU busy %
        try {
            val g = SafeRead.read("/sys/class/kgsl/kgsl-3d0/gpubusy")
            val parts = g?.trim()?.split(Regex("\\s+"))
            if (parts != null && parts.size >= 2) {
                val busy = parts[0].toLongOrNull() ?: 0L
                val totalTicks = parts[1].toLongOrNull() ?: 0L
                gpuBusyPct = if (totalTicks > 0) (busy * 100.0f / totalTicks).coerceIn(0f, 100f) else 0f
            }
        } catch (e: Exception) {}

        // Thermal zones (millidegrees -> °C)
        val collector = tc ?: ThermalCollector().also { tc = it }
        val gpu = collector.zoneMaxBySubstring(listOf("gpuss")) ?: 0L
        val ddr = collector.zoneMaxBySubstring(listOf("ddr")) ?: collector.zoneValue("ddr") ?: 0L
        val cpu = maxOf(collector.zoneMaxBySubstring(listOf("cpu-")) ?: 0L, collector.zoneMaxBySubstring(listOf("cpullc-")) ?: 0L)
        chipC = toCelsius(maxOf(gpu, ddr, cpu))
        modemC = toCelsius(collector.zoneMaxBySubstring(listOf("mdmss")) ?: 0L)

        // Battery
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                val raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                battCurrentMa = if (raw == Long.MIN_VALUE) -1f else raw / 1000f
                battVoltageMv = -1f
                if (battCurrentMa == 0f) battCurrentMa = -1f
            }
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                battLevel = if (scale > 0 && lvl >= 0) (lvl * 100f / scale) else -1f
                battBcastC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f
                isPlugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (volt > 0) battVoltageMv = volt.toFloat()
            }
        } catch (e: Exception) {}

        // Signal Quality Detection (Cellular & Wi-Fi)
        try {
            val cm = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
            val activeNet = cm?.activeNetwork
            val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
            val activeIsWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true

            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val cellLevel = if (android.os.Build.VERSION.SDK_INT >= 28 && tm != null) {
                try { tm.signalStrength?.level ?: 4 } catch (_: Exception) { 4 }
            } else 4

            if (activeIsWifi && wm != null) {
                val info = wm.connectionInfo
                val rssi = info?.rssi ?: -127
                if (rssi > -127) {
                    signalDbm = rssi
                    signalLevel = if (android.os.Build.VERSION.SDK_INT >= 30) wm.calculateSignalLevel(rssi) else android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5)
                    isLowSignal = rssi < -80
                } else {
                    signalLevel = 4; signalDbm = -75; isLowSignal = false
                }
            } else {
                signalLevel = cellLevel
                isLowSignal = cellLevel in 0..1
                signalDbm = when (cellLevel) {
                    0 -> -115; 1 -> -105; 2 -> -95; 3 -> -85; else -> -75
                }
            }
        } catch (e: Exception) {}

        // skin temp = LIVE battery-thermal zone (the sticky broadcast above can
        // sit 30-60s stale — the user-facing "temperatures are constant" bug)
        val battZone = collector.zoneValue("batt_therm")
            ?: collector.zoneValue("battery")
            ?: collector.zoneMaxBySubstring(listOf("batt")) ?: 0L
        if (battZone > 0) skinC = toCelsius(battZone)
        else if (battBcastC > 0f) skinC = battBcastC

        val targetMs = if (targetFps > 0) 1000f / targetFps else 16.6f
        val actualMs = if (fps > 0.5f) 1000f / fps else 33.3f
        frameTargetMs = targetMs; frameActualMs = actualMs

        val headroomProxy = if (isHeadroomFresh())
            thermalHeadroomPct else (100f - skinC).coerceIn(0f, 100f)

        if (startedAt == 0L) startedAt = android.os.SystemClock.elapsedRealtime()
        val tSec = ((android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)) / 1000f

        appendSensorRow(action, load, netTier, fps, mbps, latencyMs, lossPct)

        return@synchronized floatArrayOf(
            chipC, skinC, modemC, freqRatio, headroomProxy,
            fps.coerceAtLeast(0f), mbps.coerceAtLeast(0f), tSec
        )
    }

    private fun appendSensorRow(action: Int, load: Float, netTier: Int,
                                fps: Float, mbps: Float, latencyMs: Float, lossPct: Float) {
        synchronized(lock) {
            val w = writer ?: return
            try {
                w.write(String.format(java.util.Locale.US, "%d,%.1f,%.1f,%.1f,%.0f,%.0f,%.0f,%d,%.1f,%.1f,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%.2f,%d\n",
                    android.os.SystemClock.elapsedRealtime() - startedAt,
                    chipC, skinC, modemC, cpuFreqMhz, cpuMaxMhz, cpuNomMaxMhz, coresOnline, gpuBusyPct,
                    thermalHeadroomPct, thermalStatus, fps, mbps, latencyMs, lossPct,
                    frameTargetMs, frameActualMs, battLevel, battCurrentMa, battVoltageMv,
                    action, load, netTier))
                w.flush()
            } catch (e: Exception) {}
        }
    }
}