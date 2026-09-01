package com.iqoo.perfcollect.ml

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Synthetic network load actuator: the model's netTier (0..2) selects a base rate
 * (configurable pps/payload, default 80/180/360) plus a TCP latency probe to
 * host:443. AIMD adjusts the rate within the model's chosen tier based on observed
 * congestion (latency jitter, send failures, throughput stall), so the model
 * regulates traffic intensity and the generator keeps the link clean.
 *
 * The generator only ever talks to `host` (pref `net_host`, default 1.1.1.1) —
 * no VPN, no per-app tracking.
 */
class NetworkLoadGenerator(private val context: Context) {
    companion object {
        private const val TAG = "NetLoad"
    }

    private var tierBases = intArrayOf(80, 180, 360)
    private var udpPort = 12345
    private var probePortN = 443
    private var windowMsN = 20L
    private var payloadSizeN = 800

    @Volatile var activeHost = "0.0.0.0"; private set
    @Volatile var mbps = 0.0; private set
    @Volatile var latencyMs = -1f; private set
    @Volatile var packetLoss = -1f; private set
    @Volatile var lastError: String? = null; private set

    @Volatile private var running = false
    private var thread: Thread? = null
    private var probeThread: Thread? = null
    private val sockLock = Any()
    private var sock: DatagramSocket? = null
    @Volatile private var generation = 0L
    @Volatile private var host = "0.0.0.0"
    @Volatile private var tier = 0
    @Volatile private var tgtPps = 0f
    @Volatile private var isPerformanceMode = false
    fun setPerformanceMode(perf: Boolean) { isPerformanceMode = perf }

    private val basePps: Int get() {
        if (isPerformanceMode && tier == 2) return 2700 // BEST PULL: 2700*1400*8≈30Mbps ultra-low-latency line-rate in perf
        if (LiveTelemetry.isLowSignal || lossEwma.value > 30f) {
            return when (tier) {
                1 -> 25
                else -> 10
            }
        }
        return when (tier) {
            1 -> tierBases[1]
            else -> tierBases[0]
        }
    }

    private var payload = ByteArray(payloadSizeN).apply { java.util.Random().nextBytes(this) }
    private val lowSignalPayload = ByteArray(64) { 0x5A }

    @Volatile var paused = false; private set
    fun setPaused(p: Boolean) { paused = p }

    fun setHost(h: String) { if (h.isNotEmpty()) { host = h; activeHost = h } }

    fun setTier(t: Int) {
        if (t !in 0..2) return
        val prevTier = tier
        tier = t
        val newPps = basePps.toFloat()
        if (tgtPps <= 0f || t != prevTier) {
            tgtPps = newPps
            cleanSeconds = 0
        }
    }


    private val lastProbeLatency = floatArrayOf(-1f)
    private var probeFails = 0
    private var cleanSeconds = 0
    @Volatile private var metered = false
    private val jitterEwma = Ewma(0.3f, -1f)
    private val lossEwma = Ewma(0.2f, -1f)
    private var bytesSent = 0L

    private fun congestionSignals(): Boolean {
        if (isPerformanceMode && tier == 2) return false // best pull: hold line-rate unless lowSignal (handled via payload)
        // 1. high latency jitter
        val j = jitterEwma.value
        if (j > 40f) return true
        // 2. packet loss above 5%
        if (lossEwma.value > 5f) return true
        // 3. Apple Interactive-mode style RTT gate: sustained probe latency
        if (latencyMs > 250f) return true
        // 4. throughput stall detected in the sender loop
        return false
    }

