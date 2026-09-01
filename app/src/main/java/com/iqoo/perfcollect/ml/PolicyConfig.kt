package com.iqoo.perfcollect.ml

/**
 * Shared policy configuration. Must match the offline training (Python).
 *  - action = qualityTier * 3 + netTier  (15 combos)
 *  - state  = [chipC, skinC, modemC, freqRatio, headroomProxy, fps, netMbps, tSec]
 *  - normalization + per-mode reward weights
 */
object PolicyConfig {
    val LOAD = floatArrayOf(0.40f, 0.55f, 0.70f, 0.85f, 1.0f)
    val N_Q = 5
    val N_NET = 3
    val N_ACTIONS = N_Q * N_NET
    val N_STATE = 8

    // per-mode reward weights: (work, skin, chip, modem, battery, net)
    val MODE_W = mapOf(
        "performance" to floatArrayOf(1.2f, 0.4f, 0.4f, 0.4f, 0.0f, 0.6f),
        "balanced" to floatArrayOf(1.0f, 1.2f, 0.9f, 0.7f, 0.25f, 0.4f),
        "battery" to floatArrayOf(0.50f, 1.5f, 1.2f, 0.9f, 1.40f, 0.15f),
        "cool" to floatArrayOf(0.35f, 2.5f, 1.8f, 1.4f, 0.50f, 0.10f),
    )

    // per-mode skin-temp knee (°C): below this, boosting is free; above it,
    // penalties ramp quadratically — the thermostat set-point per profile
    val SKIN_KNEE = mapOf(
        "performance" to 47f,
        "balanced" to 45f,
        "battery" to 42f,
        "cool" to 40f,
    )

    // normalization constants (defaults must match training; replaced by
    // assets/state_norm.npy via init() — the exporter's values win)
    val NORM_MEAN = floatArrayOf(55f, 36f, 38f, 0.9f, 60f, 60f, 40f, 60f)
    val NORM_STD = floatArrayOf(20f, 6f, 4f, 0.2f, 25f, 40f, 30f, 60f)

    /** tSec (state[7]) is clamped to [0, T_MAX_SEC] before z-scoring so
     *  multi-minute sessions don't drift out of the training distribution */
    const val T_MAX_SEC = 1800f

    @Volatile var normMean: FloatArray = NORM_MEAN; private set
    @Volatile var normStd: FloatArray = NORM_STD; private set
    @Volatile var normSource = "hardcoded"; private set
    @Volatile private var normLoaded = false

    /** load normalization constants from assets/state_norm.npy ONCE (idempotent).
     *  On any failure/shape mismatch the hardcoded defaults stay active. */
    fun init(context: android.content.Context) {
        if (normLoaded) return
        synchronized(this) {
            if (normLoaded) return
            try {
                val flat = context.assets.open("state_norm.npy").use {
                    NpyReader.readFloat32(it, 2, N_STATE)
                }
                if (flat != null && flat.size == 2 * N_STATE) {
                    val mean = flat.copyOfRange(0, N_STATE)
                    val std = flat.copyOfRange(N_STATE, 2 * N_STATE)
                    if (mean.all { it.isFinite() } && std.all { it.isFinite() && it > 0f }) {
                        normMean = mean; normStd = std; normSource = "state_norm.npy"
                        val drift = (0 until N_STATE).any {
                            kotlin.math.abs(mean[it] - NORM_MEAN[it]) > 0.05f * kotlin.math.abs(NORM_MEAN[it]) ||
                            kotlin.math.abs(std[it] - NORM_STD[it]) > 0.05f * kotlin.math.abs(NORM_STD[it])
                        }
                        android.util.Log.i("PolicyConfig",
                            "state_norm.npy loaded${if (drift) " (>5% off hardcoded — asset wins)" else ""}\n" +
                            "mean=${mean.joinToString()} \nstd=${std.joinToString()}")
                    } else android.util.Log.w("PolicyConfig", "state_norm.npy rejected (non-finite/std<=0) — using defaults")
                } else android.util.Log.w("PolicyConfig", "state_norm.npy bad/absent shape — using defaults")
            } catch (e: Exception) {
                android.util.Log.w("PolicyConfig", "state_norm.npy load failed (${e.message}) — using defaults")
            }
            normLoaded = true
        }
    }

    /** quadratic ramp after `knee`, capped at 1 (never dominates unbounded) */
    private fun softPen(t: Float, knee: Float, range: Float): Float {
        val over = (t - knee) / range
        return if (over <= 0f) 0f else (over * over).coerceAtMost(1f)
    }

    /** reward for taking `action` and landing in `state` (raw units).
     *  fps term REWARDS TRACKING THE TARGET — in this workload fps rises with
     *  intensity (duty sleep shrinks), so a capped fps/max term made q4 the
     *  rational optimum and fps exploded after training. Overshoot now costs. */
    fun reward(state: FloatArray, action: Int, mode: String, targetFps: Int = 120): Float {
        val w = MODE_W[mode] ?: MODE_W["balanced"]!!
        val intensity = LOAD[(action / N_NET).coerceIn(0, LOAD.size - 1)]
        val tgt = targetFps.coerceIn(30, 240).toFloat()
        val fpsScore = (1f - Math.abs(state[5] - tgt) / tgt).coerceIn(0f, 1f)
        // net term: rescaled against 30 Mbps (mobile-game realistic ceiling) —
        // 100Mbps ceiling saturates on any Wi-Fi and carried zero signal.
        // TODO: verify against actual net_mbps distribution across collected sessions.
        val netNorm = (state[6] / 30f).coerceIn(0f, 1f)
        // work term: commanded load + how close fps sits on target
        val work = 0.7f * intensity + 0.3f * fpsScore
        var pen = softPen(state[1], SKIN_KNEE[mode] ?: 45f, 8f) * w[1] +
            softPen(state[0], 90f, 15f) * w[2] +
            softPen(state[2], 50f, 12f) * w[3]
        // heat hurts more while pushing harder -> immediate tier tradeoff
        pen *= 0.6f + 0.8f * intensity
        return w[0] * work + w[5] * netNorm - pen - w[4] * intensity * 0.3f
    }

