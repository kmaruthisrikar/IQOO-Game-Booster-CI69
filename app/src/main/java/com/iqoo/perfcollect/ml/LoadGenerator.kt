package com.iqoo.perfcollect.ml

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Game-like workload actuator: N worker threads run fixed-size "frames" of float
 * math as fast as possible. Real CPU throttling slows frames down -> measured fps
 * drops, exactly like a real game. The controller picks a quality tier that scales
 * ops-per-frame (load), trading fps vs temperature/power.
 */
class LoadGenerator(private val threads: Int = 6, private val lowPriority: Boolean = false) {

    companion object {
        private const val TAG = "LoadGen"
    }

    private val running = AtomicBoolean(false)
    private val stop = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val framesDone = AtomicLong(0)
    private val workDone = AtomicLong(0)
    private val workPerFrame = AtomicLong(0)
    private val threadList = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val tidList = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private var windowStartFps = 0L
    private var windowFrames = 0L
    @Volatile private var lastFps = 0.0

    /** OS thread ids of the running load threads (for ADPF PerformanceHint sessions) */
    fun tids(): IntArray = tidList.toIntArray()

    /** per-cycle ADPF reporter (contract: ONE serialized report per cycle window);
     *  fed the per-window MEAN frame duration by the internal aggregator thread —
     *  raw per-worker reports violate the HAL's monotonic-timestamp contract */
    @Volatile var frameReporter: ((Long) -> Unit)? = null

    // aggregation buffers drained once per window by a single reporter thread
    private val adpfNs = AtomicLong(0)
    private val adpfFrames = AtomicLong(0)
    private val adpfLock = Any()
    @Volatile private var adpfThread: Thread? = null

    /** JIT blackhole: the busy-loop result MUST be consumed or ART's optimizing
     *  compiler deletes the whole math loop after warmup (dead-code elimination)
     *  → frames collapse to ~0.6µs → fps reads millions. Volatile store keeps
     *  the computation observable. */
    @Volatile private var blackhole = 0f

    /** total frames completed since start (ADPF stall detection) */
    fun frameCount(): Long = framesDone.get()

    /** user-selectable workload multiplier (Advanced → workload %) */
    @Volatile var opsScale: Float = 1f
        set(v) { field = v.coerceIn(0.5f, 3f); workPerFrame.set(computeOps(intensity)) }

    @Volatile var intensity: Float = 0.5f
        set(v) { field = v.coerceIn(0.05f, 1.0f); workPerFrame.set(computeOps(field)) }

    private fun computeOps(intensity: Float): Long {
        val sc = opsScale.coerceIn(0.5f, 3f)
        return ((4_000_000L + (36_000_000L * intensity).toLong()) * sc * profileOpsFactor).toLong()
    }

