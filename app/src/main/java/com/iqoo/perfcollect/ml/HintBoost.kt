package com.iqoo.perfcollect.ml

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.SystemClock
import android.util.Log

/**
 * Wrapper around ADPF PerformanceHintManager. Public API since 35 (previously
 * @SystemApi) — with compileSdk 36 we call it DIRECTLY, guarded by SDK_INT so
 * older devices degrade to no-ops; every call is best-effort try/catch.
 *
 * Contract: reportActualWorkDuration must be called EVERY work cycle (per
 * frame) → Session.reportFrame from the load workers; updateTargetWorkDuration
 * only when the target changes → Session.updateTargetFps caches internally.
 * All session calls mutate shared binder state → synchronized.
 */
class HintBoost private constructor(private val mgr: PerformanceHintManager?) {

    class Session internal constructor(private val obj: PerformanceHintManager.Session) {
        private var lastTargetNs = 0L
        private var lastPowerEfficient = false
        private var lastStartNs = 0L

        /** per-cycle actual work report (serialized by caller); ns = mean frame
         *  duration of the aggregation window. workPeriodStart is forced strictly
         *  monotonic — the HAL rejects out-of-order periods. */
        @Synchronized fun reportFrame(actualNs: Long) {
            if (actualNs <= 0L) return
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val idealStart = now - actualNs
                    val start = if (idealStart > lastStartNs) idealStart else lastStartNs + 1L
                    if (start > now) return
                    lastStartNs = start
                    val wd = android.os.WorkDuration()
                    wd.workPeriodStartTimestampNanos = start
                    wd.actualTotalDurationNanos = actualNs
                    wd.actualCpuDurationNanos = actualNs // GPU left 0 — pure-CPU frames
                    obj.reportActualWorkDuration(wd)
                } else {
                    obj.reportActualWorkDuration(actualNs)
                }
            } catch (t: Throwable) { Log.d("HintBoost", "reportFrame: ${t.message}") }
        }

        /** updateTargetWorkDuration ONLY when fps actually changed (repeat calls are free) */
        @Synchronized fun updateTargetFps(fps: Int): Boolean {
            if (fps <= 0) return false
            val ns = 1_000_000_000L / fps
            if (ns == lastTargetNs) return true
            try {
                obj.updateTargetWorkDuration(ns)
                lastTargetNs = ns
                return true
            } catch (t: Throwable) { Log.d("HintBoost", "updateTarget: ${t.message}"); return false }
        }

        /** tell the scheduler these threads may prefer power efficiency (battery/cool modes) */
        @Synchronized fun setPowerEfficient(on: Boolean) {
            if (on == lastPowerEfficient || Build.VERSION.SDK_INT < 34) return
            try { obj.setPreferPowerEfficiency(on); lastPowerEfficient = on }
            catch (t: Throwable) { Log.d("HintBoost", "powerEfficient: ${t.message}") }
        }

        @Synchronized fun close() { try { obj.close() } catch (_: Throwable) {} }
    }

    fun createSession(tids: IntArray, targetNs: Long): Session? {
        if (tids.isEmpty() || targetNs <= 0L) return null
        val m = mgr ?: return null
        return try { m.createHintSession(tids, targetNs)?.let { Session(it) } }
        catch (t: Throwable) {
            Log.d("HintBoost", "createHintSession unavailable: ${t.message}")
            null
        }
    }

    companion object {
        fun create(context: Context): HintBoost? {
            if (Build.VERSION.SDK_INT < 31) return null
            return try {
                context.getSystemService(PerformanceHintManager::class.java)?.let { HintBoost(it) }
            } catch (t: Throwable) {
                Log.d("HintBoost", "PerformanceHintManager unavailable: ${t.message}")
                null
            }
        }
    }
}