    private fun aimdTick(congested: Boolean) {
        val target = basePps.toFloat()
        // Low-Data-Mode: cap only if not performance best-pull
        val effTarget = if (metered && !(isPerformanceMode && tier == 2)) Math.min(target, tierBases[1].toFloat()) else target
        if (congested && !(isPerformanceMode && tier == 2)) {
            tgtPps *= 0.85f
        } else {
            val cleanStreak = (++cleanSeconds)
            val ai = basePps * (if (cleanStreak >= 5) 0.12f else 0.06f)
            tgtPps += ai
        }
        val floor = if (isPerformanceMode && tier == 2) effTarget * 0.90f else effTarget * 0.4f
        tgtPps = tgtPps.coerceIn(floor, effTarget)
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        generation++
        val myGen = generation
        val sp = context.getSharedPreferences("gamemode", Context.MODE_PRIVATE)
        try {
            val raw = sp.getString("net_base_pps", "80,180,360") ?: "80,180,360"
            val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size == 3 && parts.all { it in 10..20_000 }) tierBases = parts.toIntArray()
            udpPort = sp.getInt("net_udp_port", 12345).coerceIn(1, 65_535)
            probePortN = sp.getInt("net_probe_port", 443).coerceIn(1, 65_535)
            payloadSizeN = sp.getInt("net_payload_b", 800).coerceIn(64, 65_507)
            windowMsN = sp.getInt("net_window_ms", 20).coerceIn(5, 500).toLong()
        } catch (_: Exception) {}
        payload = ByteArray(payloadSizeN).apply { java.util.Random().nextBytes(this) }
        tgtPps = basePps.toFloat()
        thread = Thread({
            android.net.TrafficStats.setThreadStatsTag(0xF00D)
            var s: DatagramSocket? = null
            try {
                var addr: InetAddress? = null
                var lastResolve = 0L
                val sl = DatagramSocket().apply {
                    runCatching { trafficClass = 0xB8 } // DSCP EF (Expedited Forwarding = 46 << 2, WMM AC_VO)
                    runCatching { sendBufferSize = 64 * 1024 }
                    runCatching { receiveBufferSize = 64 * 1024 }
                }
                s = sl
                synchronized(sockLock) { sock = sl }
                var last = System.nanoTime()
                var lastBytes = 0L
                var stallSamples = 0
                val keepAlivePayload = ByteArray(32) { 0x7F }
                while (running && myGen == generation) {
                    val nowMs = System.currentTimeMillis()
                    val sendHost = if (host == "0.0.0.0" || host.isEmpty()) "1.1.1.1" else host
                    if (addr == null || nowMs - lastResolve > 10_000L) {
                        lastResolve = nowMs
                        try { addr = InetAddress.getByName(sendHost) } catch (e: Exception) {
                            addr = null; lastError = e.message
                        }
                    }
                    if (paused) {
                        try {
                            if (addr != null) sl.send(DatagramPacket(keepAlivePayload, keepAlivePayload.size, addr, udpPort))
                        } catch (_: Exception) {}
                        try { Thread.sleep(300) } catch (_: InterruptedException) {}
                        last = System.nanoTime(); lastBytes = bytesSent
                        continue
                    }
                    if (addr == null) { try { Thread.sleep(500) } catch (_: InterruptedException) {}; continue }
                    val now = System.nanoTime()
                    val dt = (now - last) / 1e9
                    val activePayload = if (tier < 2 && (LiveTelemetry.isLowSignal || lossEwma.value > 15f)) lowSignalPayload else payload
                    if (dt >= 1.0) {
                        last = now
                        val inst = (bytesSent - lastBytes) * 8.0 / dt / 1e6
                        lastBytes = bytesSent
                        mbps = if (mbps == 0.0) inst else mbps * 0.7 + inst * 0.3
                        val cmdExpected = tgtPps * activePayload.size.toDouble() * 8.0 / 1e6
                        if (inst < cmdExpected * 0.25f) { stallSamples++; cleanSeconds = 0 } else { stallSamples = 0; cleanSeconds++ }
                        runCatching {
                            metered = context.getSystemService(android.net.ConnectivityManager::class.java)
                                ?.isActiveNetworkMetered ?: false
                        }
                        aimdTick(congestionSignals() || stallSamples >= 4)
                    }
                    val burst = Math.round(tgtPps / (1000.0 / windowMsN)).toInt().coerceAtLeast(1)
                    // High-grade PACED sending: spread packets evenly across the
                    // window instead of slamming them back-to-back (anti-bufferbloat)
                    val paceGapNs = if (burst > 1) (windowMsN * 1_000_000L) / burst else 0L
                    val t0 = System.nanoTime()
                    var sent = 0
                    var failed = false
                    for (i in 0 until burst) {
                        if (i > 0 && paceGapNs > 200_000L) {
                            java.util.concurrent.locks.LockSupport.parkNanos(paceGapNs)
                        }
                        try {
                            sl.send(DatagramPacket(activePayload, activePayload.size, addr, udpPort))
                            bytesSent += activePayload.size
                            sent++
                        } catch (e: Exception) {
                            failed = true
                            lastError = e.message
                            break
                        }
                    }
                    val burstMs = (System.nanoTime() - t0) / 1e6
                    val actualLossPct = if (burst > 0) ((burst - sent).toFloat() / burst * 100f) else 0f
                    lossEwma.update(actualLossPct)
                    packetLoss = lossEwma.value
                    if (failed) {
                        try { Thread.sleep(windowMsN * 2) } catch (_: InterruptedException) {}
                    } else if (burstMs < windowMsN) {
                        try { Thread.sleep(windowMsN - burstMs.toLong()) } catch (_: InterruptedException) {}
                    }
                }
            } catch (e: Exception) {
                lastError = e.message
                Log.e(TAG, "net gen failed: ${e.message}")
            } finally {
                android.net.TrafficStats.clearThreadStatsTag()
                try { s?.close() } catch (_: Exception) {}
                synchronized(sockLock) { if (sock === s) sock = null }
            }
        })
        thread!!.isDaemon = true
        thread!!.start()

