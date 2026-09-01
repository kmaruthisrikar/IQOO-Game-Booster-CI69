package com.iqoo.perfcollect.collect

import android.content.Context
import com.iqoo.perfcollect.GameModeService
import com.iqoo.perfcollect.SafeRead
import com.iqoo.perfcollect.ml.LiveTelemetry
import com.iqoo.perfcollect.ml.PolicyConfig
import org.json.JSONObject

class TickBuilder(context: Context) {

    private val ctx = context.applicationContext
    private val cpu = CpuCollector()
    private val thermal = ThermalCollector()
    private val proc = ProcessCollector()

    fun buildTick(seq: Long): String {
        // GM paused + screen off → skip the heavy sweep entirely (1-in-6 keepalive)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        if (GameModeService.controllerOn && GameModeService.paused &&
            pm?.isInteractive != true && seq % 6 != 0L) {
            val stub = JSONObject()
            stub.put("schema", 1); stub.put("seq", seq)
            stub.put("t_ns", android.os.SystemClock.elapsedRealtimeNanos())
            stub.put("w_ms", System.currentTimeMillis())
            stub.put("skip", "gm_paused")
            return stub.toString()
        }
        val root = JSONObject()
        root.put("schema", 1)
        root.put("seq", seq)
        root.put("t_ns", android.os.SystemClock.elapsedRealtimeNanos())
        root.put("w_ms", System.currentTimeMillis())

        BatteryCollector.collect(ctx, root)
        cpu.collect(root)
        MemoryCollector.collect(root)
        thermal.collect(root)
        AdpfCollector.collect(ctx, root)
        proc.collect(root)
        NetworkCollector.collect(root)
        DisplayCollector.collect(ctx, root)

        // Same fused RL state the game-mode trace records, so telemetry sessions
        // train on the identical data as gamemode_trace.csv (no row mismatch).
        val on = GameModeService.controllerOn
        val prefs = ctx.getSharedPreferences(GameModeService.PREF, Context.MODE_PRIVATE)
        val mode = prefs.getString(GameModeService.KEY_MODE, "balanced") ?: "balanced"
        val tgtFps = prefs.getInt(GameModeService.KEY_TARGET_FPS, 120)
        val st = LiveTelemetry.sample(ctx,
            if (on) GameModeService.lastFps.toFloat() else 0f,
            if (on) GameModeService.lastMbps.toFloat() else 0f,
            if (on) GameModeService.lastLatencyMs else -1f,
            if (on) GameModeService.lastPacketLoss else -1f,
            if (on) GameModeService.lastAction else -1,
            if (on) GameModeService.lastIntensity else 0f,
            if (on) GameModeService.lastNetTier else 0,
            tgtFps)
        val rl = JSONObject()
        fun safe(v: Float): Float = if (v.isFinite()) v else 0f
        rl.put("chip_c", safe(st[0])); rl.put("skin_c", safe(st[1])); rl.put("modem_c", safe(st[2]))
        rl.put("freq_ratio", safe(st[3])); rl.put("headroom", safe(st[4]))
        rl.put("fps", safe(st[5])); rl.put("net_mbps", safe(st[6])); rl.put("t_sec", safe(st[7]))
        rl.put("action", if (on) GameModeService.lastAction else -1)
        rl.put("mode", mode)
        rl.put("target_temp_c", (PolicyConfig.SKIN_KNEE[mode] ?: 45f).toDouble())
        if (on && GameModeService.lastAction in 0 until PolicyConfig.N_ACTIONS) {
            val rew = PolicyConfig.reward(st, GameModeService.lastAction, mode, GameModeService.lastTargetFps)
            rl.put("reward", if (rew.isFinite()) rew else 0f)
        }
        root.put("rl", rl)

        return root.toString()
    }

    fun lastThermalCount(): Int = SafeRead.attempt("Thermal") { thermal.zoneCount() } ?: 0
}