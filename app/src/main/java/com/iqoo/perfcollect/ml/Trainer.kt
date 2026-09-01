package com.iqoo.perfcollect.ml

import android.content.Context
import android.util.Log
import com.iqoo.perfcollect.SafeRead
import com.iqoo.perfcollect.export.ModelsDir
import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * On-device offline training (Train page): batch Q-learning from a recorded
 * game-mode trace CSV. Produces trained_<mode>.bin, which Game Mode then loads
 * (per-mode model including the network dimension).
 *
 * CSV (gamemode_trace.csv) columns (current 15-col format):
 *   t_ms,mode,action,quality,net_tier,tc_c,ts_c,tm_c,freq_ratio,fps,mbps,latency_ms,loss_pct,target_temp,reward
 * Legacy 13-col format (reward at index 12) is also accepted.
 * State is reconstructed as: [tc, ts, tm, freq_ratio, 100-ts, fps, mbps, t_ms/1000].
 */
class Trainer {
    companion object {
        private const val TAG = "Trainer"
        private val RNG = Random(System.currentTimeMillis())

        fun trainedFile(ctx: Context, mode: String) = File(ctx.filesDir, "trained_$mode.bin")

        /** device's real max CPU freq (kHz), read live — never hardcoded per phone */
        fun deviceMaxKhz(): Long {
            var m = 0L
            for (i in 0..11) {
                val v = SafeRead.read("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")?.trim()?.toLongOrNull() ?: 0L
                if (v > m) m = v
            }
            return if (m > 0) m else 3_300_000L
        }

        /** parse a trace CSV into transitions; reward computed if column missing */
        fun parseCsv(text: String, mode: String, targetFps: Int = 120): List<FloatArray> {
            var dqTotal = 0; var dqDropped = 0
            val out = ArrayList<FloatArray>(512)
            val lines = text.split('\n')
            var t0 = -1f; var prevTSec = -1f
            for (ln in lines) {
                val l = ln.trim()
                if (l.isEmpty() || l.startsWith("t_ms")) continue
                val p = l.split(',')
                if (p.size < 12) continue
                try {
                    val action = p[2].trim().toInt()
                    if (action < 0 || action >= PolicyConfig.N_ACTIONS) continue
                    val tc = p[5].trim().toFloat()
                    val ts = p[6].trim().toFloat()
                    val tm = p[7].trim().toFloat()
                    val fr = p[8].trim().toFloat()
                    val fps = p[9].trim().toFloat()
                    val mbps = p[10].trim().toFloat()
                    val tMs = (p[0].trim().toDoubleOrNull() ?: continue).toFloat()
                    val tSec = tMs / 1000f
                    if (t0 < 0f) t0 = tSec
                    else if (prevTSec >= 0f && (tSec < prevTSec || tSec - prevTSec > 30f)) t0 = tSec
                    prevTSec = tSec
                    val tRel = (tSec - t0).coerceAtLeast(0f)
                    val raw = floatArrayOf(tc, ts, tm, fr, 100f - ts, fps, mbps, tRel)
                    if (!PolicyConfig.plausibleState(raw)) { dqDropped++; continue }
                    dqTotal++
                    val r = PolicyConfig.reward(raw, action, mode, targetFps)
                    out.add(floatArrayOf(action.toFloat(), r, tc, ts, tm, fr, 100f - ts, fps, mbps, tRel))
                } catch (_: Exception) {}
            }
            Log.i("Trainer", "data-quality: accepted $dqTotal/${dqTotal + dqDropped} rows (${dqDropped} dropped as implausible)")
            return out
        }

        /** true if `text` is a game-mode RL trace (gamemode_trace.csv) */
        fun isRlTrace(text: String): Boolean {
            val header = text.split('\n').firstOrNull()?.trim() ?: return false
            if (!header.startsWith("t_ms")) return false
            if (header.contains("chipC") || header.contains("headroomPct")) return false
            if (header.contains("action") || header.contains("net_tier") || header.contains("tc_c")) return true
            for (ln in text.split('\n')) {
                val l = ln.trim()
                if (l.isEmpty() || l.startsWith("t_ms")) continue
                val p = l.split(',')
                if (p.size < 12) return false
                val a = p[2].trim().toIntOrNull() ?: return false
                return a in 0 until PolicyConfig.N_ACTIONS
            }
            return false
        }

        /** true if `text` is a Collector telemetry export (flattened JSON header) */
        fun isCollectorCsv(text: String): Boolean {
            val h = text.split('\n').firstOrNull() ?: return false
            return h.contains("battery.") || h.contains("thermal.") || h.contains("adpf.") || h.contains("cpu.")
        }

        /** RFC-4180 quote-aware CSV tokenizer: never misaligns columns on quoted commas */
        fun splitCsvLine(line: String): List<String> {
            val result = ArrayList<String>()
            var cur = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (ch == '"') {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i++
                    } else {
                        inQuotes = !inQuotes
                    }
                } else if (ch == ',' && !inQuotes) {
                    result.add(cur.toString()); cur = StringBuilder()
                } else {
                    cur.append(ch)
                }
                i++
            }
            result.add(cur.toString())
            return result
        }

        /** infer the best action for unlabelled rows using unified z-score + netBias decision */
        fun inferAction(engine: KotlinMlpEngine, state: FloatArray, mode: String): Int {
            val s = PolicyConfig.normalize(state)
            val x = PolicyConfig.fitInput(engine, s)
            val q = engine.qValues(x)
            val usable = minOf(q.size, PolicyConfig.N_ACTIONS)
            val bias = PolicyConfig.PROFILE_BIAS[mode] ?: PolicyConfig.PROFILE_BIAS["balanced"]!!
            if (usable == 0 || q.any { !it.isFinite() }) {
                val t = bias.indices.maxByOrNull { bias[it] } ?: 2
                return (t * PolicyConfig.N_NET).coerceAtMost(PolicyConfig.N_ACTIONS - 1)
            }
            var mu = 0f
            for (i in 0 until usable) mu += q[i]
            mu /= usable
            var vr = 0.0
            for (i in 0 until usable) {
                val diff = (q[i] - mu).toDouble()
                vr += diff * diff
            }
            val sd = kotlin.math.sqrt(vr / usable + 1e-6).toFloat()
            val netBias = when (mode) {
                "performance" -> floatArrayOf(-0.40f, 0.10f, 0.80f)
                "battery" -> floatArrayOf(1.00f, 0.20f, -1.00f)
                "cool" -> floatArrayOf(1.20f, -0.20f, -1.20f)
                else -> floatArrayOf(0f, 0f, 0f)
            }
            var best = 0; var bestV = Float.NEGATIVE_INFINITY
            for (i in 0 until usable) {
                val qTier = (i / PolicyConfig.N_NET).coerceIn(0, PolicyConfig.N_Q - 1)
                val nTier = (i % PolicyConfig.N_NET).coerceIn(0, PolicyConfig.N_NET - 1)
                val b = bias[qTier] + netBias[nTier]
                val z = if (sd > 1e-6f && sd.isFinite()) (q[i] - mu) / sd else 0f
                val score = z + b
                if (score > bestV) { bestV = score; best = i }
            }
            return best.coerceIn(0, PolicyConfig.N_ACTIONS - 1)
        }

        /**
         * Convert a Collector telemetry CSV into RL transitions so the model can be
         * calibrated on REAL thermal / battery / modem / CPU / network data instead
         * of the synthetic offline sim. State comes straight from the collected
         * sensors; the action is the current policy's best action (policy-iteration
         * refinement) and the reward is computed with PolicyConfig.reward.
         */
        fun parseCollectorCsv(text: String, mode: String, engine: KotlinMlpEngine, targetFps: Int = 120): List<FloatArray> {
            var dqTotal = 0; var dqDropped = 0
            val lines = text.split('\n')
            if (lines.size < 2) return emptyList()
            val colIdx = HashMap<String, Int>()
            splitCsvLine(lines[0]).forEachIndexed { i, h -> colIdx[h.trim().trim('"')] = i }
            fun cell(r: List<String>, key: String): Float? =
                colIdx[key]?.let { i -> r.getOrNull(i)?.trim()?.toFloatOrNull() }
            fun cellMax(r: List<String>, prefix: String): Float {
                var m = Float.NEGATIVE_INFINITY
                for ((k, i) in colIdx) {
                    if (k.startsWith(prefix) && !k.contains("trip") && !k.contains("threshold") && !k.contains("step") && !k.contains("hw_")) {
                        val v = r.getOrNull(i)?.trim()?.toFloatOrNull()
                        if (v != null && v > m) m = v
                    }
                }
                return if (m == Float.NEGATIVE_INFINITY) Float.NaN else m
            }
            fun freqRatio(r: List<String>): Float {
                var sum = 0f; var n = 0
                for ((k, i) in colIdx) {
                    if (k.startsWith("cpu.freq_khz.cpu")) {
                        val v = r.getOrNull(i)?.trim()?.toFloatOrNull()
                        if (v != null) { sum += v; n++ }
                    }
                }
                if (n == 0) return 1.0f
                return (sum / n / deviceMaxKhz()).coerceIn(0f, 1f)
            }
            val out = ArrayList<FloatArray>(512)
            var t0 = -1f
            var prevTs = -1f
            var prevBytes = -1f
            var headroomFresh = 0; var headroomProxy = 0
            for (idx in 1 until lines.size) {
                val l = lines[idx].trim()
                if (l.isEmpty()) continue
                val p = splitCsvLine(l)
                if (p.size < 2) continue
                try {
                    val wMs = cell(p, "w_ms") ?: (cell(p, "t_ns")?.let { it / 1e6f } ?: continue)
                    val tSec = wMs / 1000f
                    if (t0 < 0f) t0 = tSec
                    else if (prevTs >= 0f && (tSec < prevTs || tSec - prevTs > 10f)) { t0 = tSec; prevBytes = -1f }
                    // prefer the exact RL-fused columns the collector now writes
                    val rawChipC = cell(p, "rl.chip_c") ?: cellMax(p, "thermal.")
                    val chipC = if (!rawChipC.isNaN() && rawChipC > 200f) rawChipC / 1000f else rawChipC
                    val rawSkin = cell(p, "rl.skin_c") ?: cell(p, "battery.temp_c")
                    val skinC: Float = (if (rawSkin != null && !rawSkin.isNaN()) {
                        if (rawSkin > 2000f) rawSkin / 1000f else if (rawSkin > 200f) rawSkin / 10f else rawSkin
                    } else null) ?: continue
                    val rawModemC = cell(p, "rl.modem_c") ?: cell(p, "thermal.mdmss")
                    val modemC: Float = if (rawModemC != null && !rawModemC.isNaN() && rawModemC > 200f) rawModemC / 1000f else (rawModemC ?: chipC)
                    if (chipC.isNaN() || skinC < 0f) continue
                    val fr = cell(p, "rl.freq_ratio") ?: freqRatio(p)
                    val freshHdrm = cell(p, "adpf.headroom_10s")
                    val hdrmPct: Float = cell(p, "rl.headroom") ?: (freshHdrm?.let { (100f - it.coerceIn(0f, 5f) * 20f).coerceIn(0f, 100f) } ?: (100f - skinC))
                    if (freshHdrm != null) headroomFresh++ else headroomProxy++
                    val fps = cell(p, "rl.fps") ?: cell(p, "disp.refresh_rate") ?: 120f
                    val mbps = cell(p, "rl.net_mbps") ?: run {
                        val rxB = cell(p, "net.uid_rx_bytes") ?: cell(p, "net.sys_rx_bytes") ?: 0f
                        val txB = cell(p, "net.uid_tx_bytes") ?: cell(p, "net.sys_tx_bytes") ?: 0f
                        val bytes = if (rxB > 0f || txB > 0f) rxB + txB else null
                        var m = 0f
                        if (bytes != null && bytes >= 0f && prevBytes >= 0f && prevTs > 0f) {
                            val dt = (tSec - prevTs).coerceAtLeast(0.1f)
                            m = (Math.abs(bytes - prevBytes) * 8f / 1e6f / dt)
                        }
                        prevBytes = bytes ?: prevBytes
                        m
                    }
                    prevTs = tSec
                    val modem = if (modemC.isNaN()) chipC else modemC
                    val state = floatArrayOf(chipC, skinC, modem, fr, hdrmPct, fps, mbps, tSec - t0)
                    if (!PolicyConfig.plausibleState(state)) { dqDropped++; continue }
                    dqTotal++
                    // use the recorded action when present (controller was running);
                    // reward ALWAYS recomputed with the current formula
                    var ai = cell(p, "rl.action")?.toInt() ?: -1
                    if (ai !in 0 until PolicyConfig.N_ACTIONS) {
                        ai = inferAction(engine, state, mode)
                    }
                    val r = PolicyConfig.reward(state, ai, mode, targetFps)
                    out.add(floatArrayOf(ai.toFloat(), r, chipC, skinC, modem,
                        fr, hdrmPct, fps, mbps, tSec - t0))
                } catch (_: Exception) {}
            }
            Log.i("Trainer", "data-quality: accepted $dqTotal/${dqTotal + dqDropped} rows (${dqDropped} dropped as implausible) headroom fresh=$headroomFresh proxy=$headroomProxy")
            return out
        }

        /** true if `text` is the fused live-sensors CSV (gamemode_live.csv) */
        fun isLiveCsv(text: String): Boolean {
            val h = text.split('\n').firstOrNull() ?: return false
            return h.contains("chipC") && h.contains("headroomPct")
        }

        /** Convert gamemode_live.csv (same fused data as the game trace) into RL transitions. */
        fun parseLiveCsv(text: String, mode: String, engine: KotlinMlpEngine, targetFps: Int = 120): List<FloatArray> {
            var dqTotal = 0; var dqDropped = 0
            val lines = text.split('\n')
            if (lines.size < 2) return emptyList()
            val colIdx = HashMap<String, Int>()
            splitCsvLine(lines[0]).forEachIndexed { i, h -> colIdx[h.trim().trim('"')] = i }
            fun cell(r: List<String>, key: String): Float? =
                colIdx[key]?.let { i -> r.getOrNull(i)?.trim()?.toFloatOrNull() }
            val out = ArrayList<FloatArray>(512)
            var t0 = -1f
            var prevTs = -1f
            for (idx in 1 until lines.size) {
                val l = lines[idx].trim()
                if (l.isEmpty()) continue
                val p = splitCsvLine(l)
                try {
                    val tMs = cell(p, "t_ms") ?: continue
                    val tSec = tMs / 1000f
                    if (t0 < 0f) t0 = tSec
                    else if (prevTs >= 0f && (tSec < prevTs || tSec - prevTs > 10f)) t0 = tSec
                    val chipC = cell(p, "chipC") ?: continue
                    val skinC = cell(p, "skinC") ?: continue
                    val modemC = cell(p, "modemC") ?: chipC
                    val frM = cell(p, "freqMhz") ?: 0f
                    val frX = cell(p, "nomMaxMhz") ?: cell(p, "maxMhz") ?: 0f
                    val fr = if (frX > 0f) (frM / frX).coerceIn(0f, 1f) else 1f
                    val hdrm = cell(p, "headroomPct") ?: (100f - skinC)
                    val fps = cell(p, "fps") ?: 0f
                    val mbps = cell(p, "mbps") ?: 0f
                    prevTs = tSec
                    val state = floatArrayOf(chipC, skinC, modemC, fr, hdrm, fps, mbps, tSec - t0)
                    if (!PolicyConfig.plausibleState(state)) { dqDropped++; continue }
                    dqTotal++
                    var ai = cell(p, "action")?.toInt() ?: -1
                    if (ai !in 0 until PolicyConfig.N_ACTIONS) {
                        ai = inferAction(engine, state, mode)
                    }
                    val r = PolicyConfig.reward(state, ai, mode, targetFps)
                    if (r.isNaN()) continue
                    out.add(floatArrayOf(ai.toFloat(), r, chipC, skinC, modemC, fr, hdrm, fps, mbps, tSec - t0))
                } catch (_: Exception) {}
            }
            Log.i("Trainer", "data-quality: accepted $dqTotal/${dqTotal + dqDropped} rows (${dqDropped} dropped as implausible)")
            return out
        }

        /** loads an engine for `mode`:
   1) try /sdcard/iqoo-data/models/trained_<mode>.bin (favorites/user models)
   2) try ctx.filesDir/trained_<mode>.bin (previous trained file)
   3) else frozen asset */
        fun loadEngine(ctx: Context, mode: String): KotlinMlpEngine {
            // ensure normalization constants are resolved (asset npy or defaults)
            // before any training/inference path normalizes a state
            PolicyConfig.init(ctx)
            // ordered candidates, first HEALTHY one wins (NaN-poisoned files
            // from old builds are skipped automatically)
            val modelsDir = ModelsDir.dir(ctx)
            val candidates = listOf(
                File(modelsDir, "trained_$mode.bin"),
                trainedFile(ctx, mode)
            )
            for (c in candidates) {
                if (!c.exists()) continue
                try {
                    val e = KotlinMlpEngine.fromBytes(c.readBytes())
                    if (KotlinMlpEngine.isHealthy(e)) return e
                } catch (_: Throwable) {}
            }
            val asset = when (mode) {
                "performance" -> "qnet_performance.bin"
                "battery" -> "qnet_battery.bin"
                "cool" -> "qnet_cool.bin"
                else -> "qnet_balanced.bin"
            }
            return KotlinMlpEngine(ctx, asset)
        }
    }
}