        probeThread = Thread({
            android.net.TrafficStats.setThreadStatsTag(0xF00D)
            while (running && myGen == generation) {
                if (paused) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    continue
                }
                var l = -1f
                var s: Socket? = null
                val pHost = if (host == "0.0.0.0" || host.isEmpty()) "1.1.1.1" else host
                try {
                    s = Socket().apply {
                        tcpNoDelay = true
                        runCatching { trafficClass = 0xB8 }
                        runCatching { setPerformancePreferences(0, 2, 1) }
                        runCatching { sendBufferSize = 16 * 1024 }
                        runCatching { receiveBufferSize = 16 * 1024 }
                    }
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(pHost, probePortN), 600)
                    l = (System.nanoTime() - t0) / 1e6f
                } catch (_: Exception) {
                    if (probePortN == 443) {
                        try {
                            s = Socket().apply {
                                tcpNoDelay = true
                                runCatching { trafficClass = 0xB8 }
                            }
                            val t0 = System.nanoTime()
                            s.connect(InetSocketAddress(pHost, 53), 400)
                            l = (System.nanoTime() - t0) / 1e6f
                        } catch (_: Exception) {}
                    }
                } finally {
                    try { s?.close() } catch (_: Exception) {}
                }
                if (l >= 0f) {
                    if (lastProbeLatency[0] >= 0f) jitterEwma.update(Math.abs(l - lastProbeLatency[0]))
                    lastProbeLatency[0] = l
                    latencyMs = l
                    probeFails = 0
                    try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
                } else {
                    lastProbeLatency[0] = -1f
                    latencyMs = -1f
                    probeFails++
                    try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
                }
                if (probeFails >= 3 && paused.not() && tier < 2) {
                    try { Thread.sleep(2000L * probeFails.coerceAtMost(3)) } catch (_: InterruptedException) { break }
                }
            }
            android.net.TrafficStats.clearThreadStatsTag()
        })
        probeThread!!.isDaemon = true
        probeThread!!.start()
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        val t = thread; val pt = probeThread
        try { t?.interrupt(); pt?.interrupt() } catch (_: Exception) {}
        try { t?.join(1500); pt?.join(1500) } catch (_: InterruptedException) {}
        synchronized(sockLock) {
            try { sock?.close() } catch (_: Exception) {}
            sock = null
        }
        thread = null; probeThread = null
    }

    private class Ewma(private val alpha: Float, initial: Float) {
        @Volatile private var v = initial
        val value: Float get() = v
        fun update(x: Float) { if (x >= 0f) v = if (v < 0f) x else alpha * x + (1f - alpha) * v }
    }
}