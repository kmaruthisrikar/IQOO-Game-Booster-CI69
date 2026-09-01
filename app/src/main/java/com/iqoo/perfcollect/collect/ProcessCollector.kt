package com.iqoo.perfcollect.collect

import android.os.Debug
import com.iqoo.perfcollect.SafeRead
import org.json.JSONObject

class ProcessCollector {
    private var lastCpuTicks = 0L
    private var lastStatMs = 0L
    private var initialized = false
    private var lastPssMs = 0L

    fun collect(out: JSONObject) {
        val p = JSONObject()

        SafeRead.attempt("Proc") {
            val stat = SafeRead.read("/proc/self/stat")
            if (stat != null) {
                val cut = stat.indexOf(") ")
                if (cut > 0) {
                    val after = stat.substring(cut + 2).split(" ")
                    // fields after "comm)": state[0], ppid[1]... utime[11], stime[12], num_threads[17]
                    val utime = after.getOrNull(11)?.toLongOrNull() ?: 0L
                    val stime = after.getOrNull(12)?.toLongOrNull() ?: 0L
                    val threads = after.getOrNull(17)?.toIntOrNull()
                    if (threads != null) p.put("threads", threads)

                    val ticks = utime + stime
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (initialized && lastStatMs > 0) {
                        // USER_HZ=100: pct = dTicks / (100 * secs * cores) * 100 (can exceed 100 when multithreaded)
                        val secs = (nowMs - lastStatMs) / 1000.0
                        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                        if (secs > 0) {
                            val d = (ticks - lastCpuTicks).coerceAtLeast(0L)
                            val pct = (d / (100.0 * secs * cores) * 100.0).coerceIn(0.0, 100.0 * cores)
                            p.put("app_cpu_pct", Math.round(pct * 100.0) / 100.0)
                        }
                    }
                    lastCpuTicks = ticks
                    lastStatMs = nowMs
                    initialized = true
                    p.put("app_cpu_ticks", ticks)
                }
            }

            val status = SafeRead.read("/proc/self/status")
            if (status != null) {
                for (line in status.lines()) {
                    when {
                        line.startsWith("VmRSS:") -> p.put("vmrss_kb", line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull())
                        line.startsWith("VmSize:") -> p.put("vmsize_kb", line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull())
                        line.startsWith("voluntary_ctxt_switches:") -> p.put("vol_ctxt_sw", line.substringAfter(":").trim().toLongOrNull())
                        line.startsWith("nonvoluntary_ctxt_switches:") -> p.put("nvol_ctxt_sw", line.substringAfter(":").trim().toLongOrNull())
                    }
                }
            }

            val io = SafeRead.read("/proc/self/io")
            if (io != null) {
                for (line in io.lines()) {
                    when {
                        line.startsWith("read_bytes:") -> p.put("io_read_bytes", line.substringAfter(":").trim().toLongOrNull())
                        line.startsWith("write_bytes:") -> p.put("io_write_bytes", line.substringAfter(":").trim().toLongOrNull())
                        line.startsWith("rchar:") -> p.put("io_rchar", line.substringAfter(":").trim().toLongOrNull())
                        line.startsWith("wchar:") -> p.put("io_wchar", line.substringAfter(":").trim().toLongOrNull())
                    }
                }
            }

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPssMs >= 60_000L) {
                lastPssMs = now
                val pss = Debug.getPss()
                if (pss > 0) p.put("pss_kb", pss)
            }
        }

        out.put("proc", p)
    }
}