    @Volatile private var profileOpsFactor = 1.0f
    fun setProfile(mode: String) {
        profileOpsFactor = when (mode) {
            "performance" -> 2.0f
            "balanced" -> 1.0f
            "battery" -> 0.70f
            "cool" -> 0.25f
            else -> 1.0f
        }
        workPerFrame.set(computeOps(intensity))
        val prio = when (mode) {
            "performance" -> android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            "battery", "cool" -> android.os.Process.THREAD_PRIORITY_BACKGROUND
            else -> android.os.Process.THREAD_PRIORITY_DEFAULT
        }
        val javaPrio = when (mode) {
            "performance" -> Thread.MAX_PRIORITY
            "battery", "cool" -> Thread.MIN_PRIORITY
            else -> Thread.NORM_PRIORITY
        }
        tidList.forEach { tid -> runCatching { android.os.Process.setThreadPriority(tid, prio) } }
        threadList.forEach { t -> runCatching { t.priority = javaPrio } }
    }

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        stop.set(false)
        workPerFrame.set(computeOps(intensity))
        windowStartFps = SystemClock.elapsedRealtime()
        windowFrames = framesDone.get()
        windowStartMflops = windowStartFps
        windowWork = workDone.get()
        for (i in 0 until threads) {
            val t = Thread({
                val tid = android.os.Process.myTid()
                tidList.add(tid)
                while (!stop.get()) {
                    if (paused.get()) {
                        try { Thread.sleep(300) } catch (_: InterruptedException) { if (stop.get()) break; Thread.interrupted() }
                        continue
                    }
                    val ops = workPerFrame.get()
                    var acc = 0.5f
                    var k = 0L
                    val t0 = System.nanoTime()
                    while (k < ops) {
                        acc = acc * 1.0000001f + 0.0000001f
                        acc = acc * 0.9999999f - 0.00000005f
                        k++
                    }
                    blackhole = acc // consume: prevents JIT dead-code elimination
                    framesDone.incrementAndGet()
                    workDone.addAndGet(ops)
                    val frameNs = System.nanoTime() - t0
                    if (frameNs > 0) { synchronized(adpfLock) { adpfNs.addAndGet(frameNs); adpfFrames.incrementAndGet() } }
                    // duty-cycle: lower intensity = real idle gaps, so schedutil
                    // drops core frequency instead of staying pegged at max
                    val duty = intensity.coerceIn(0.02f, 1f)
                    val sleepNs = (frameNs * (1f - duty) / duty).toLong()
                    val sleepMs = sleepNs.toLong() / 1_000_000L
                    val sleepNsRemainder = (sleepNs.toLong() % 1_000_000L).toInt().coerceIn(0, 999_999)
                    if (sleepMs > 0 || sleepNsRemainder > 100_000) {
                        try { Thread.sleep(sleepMs.coerceIn(0L, 500L), sleepNsRemainder) } catch (_: InterruptedException) { break }
                    }
                }
            }, "loadgen-$i")
            t.priority = if (lowPriority) Thread.NORM_PRIORITY else Thread.MAX_PRIORITY
            t.start()
            threadList.add(t)
        }
        startAdpfReporter()
    }

    /** single-threaded aggregator: ~25 serialized reports/s keeps HAL timestamps
     *  monotonic (6 workers calling directly get every report rejected) */
    private fun startAdpfReporter() {
        if (adpfThread?.isAlive == true) return
        adpfThread = Thread({
            while (!stop.get()) {
                try { Thread.sleep(40) } catch (_: InterruptedException) { break }
                val f: Long
                val ns: Long
                synchronized(adpfLock) {
                    f = adpfFrames.getAndSet(0)
                    ns = adpfNs.getAndSet(0)
                }
                if (f > 0L) {
                    runCatching { frameReporter?.invoke(ns / f) }
                }
            }
        }, "loadgen-adpf").apply { isDaemon = true; start() }
    }

    fun setPaused(p: Boolean) { paused.set(p) }

    /** moves all worker tids into the background cgroup → scheduler restricts
     *  them to LITTLE cores on big.LITTLE devices (huge heat cut for
     *  battery/cool profiles; never used by performance) */
    fun setBackgroundPriority() {
        tidList.forEach { tid ->
            runCatching {
                android.os.Process.setThreadPriority(
                    tid, android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        stop.set(true)
        threadList.forEach { it.interrupt() }
        threadList.forEach { t -> try { t.join(2000) } catch (_: InterruptedException) {} }
        threadList.clear()
        tidList.clear()
        try {
            adpfThread?.interrupt()
            adpfThread?.join(500)
        } catch (_: InterruptedException) {}
        adpfThread = null
        synchronized(adpfLock) { adpfNs.set(0); adpfFrames.set(0) }
        running.set(false)
    }

    /** frames/sec achieved in the last sample window — reported PER WORKER
     *  (a real game renders one frame through one pipeline; summing N parallel
     *  workers saturated the 120 cap instantly and gave the model a constant) */
    @Synchronized
    fun fps(): Double {
        val now = SystemClock.elapsedRealtime()
        val f = framesDone.get()
        val dtMs = now - windowStartFps
        if (dtMs >= 900) {
            val workers = threads.coerceAtLeast(1)
            lastFps = ((f - windowFrames).toDouble() / (dtMs / 1000.0) / workers)
            windowStartFps = now
            windowFrames = f
            if (lastFps > 200) Log.w(TAG, "fps outlier: f=$f windowFrames=$windowFrames dtMs=$dtMs workers=$workers running=${running.get()} stop=${stop.get()}")
        }
        return lastFps
    }

    /** achieved throughput MFLOP/s — intuitive gauge: rises with tier & speed */
    @Synchronized
    fun mflops(): Double {
        val now = SystemClock.elapsedRealtime()
        val w = workDone.get()
        val dtMs = now - windowStartMflops
        if (dtMs >= 900) {
            windowMf = (w - windowWork).toDouble() / (dtMs / 1000.0) / 1e6
            windowWork = w
            windowStartMflops = now
        }
        return windowMf
    }
    private var windowMf = 0.0
    private var windowWork = 0L
    private var windowStartMflops = 0L

    fun isRunning() = running.get()
}