/** shared mini-batch Q-learning update (hand-rolled backprop, no deps) */
object QUpdate {
    fun update(engine: KotlinMlpEngine,
               s: List<FloatArray>, a: List<Int>, r: List<Float>,
               s2: List<FloatArray>, done: List<Boolean>,
               lr: Float, gamma: Float): Float {
        val nIn = engine.nIn; val n1 = engine.n1; val n2 = engine.n2; val nOut = engine.nOut
        val w1 = engine.w1; val b1 = engine.b1
        val w2 = engine.w2; val b2 = engine.b2
        val w3 = engine.w3; val b3 = engine.b3
        val B = s.size
        var validCount = 0 // NaN rows are skipped from gradients — scale by VALID count only

        val dw1 = FloatArray(n1 * nIn); val db1 = FloatArray(n1)
        val dw2 = FloatArray(n2 * n1); val db2 = FloatArray(n2)
        val dw3 = FloatArray(nOut * n2); val db3 = FloatArray(nOut)
        var lossSum = 0f

        fun forward(x: FloatArray, h1: FloatArray, z1: FloatArray, h2: FloatArray, z2: FloatArray, q: FloatArray) {
            for (i in 0 until n1) {
                var acc = b1[i]; var j = 0
                while (j < nIn) { acc += x[j] * w1[i * nIn + j]; j++ }
                z1[i] = acc; h1[i] = if (acc > 0f) acc else 0f
            }
            for (i in 0 until n2) {
                var acc = b2[i]; var j = 0
                while (j < n1) { acc += h1[j] * w2[i * n1 + j]; j++ }
                z2[i] = acc; h2[i] = if (acc > 0f) acc else 0f
            }
            for (i in 0 until nOut) {
                var acc = b3[i]; var j = 0
                while (j < n2) { acc += h2[j] * w3[i * n2 + j]; j++ }
                q[i] = acc
            }
        }

        for (b in 0 until B) {
            val x = s[b]; val x2 = s2[b]; val ai = a[b]; val rr = r[b]; val d = done[b]
            // skip samples with non-finite values or out-of-range action
            if (ai !in 0 until nOut) continue
            var bad = false
            if (!rr.isFinite()) bad = true
            for (v in x) if (!v.isFinite()) bad = true
            for (v in x2) if (!v.isFinite()) bad = true
            if (bad) continue
            validCount++
            val h1 = FloatArray(n1); val z1 = FloatArray(n1)
            val h2 = FloatArray(n2); val z2 = FloatArray(n2)
            val q = FloatArray(nOut)
            forward(x, h1, z1, h2, z2, q)

            var target = rr
            if (!d) {
                val h1t = FloatArray(n1); val z1t = FloatArray(n1)
                val h2t = FloatArray(n2); val z2t = FloatArray(n2)
                val qt = FloatArray(nOut)
                forward(x2, h1t, z1t, h2t, z2t, qt)
                var qmax = Float.NEGATIVE_INFINITY
                for (i in 0 until nOut) if (qt[i].isFinite() && qt[i] > qmax) qmax = qt[i]
                if (qmax.isFinite()) {
                    target += gamma * qmax
                }
            }

            val err = q[ai] - target
            lossSum += err * err
            val dq = 2f * err // scale by 1/validCount after the batch loop
            val dqFull = FloatArray(nOut); dqFull[ai] = dq

            val dh2 = FloatArray(n2)
            for (j in 0 until n2) {
                var acc = 0f; var i = 0
                while (i < nOut) { acc += dqFull[i] * w3[i * n2 + j]; i++ }
                dh2[j] = acc
            }
            val dz2 = FloatArray(n2)
            for (j in 0 until n2) dz2[j] = if (h2[j] > 0f) dh2[j] else 0f

            val dh1 = FloatArray(n1)
            for (j in 0 until n1) {
                var acc = 0f; var i = 0
                while (i < n2) { acc += dz2[i] * w2[i * n1 + j]; i++ }
                dh1[j] = acc
            }
            val dz1 = FloatArray(n1)
            for (j in 0 until n1) dz1[j] = if (h1[j] > 0f) dh1[j] else 0f

            // accumulate gradients across the batch (scaled by 1/validCount later)
            for (i in 0 until nOut) {
                val dqi = dqFull[i]
                if (dqi == 0f) continue
                db3[i] += dqi
                val off = i * n2
                for (j in 0 until n2) dw3[off + j] += dqi * h2[j]
            }
            for (i in 0 until n2) {
                val dzi = dz2[i]
                if (dzi == 0f) continue
                db2[i] += dzi
                val off = i * n1
                for (j in 0 until n1) dw2[off + j] += dzi * h1[j]
            }
            for (i in 0 until n1) {
                val dzi = dz1[i]
                if (dzi == 0f) continue
                db1[i] += dzi
                val off = i * nIn
                for (j in 0 until nIn) dw1[off + j] += dzi * x[j]
            }
        }

        if (validCount == 0) return Float.NaN
        val inv = 1f / validCount
        for (i in dw1.indices) dw1[i] *= inv
        for (i in db1.indices) db1[i] *= inv
        for (i in dw2.indices) dw2[i] *= inv
        for (i in db2.indices) db2[i] *= inv
        for (i in dw3.indices) dw3[i] *= inv
        for (i in db3.indices) db3[i] *= inv
        // (kept as sum above, now divide for the returned loss)

        // global gradient-norm clipping — plain SGD on Q targets explodes without it
        var gsq = 0.0
        for (g in dw1) gsq += g.toDouble() * g.toDouble()
        for (g in db1) gsq += g.toDouble() * g.toDouble()
        for (g in dw2) gsq += g.toDouble() * g.toDouble()
        for (g in db2) gsq += g.toDouble() * g.toDouble()
        for (g in dw3) gsq += g.toDouble() * g.toDouble()
        for (g in db3) gsq += g.toDouble() * g.toDouble()
        val gnorm = Math.sqrt(gsq).toFloat()
        val scale = if (gnorm > GRAD_CLIP && gnorm.isFinite() && gnorm > 0f) GRAD_CLIP / gnorm else 1f

        for (i in 0 until n1 * nIn) w1[i] -= lr * dw1[i] * scale
        for (i in 0 until n1) b1[i] -= lr * db1[i] * scale
        for (i in 0 until n2 * n1) w2[i] -= lr * dw2[i] * scale
        for (i in 0 until n2) b2[i] -= lr * db2[i] * scale
        for (i in 0 until nOut * n2) w3[i] -= lr * dw3[i] * scale
        for (i in 0 until nOut) b3[i] -= lr * db3[i] * scale
        // poisoned weights must surface as NaN THIS batch, not later
        for (g in w1) if (!g.isFinite()) return Float.NaN
        for (g in b1) if (!g.isFinite()) return Float.NaN
        for (g in w2) if (!g.isFinite()) return Float.NaN
        for (g in b2) if (!g.isFinite()) return Float.NaN
        for (g in w3) if (!g.isFinite()) return Float.NaN
        for (g in b3) if (!g.isFinite()) return Float.NaN
        return lossSum / validCount.coerceAtLeast(1)
    }
    const val GRAD_CLIP = 5f
}