    /** per-profile prior added to Q-values at argmax time — makes profile
     *  selection steer decisions INSTANTLY (long-term calibration still comes
     *  from per-profile training). Small nudges; learned Q still dominates. */
    val PROFILE_BIAS = mapOf(
        "performance" to floatArrayOf(-0.40f, -0.10f, 0.30f, 0.70f, 1.10f),
        "balanced" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
        "battery" to floatArrayOf(0.60f, 0.24f, -0.04f, -0.30f, -0.60f),
        "cool" to floatArrayOf(1.00f, 0.40f, -0.20f, -0.60f, -1.00f),
    )

    /** user-tunable tilt (Weights page) overrides PROFILE_BIAS when present */
    fun biasFor(prefs: android.content.SharedPreferences, mode: String): FloatArray {
        val def = PROFILE_BIAS[mode] ?: PROFILE_BIAS["balanced"]!!
        return FloatArray(N_Q) {
            val v = prefs.getFloat("bias_${mode}_$it", def[it])
            if (v.isFinite()) v else def[it]
        }
    }

    /** adapt the canonical 8-dim state to whatever input width the engine has:
     *  truncate if wider, zero-pad if narrower */
    fun fitInput(eng: KotlinMlpEngine, normalizedState: FloatArray): FloatArray = when {
        eng.nIn == normalizedState.size -> normalizedState
        eng.nIn < normalizedState.size -> normalizedState.copyOf(eng.nIn)
        else -> normalizedState + FloatArray(eng.nIn - normalizedState.size)
    }

    /** engine forward + z-normalized Q (keeps learned dynamics) + profile tilt.
     *  Works with ANY engine dims: argmax over min(nOut, N_ACTIONS) actions;
     *  if the net has fewer outputs than actions, missing tiers fall back to
     *  the profile prior so control never dies. */
    fun chooseAction(eng: KotlinMlpEngine, normalizedState: FloatArray, prefs: android.content.SharedPreferences, mode: String): Int {
        val x = fitInput(eng, normalizedState)
        val q = eng.qValues(x)
        val usable = minOf(q.size, N_ACTIONS)
        val bias = biasFor(prefs, mode)
        // defensive: non-finite anywhere in Q (NaN or Inf, corrupt weights) → profile prior action
        if (usable == 0 || q.any { !it.isFinite() }) {
            val t = bias.indices.maxByOrNull { bias[it] } ?: 2
            return (t * N_NET).coerceAtMost(N_ACTIONS - 1)
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
            val qTier = (i / N_NET).coerceIn(0, N_Q - 1)
            val nTier = (i % N_NET).coerceIn(0, N_NET - 1)
            val b = bias[qTier] + netBias[nTier]
            val z = if (sd > 1e-6f && sd.isFinite()) (q[i] - mu) / sd else 0f
            val score = z + b
            if (score > bestV) { bestV = score; best = i }
        }
        return best.coerceIn(0, N_ACTIONS - 1)
    }

    fun normalize(state: FloatArray): FloatArray {
        val s = FloatArray(N_STATE) { if (it < state.size) state[it] else 0f }
        s[7] = s[7].coerceIn(0f, T_MAX_SEC)
        val mean = normMean; val std = normStd
        return FloatArray(N_STATE) {
            val v = s[it]
            val m = mean.getOrElse(it) { 0f }
            val d = maxOf(std.getOrElse(it) { 1f }, 1e-6f)
            val z = if (v.isFinite() && m.isFinite() && d.isFinite()) (v - m) / d else 0f
            if (z.isFinite()) z.coerceIn(-10f, 10f) else 0f
        }
    }

    /** physical plausibility gate for a RAW 8-dim state — rejects poisoned or
     *  glitched rows before they enter training data */
    fun plausibleState(s: FloatArray): Boolean =
        s.size == 8 && s[0] in -10f..130f && s[1] in -10f..130f && s[2] in -10f..130f &&
            s[3] in 0f..2f && s[4] in 0f..100f && s[5] in 0f..500f &&
            s[6] in 0f..2000f && s[7].isFinite() && s[7] in 0f..86400f

    /** profile-aware ADPF boost target: eases with heat at mode-specific rates
     *  (Perf holds hardest, Cool backs off soonest) — the model's frequency lever */
    fun effectiveBoostFps(targetFps: Int, mode: String, skinC: Float, chipC: Float, qualityScale: Float = 1f, panelMaxHz: Float = 144f): Int {
        val knee = SKIN_KNEE[mode] ?: 45f
        val overSkin = (skinC - knee).coerceAtLeast(0f)
        val overChip = (chipC - 85f).coerceAtLeast(0f)
        val relaxPerC = when (mode) {
            "performance" -> 0.6f
            "balanced" -> 1.4f
            "battery" -> 2.2f
            else -> 3.0f
        }
        val scale = ((1f - (overSkin + overChip) * relaxPerC / 100f) * qualityScale).coerceIn(0.4f, 1f)
        val raw = (targetFps * scale).toInt()
        return raw.coerceIn(30, panelMaxHz.toInt().coerceAtLeast(30))
    }
}