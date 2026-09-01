package com.iqoo.perfcollect.collect

import android.os.SystemClock
import com.iqoo.perfcollect.SafeRead
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

class CpuCollector {
    private var lastTotal = 0L
    private var lastIdle = 0L
    private var initialized = false

    // cpuidle idle-time tracking (works even where /proc/stat is SELinux-denied)
    private var lastTickMs = 0L
    private val prevIdleUs = HashMap<Int, Long>()

    // time_in_state freq residency tracking (per cpufreq policy: policy0=cpu0-5, policy6=cpu6-7)
    private val prevPolicyState = HashMap<Int, Map<Long, Long>>()

    private fun readIdleUs(core: Int): Long {
        var total = 0L
        var state = 0
        while (true) {
            val f = File("/sys/devices/system/cpu/cpu$core/cpuidle/state$state/time")
            if (!f.exists()) break
            val v = try { SafeRead.readLong(f.path) } catch (_: Exception) { null }
                ?: try { f.readText().trim().toLongOrNull() } catch (_: Exception) { null }
            if (v == null) break
            total += v
            state++
        }
        return total
    }

    private fun readTimeInState(policyCore: Int): Map<Long, Long> {
        val map = HashMap<Long, Long>()
        try {
            File("/sys/devices/system/cpu/cpu$policyCore/cpufreq/stats/time_in_state").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val freq = parts[0].toLongOrNull() ?: return@forEachLine
                    val ticks = parts[1].toLongOrNull() ?: return@forEachLine
                    map[freq] = ticks
                }
            }
        } catch (e: Exception) {
            // not available
        }
        return map
    }

    fun collect(out: JSONObject) {
        val cpu = JSONObject()

        val freqs = JSONObject()
        val online = JSONObject()
        for (i in 0..7) {
            val f = SafeRead.readInt("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (f != null) freqs.put("cpu$i", f)
            val on = SafeRead.readInt("/sys/devices/system/cpu/cpu$i/online")
            if (on != null) online.put("cpu$i", on)
        }
        cpu.put("freq_khz", freqs)
        cpu.put("online", online)

        // --- cpuidle based utilization (SELinux-safe; time_in_state on this kernel
        //     counts wall-clock incl. idle, so it cannot yield utilization) ---
        val nowMs = SystemClock.elapsedRealtime()
        val windowMs = if (lastTickMs > 0) (nowMs - lastTickMs).toDouble() else 0.0
        val utilByCore = JSONObject()
        val idleUsByCore = JSONObject()
        var utilSum = 0.0
        var onlineCores = 0

        for (i in 0..7) {
            val idle = readIdleUs(i)
            if (idle == 0L) continue
            val prev = prevIdleUs[i]
            if (prev != null && windowMs > 0) {
                if (idle < prev) {
                    prevIdleUs[i] = idle
                    continue
                }
                val dIdleUs = idle - prev
                val idlePct = dIdleUs / (windowMs * 1000.0) * 100.0
                val util = (100.0 - idlePct).coerceIn(0.0, 100.0)
                utilByCore.put("cpu$i", Math.round(util * 100.0) / 100.0)
                idleUsByCore.put("cpu$i", dIdleUs)
                if (online.optInt("cpu$i", 1) == 1) {
                    utilSum += util
                    onlineCores++
                }
            }
            prevIdleUs[i] = idle
        }
        lastTickMs = nowMs

        if (utilByCore.keys().hasNext()) {
            cpu.put("util_pct_core", utilByCore)
        }
        if (idleUsByCore.keys().hasNext()) {
            cpu.put("idle_us_core", idleUsByCore)
        }
        if (onlineCores > 0) {
            cpu.put("util_pct_avg", Math.round(utilSum / onlineCores * 100.0) / 100.0)
            cpu.put("total_load_pct", Math.round(utilSum * 100.0) / 100.0)
        }

        // --- time_in_state freq residency per cpufreq policy (weighted average freq) ---
        val wfreqByPolicy = JSONObject()
        val residencyByPolicy = JSONObject()
        val policyDir = File("/sys/devices/system/cpu/cpufreq")
        val discovered = policyDir.listFiles { f -> f.name.startsWith("policy") }?.mapNotNull { f ->
            val core = f.name.removePrefix("policy").toIntOrNull() ?: return@mapNotNull null
            f.name to core
        }?.sortedBy { it.second }
        val policies = if (!discovered.isNullOrEmpty()) discovered else listOf("policy0" to 0, "policy6" to 6)
        for ((policyName, policyCore) in policies) {
            val cur = readTimeInState(policyCore)
            if (cur.isEmpty()) continue
            val prev = prevPolicyState[policyCore]
            if (prev != null) {
                var totalTicks = 0L
                var weighted = 0.0
                for ((f, ticksNow) in cur) {
                    val ticksPrev = prev[f] ?: 0L
                    val d = (ticksNow - ticksPrev).coerceAtLeast(0)
                    totalTicks += d
                    weighted += f * d
                }
                if (totalTicks > 0) {
                    wfreqByPolicy.put(policyName, Math.round(weighted / totalTicks))
                    // dominant freq of the WINDOW (delta vs prev), not boot-time absolute max;
                    // skip entries with no prev sample, fall back to weighted freq when no delta
                    var topFreq = -1L
                    var topDelta = -1L
                    for ((f, ticksNow) in cur) {
                        val p = prev[f] ?: continue
                        val d = (ticksNow - p).coerceAtLeast(0L)
                        if (d > topDelta) { topDelta = d; topFreq = f }
                    }
                    residencyByPolicy.put(policyName, if (topFreq >= 0 && topDelta > 0) topFreq else Math.round(weighted / totalTicks))
                }
            }
            prevPolicyState[policyCore] = cur
        }
        if (wfreqByPolicy.keys().hasNext()) {
            cpu.put("wfreq_khz_policy", wfreqByPolicy)
            cpu.put("residency_freq_khz_policy", residencyByPolicy)
        }

        // --- best-effort /proc/stat (works on AOSP; denied on vivo) ---
        val total = SafeRead.firstLine("/proc/stat")
        if (total != null) {
            val parts = total.split(Regex("\\s+"))
            if (parts.size >= 5 && parts[0] == "cpu") {
                // defensive column access: steal/guest may be absent on short lines
                fun col(n: Int): Long = if (n < parts.size) parts[n].toLongOrNull() ?: 0L else 0L
                var sum = 0L
                for (i in 1..8) sum += col(i)
                val idle = col(4) + col(5)
                if (initialized) {
                    val dTotal = sum - lastTotal
                    val dIdle = idle - lastIdle
                    if (dTotal > 0) {
                        val usage = ((1.0 - dIdle.toDouble() / dTotal.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                        cpu.put("usage_pct_procstat", Math.round(usage * 100.0) / 100.0)
                    }
                }
                lastTotal = sum
                lastIdle = idle
                initialized = true
            }
        }

        val la = SafeRead.read("/proc/loadavg")
        if (la != null) {
            val p = la.split(" ")
            if (p.size >= 3) {
                cpu.put("load1", p[0].toDoubleOrNull() ?: 0.0)
                cpu.put("load5", p[1].toDoubleOrNull() ?: 0.0)
                cpu.put("load15", p[2].toDoubleOrNull() ?: 0.0)
            }
        }

        out.put("cpu", cpu)
    }
}