/**
 * Runs multi-epoch batch Q-learning over parsed CSV rows on a background thread.
 * onProgress(epoch, totalEpochs, loss, elapsedMs) is posted periodically.
 */
class OfflineTrainer(
    private val engine: KotlinMlpEngine,
    private val rows: List<FloatArray>,
    private val epochs: Int,
    private val lr: Float = 0.0005f,
    private val gamma: Float = 0.9f,
    private val batchSize: Int = 32,
    private val targetFps: Int = 120,
    private val mode: String = "balanced",
) {
    private val rng = java.util.Random(System.currentTimeMillis())
    @Volatile var stopped = false; private set
    var lastLoss = 0f; private set

    /** cooperative cancel: the next epoch boundary fires onError("training cancelled") */
    fun stop() { stopped = true }

    fun run(onProgress: (Int, Int, Float, Long) -> Unit, onDone: (FloatArray) -> Unit, onError: (String) -> Unit) {
        Thread({
            val losses = trainBlocking(onProgress, onError)
                ?: return@Thread
            onDone(losses)
        }, "offline-train").apply { isDaemon = true }.start()
    }

    /** synchronous training on the CALLER thread — returns losses or null on error */
    fun trainBlocking(
        onProgress: (Int, Int, Float, Long) -> Unit,
        onError: (String) -> Unit,
    ): FloatArray? {
        try {
            val n = rows.size
            if (n < batchSize) { onError("need ≥ $batchSize rows, found $n"); return null }
            val backup = engine.toBytes() // divergence rolls back to pre-training weights
            val idx = (0 until n).toMutableList()
            val start = System.currentTimeMillis()
            val losses = FloatArray(epochs)
            val sArr = Array(n) { FloatArray(engine.nIn) }
            val aArr = IntArray(n)
            val rArr = FloatArray(n)
            val s2Arr = Array(n) { FloatArray(engine.nIn) }
            val dArr = BooleanArray(n)
            for (i in 0 until n) {
                val row = rows[i]
                aArr[i] = row[0].toInt()
                val rawS = floatArrayOf(row[2], row[3], row[4], row[5], row[6], row[7], row[8], row[9])
                sArr[i] = PolicyConfig.fitInput(engine, PolicyConfig.normalize(rawS))
                if (i < n - 1) {
                    val tCurr = row[9]
                    val tNext = rows[i + 1][9]
                    // cross-session boundary: tSec reset or large gap >10s means i is terminal
                    if (tNext < tCurr || kotlin.math.abs(tNext - tCurr) > 10f) {
                        dArr[i] = true
                        s2Arr[i] = sArr[i]
                        rArr[i] = row[1]
                    } else {
                        val nx = rows[i + 1]
                        val rawS2 = floatArrayOf(nx[2], nx[3], nx[4], nx[5], nx[6], nx[7], nx[8], nx[9])
                        s2Arr[i] = PolicyConfig.fitInput(engine, PolicyConfig.normalize(rawS2))
                        rArr[i] = row[1]
                    }
                } else {
                    rArr[i] = row[1]
                    s2Arr[i] = sArr[i]; dArr[i] = true
                }
            }
            for (ep in 0 until epochs) {
                if (stopped) { onError("training cancelled"); return null }
                idx.shuffle(rng)
                var epLoss = 0f; var nb = 0
                var i = 0
                while (i < n) {
                    val e = Math.min(i + batchSize, n)
                    val bs = e - i
                    val xs = ArrayList<FloatArray>(bs); val aa = ArrayList<Int>(bs)
                    val rr = ArrayList<Float>(bs); val x2 = ArrayList<FloatArray>(bs)
                    val dd = ArrayList<Boolean>(bs)
                    for (k in i until e) {
                        val j = idx[k]
                        xs.add(sArr[j]); aa.add(aArr[j]); rr.add(rArr[j])
                        x2.add(s2Arr[j]); dd.add(dArr[j])
                    }
                    val loss = QUpdate.update(engine, xs, aa, rr, x2, dd, lr, gamma)
                    if (!loss.isFinite()) {
                        KotlinMlpEngine.restoreFrom(engine, backup) // never persist poisoned weights
                        onError("training diverged (NaN loss) — gradients clipped, weights rolled back; fewer epochs and retrain")
                        stopped = true
                        return null
                    }
                    epLoss += loss
                    nb++
                    i = e
                }
                if (nb == 0) { onError("no usable samples in this data"); stopped = true; return null }
                lastLoss = epLoss / nb
                losses[ep] = lastLoss
                if ((ep + 1) % 2 == 0 || ep == epochs - 1) {
                    onProgress(ep + 1, epochs, lastLoss, System.currentTimeMillis() - start)
                    Log.i("Trainer", "epoch ${ep + 1}/$epochs loss=$lastLoss")
                }
            }
            return losses
        } catch (e: Exception) {
            Log.e("Trainer", "train failed: ${e.message}")
            onError(e.message ?: "unknown error")
            return null
        }
    }
}