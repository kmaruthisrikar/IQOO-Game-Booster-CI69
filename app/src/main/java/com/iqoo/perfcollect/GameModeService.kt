package com.iqoo.perfcollect

import android.app.GameManager
import android.app.GameState
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.iqoo.perfcollect.collect.ThermalCollector
import com.iqoo.perfcollect.export.ModelsDir
import com.iqoo.perfcollect.ml.HintBoost
import com.iqoo.perfcollect.ml.KotlinMlpEngine
import com.iqoo.perfcollect.ml.LiveTelemetry
import com.iqoo.perfcollect.ml.LoadGenerator
import com.iqoo.perfcollect.ml.NetworkLoadGenerator
import com.iqoo.perfcollect.ml.PolicyConfig
import com.iqoo.perfcollect.ml.Trainer
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.util.Locale

/**
 * iQOO-style Game Mode controller — PURE INFERENCE only.
 * Reads live state (thermal + fps + network), runs the (NPU-ready) policy
 * engine with the mode's best model (trained_<mode>.bin if present, else the
 * frozen asset), and steers the built-in load + network generators.
 * Training happens separately in the Train page (Trainer / OfflineTrainer).
 */
class GameModeService : Service() {

    companion object {
        private const val TAG = "GameMode"
        private const val CHANNEL_ID = "gamemode"
        private const val NOTIF_ID = 2
        const val PREF = "gamemode"
        const val KEY_MODE = "mode"
        const val KEY_ACTIVE_MODEL = "active_model"
        const val KEY_TARGET_FPS = "target_fps"
        const val ACTION_START = "com.iqoo.perfcollect.GM_START"
        const val ACTION_STOP = "com.iqoo.perfcollect.GM_STOP"
        const val ACTION_APPLY_MODEL = "com.iqoo.perfcollect.GM_APPLY_MODEL"
        const val ACTION_RELOAD = "com.iqoo.perfcollect.GM_RELOAD"
        const val ACTION_PAUSE = "com.iqoo.perfcollect.GM_PAUSE"
        const val ACTION_RESUME = "com.iqoo.perfcollect.GM_RESUME"
        const val EXTRA_MODEL = "model"
        const val EXTRA_MODE = "mode"

        private val MODE_ASSET = mapOf(
            "performance" to "qnet_performance.bin",
            "balanced" to "qnet_balanced.bin",
            "battery" to "qnet_battery.bin",
            "cool" to "qnet_cool.bin",
        )

        @Volatile var controllerOn = false; private set
        @Volatile var lastState = FloatArray(PolicyConfig.N_STATE); private set
        @Volatile var lastAction = -1; private set
        @Volatile var lastFps = 0.0; private set
        @Volatile var lastWorkMf = 0.0; private set
        @Volatile var lastTempChip = 0f; private set
        @Volatile var lastTempBatt = 0f; private set
        @Volatile var lastTempModem = 0f; private set
        @Volatile var lastIntensity = 0f; private set
        @Volatile var lastNetTier = 0; private set
        @Volatile var lastMbps = 0.0; private set
        @Volatile var lastLatencyMs = -1f; private set
        @Volatile var lastPacketLoss = -1f; private set
        @Volatile var tickCount = 0; private set
        @Volatile var lastReward = 0f; private set
        @Volatile var modelUsed = "—"; private set
        const val KEY_NET_LOAD = "net_load_enabled"
        const val KEY_SCREEN_PAUSE = "screen_pause"
        const val KEY_MIN_BATT = "min_batt_pct"
        const val KEY_TICK_MS = "tick_ms"
        const val KEY_THREADS = "load_threads"
        const val KEY_CLAMP_PERF_MIN = "clamp_perf_min"
        const val KEY_CLAMP_BATT_MAX = "clamp_batt_max"
        const val KEY_CLAMP_COOL_MAX = "clamp_cool_max"
        const val KEY_RAMP_PCT = "ramp_pct"
        const val KEY_GUARD_HOT = "guard_chip_c"
        const val KEY_GUARD_RESUME = "guard_resume_c"
        const val KEY_NET_BASE = "net_base_pps"
        const val KEY_THERMAL_GUARD_ON = "thermal_guard_on"
        const val KEY_TRACE_CAP_VALUE = "trace_cap_value"
        const val KEY_TRACE_CAP_UNIT = "trace_cap_unit"
        @Volatile var startedAtMs = 0L; private set
        @Volatile var lastGpuPct = 0f; private set
        @Volatile var lastCores = 0; private set
        @Volatile var lastHeadroom = 0f; private set
        @Volatile var lastCpuFreqMhz = 0f; private set
        @Volatile var lastPrimeFreqMhz = 0f; private set
        @Volatile var lastBattLevel = -1f; private set
        @Volatile var lastFrameTargetMs = 0f; private set
        @Volatile var lastFrameActualMs = 0f; private set
        @Volatile var lastTargetFps = 120; private set
        @Volatile var lastDisplayHz = 0f; private set

        /** what the user means by "fps": the panel's CURRENT frame rate */
        @Volatile var displayHzProvider: (() -> Float)? = null
        @Volatile var paused = false; private set
        @Volatile var liveProfile = "—"; private set
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var engine: KotlinMlpEngine? = null
    @Volatile private var loadGen: LoadGenerator? = null
    @Volatile private var netGen: NetworkLoadGenerator? = null
    @Volatile private var hintSession: HintBoost.Session? = null
    private var hintMgr: HintBoost? = null
    private val thermal = ThermalCollector()
    @Volatile private var writer: FileWriter? = null
    private val writerLock = Any()
    @Volatile private var running = false
    private var startElapsed = 0L
    private var curMode = "balanced"
    @Volatile private var userPaused = false
    @Volatile private var screenPaused = false
    private var guardLatched = false
    private var battBrakeLatched = false
    private var screenRc: android.content.BroadcastReceiver? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    // GameManager may be absent on some post-VANILLA_ICE_CREAM devices → null-guard everything
    private val gameManager: GameManager? by lazy {
        if (Build.VERSION.SDK_INT >= 33) runCatching { getSystemService(GameManager::class.java) }.getOrNull() else null
    }
    @Volatile private var lastPowerEfficient = false
    private var lastFrameCount = 0L
    private var lastFrameProgressAt = 0L
    private var lastTickAt = 0L

    /** loading vs playing signal so the platform tunes resources around us (API 33+) */
    private fun gameLoading(loading: Boolean) {
        val gm = gameManager ?: return
        runCatching {
            gm.setGameState(
                if (loading) GameState(true, GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE)
                else GameState(false, GameState.MODE_GAMEPLAY_INTERRUPTIBLE)
            )
        }
    }

    /** ADPF power-efficiency hint — applied ONLY on mode change (cached) */
    private fun applyPowerEfficient(mode: String) {
        val pe = mode == "battery" || mode == "cool"
        if (pe == lastPowerEfficient) return
        hintSession?.setPowerEfficient(pe)
        lastPowerEfficient = pe
    }

    /** QAPE presence probe (log-only): which Qualcomm perf entry points exist on this ROM */
    private fun probeQape() {
        Thread({
            val hit = listOf(
                "com.qualcomm.qti.QcFramework",
                "com.qualcomm.qti.performance.QCPerformanceSDK",
                "vendor.qti.hardware.perf2.V1_0.IPerf",
                "com.qualcomm.qti.qperf.QPerf",
            ).firstOrNull { p -> try { Class.forName(p); true } catch (_: Throwable) { false } }
            Log.i("QAPE", if (hit != null) "available: $hit" else "none-found")
        }, "qape-probe").apply { isDaemon = true }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PolicyConfig.init(this)
        recoverRefreshIfNeeded()
        // heartbeat gap detection (Part 2.4): was we killed?
        try {
            val hp = getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("gm_last_heartbeat_ms", 0L)
            if (hp > 0L) {
                val gap = SystemClock.elapsedRealtime() - hp
                val poll = getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_TICK_MS, 2000).toLong()
                if (gap > poll * 2 + 5000) Log.w(TAG, "kill-detected: gap ${gap}ms since last heartbeat (poll ${poll}ms)")
            }
        } catch (_: Exception) {}
        createChannel()
        probeQape()
        thread = HandlerThread("gamemode").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("GameMode", "onStartCommand action=${intent?.action} startId=$startId")
        // must enter foreground within the ANR window on EVERY action path (API 34+)
        startForeground(NOTIF_ID, buildNotification(if (controllerOn) "$curMode · $modelUsed" else "starting controller…"))
        when (intent?.action) {
            ACTION_STOP -> {
                stopControl()
                stopForeground(STOP_FOREGROUND_REMOVE)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPLY_MODEL -> {
                val name = intent.getStringExtra(EXTRA_MODEL)
                if (!name.isNullOrEmpty()) {
                    handler?.post {
                        if (running) {
                            val msg = applyModel(name)
                            updateNotification("$curMode · $modelUsed · live swap")
                            Log.i(TAG, msg)
                        } else {
                            getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                                .putString(KEY_ACTIVE_MODEL, name).commit()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                            stopSelf()
                        }
                    }
                    return START_STICKY
                }
                if (!running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.w(TAG, "ACTION_APPLY_MODEL received without EXTRA_MODEL — keeping active session")
                return START_STICKY
            }
            ACTION_PAUSE -> {
                if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
                userPaused = true; handler?.post { applyPause(true) }; return START_STICKY
            }
            ACTION_RESUME -> {
                if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
                userPaused = false; screenPaused = false
                handler?.post {
                    if (!guardLatched && !battBrakeLatched) {
                        applyPause(false)
                    } else {
                        updateNotification("PAUSED · thermal guard active")
                    }
                }
                return START_STICKY
            }
            ACTION_RELOAD -> {
                handler?.post {
                    if (running) { gameLoading(true); stopControl(); startControl(); Log.i(TAG, "settings reloaded live") }
                    else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
        }
        // optional live profile switch: GM_START --es mode performance
        // NOTE: the ACTIVE MODEL PERSISTS across profile switches by design —
        // a performance-trained model keeps running under battery/cool/balanced,
        // re-shaped in REAL TIME by the selected profile's RL layer (tilt bias,
        // clamp band, thermal knees, demand scale — all read fresh every tick)
        val newMode = intent?.getStringExtra(EXTRA_MODE)?.takeIf { it in MODE_ASSET.keys }
        handler?.post {
            var restartWithNewMode = false
            if (newMode != null) {
                val p = getSharedPreferences(PREF, Context.MODE_PRIVATE)
                if (p.getString(KEY_MODE, null) != newMode) {
                    p.edit().putString(KEY_MODE, newMode).commit()
                    dwellMode = "" // force instant layer re-shape, no dwell lag
                    if (running) { stopControl(); restartWithNewMode = true }
                }
            }
            if (!running || restartWithNewMode) startControl()
        }
        return START_STICKY
    }

    /** load + NaN-probe; returns null when weights are poisoned/incompatible */
    private fun safeEngine(bytes: ByteArray, tag: String): KotlinMlpEngine? = try {
        val e = KotlinMlpEngine.fromBytes(bytes)
        if (com.iqoo.perfcollect.ml.KotlinMlpEngine.isHealthy(e)) e
        else { Log.w(TAG, "$tag has NaN weights — rejected"); null }
    } catch (e: Exception) {
        Log.w(TAG, "$tag incompatible (${e.message})")
        null
    }

    private fun applyModel(name: String): String {
        gameLoading(true)
        val eng = try {
            val f = ModelsDir.loadModel(this, name)
            if (f != null) {
                val e = safeEngine(f.readBytes(), name)
                    ?: return "apply failed: $name has NaN/corrupt weights — pick another or run Model health scan"
                modelUsed = "$name.bin"
                e
            } else {
                val trained = Trainer.trainedFile(this, name).takeIf { it.isFile }?.let { safeEngine(it.readBytes(), "trained $name") }
                if (trained != null) {
                    modelUsed = "trained_$name.bin"
                    trained
                } else {
                    val asset = MODE_ASSET[name] ?: return "apply failed: unknown model $name"
                    modelUsed = asset
                    KotlinMlpEngine(this, asset)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "live swap failed: ${e.message}")
            return "apply failed: ${e.message}"
        }
        engine = eng
        // full dwell reset: the new model must express its policy on the NEXT
        // tick — no hysteresis carry-over from the previous weights
        lastAction = -1; lastAppliedQ = -1; pendingQ = -1; pendingTicks = 0
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_MODEL, name).commit()
        gameLoading(false)
        return "live-swapped to $name"
    }

    private fun setPaused(p: Boolean) {
        paused = p
        loadGen?.setPaused(p)
        netGen?.setPaused(p)
        lastNotifText = null; updateNotification(if (p) "PAUSED · $curMode · $modelUsed" else "$curMode · $modelUsed")
    }

    private fun applyPause(p: Boolean) {
        setPaused(p)
    }

    private fun registerScreenReceiver() {
        if (screenRc != null) return
        screenRc = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> if (!userPaused && getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .getBoolean(KEY_SCREEN_PAUSE, true)) {
                        screenPaused = true; handler?.post { applyPause(true) }
                    }
                    Intent.ACTION_SCREEN_ON -> if (screenPaused && !userPaused) {
                        screenPaused = false; handler?.post { applyPause(false) }
                    }
                }
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenRc, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenRc, f)
        }
    }

    private fun unregisterScreenReceiver() {
        try { screenRc?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        screenRc = null
        screenPaused = false
    }

    /** per-profile: respects user snapshot if for same mode, else loads trained_<mode>.bin */
    private fun loadEngine(prefs: android.content.SharedPreferences, mode: String): KotlinMlpEngine? {
        var activeModel = prefs.getString(KEY_ACTIVE_MODEL, null)?.takeIf { it.isNotBlank() }
        // if user selected a custom snapshot for another mode, ignore it and use per-profile
        if (activeModel != null && activeModel !in MODE_ASSET && activeModel.contains("_")) {
            val snapMode = activeModel.removePrefix("trained_").substringBefore("_").let { if (it in MODE_ASSET) it else null }
                ?: activeModel.substringBefore("_")
            if (snapMode != mode && snapMode in MODE_ASSET) {
                activeModel = null
            }
        }
        // if activeModel is a different profile's base (e.g., "performance" while mode is "cool"), prefer per-profile trained
        if (activeModel != null && activeModel in MODE_ASSET && activeModel != mode) {
            val perProfile = Trainer.trainedFile(this, mode)
            if (perProfile.isFile) {
                val e = safeEngine(perProfile.readBytes(), "trained $mode")
                if (e != null) {
                    modelUsed = "trained_${mode}.bin"
                    prefs.edit().putString(KEY_ACTIVE_MODEL, mode).apply()
                    return e
                }
            }
        }
        val explicitOk = activeModel != null && (activeModel in MODE_ASSET.keys ||
            ModelsDir.loadModel(this, activeModel!!) != null)
        if (!explicitOk) {
            activeModel = ModelsDir.preferredFavourite(this, mode) ?: mode
            prefs.edit().putString(KEY_ACTIVE_MODEL, activeModel).commit()
        }
        return try {
            when {
                // frozen mode model (or its on-device trained variant)
                activeModel in MODE_ASSET -> {
                    val trained = Trainer.trainedFile(this, activeModel)
                    if (trained.isFile) {
                        val e = safeEngine(FileInputStream(trained).readBytes(), "trained $activeModel")
                        if (e != null) { modelUsed = "trained_$activeModel.bin"; e }
                        else {
                            val asset = MODE_ASSET[activeModel]!!
                            modelUsed = asset
                            KotlinMlpEngine(this, asset)
                        }
                    } else {
                        val asset = MODE_ASSET[activeModel]!!
                        modelUsed = asset
                        KotlinMlpEngine(this, asset)
                    }
                }
                // saved snapshot (ModelsDir) — falls back to base on any load issue
                else -> {
                    val snap = ModelsDir.loadModel(this, activeModel)?.takeIf { it.isFile }
                    val snapE = snap?.let { safeEngine(FileInputStream(it).readBytes(), "snapshot $activeModel") }
                    if (snapE != null) {
                        modelUsed = "$activeModel.bin"
                        snapE
                    } else {
                        val asset = MODE_ASSET[mode] ?: MODE_ASSET["balanced"]!!
                        modelUsed = asset
                        KotlinMlpEngine(this, asset)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "engine load failed (model=${prefs.getString(KEY_ACTIVE_MODEL, "?")}): ${e.message}")
            null
        }
    }

    /** recreates generators (+ hint session) after an ungoverned stop — no profile hardcode */
    private fun restartGenerators(@Suppress("UNUSED_PARAMETER") lowPri: Boolean) {
        loadGen?.frameReporter = null
        val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        loadGen = LoadGenerator(threads = prefs.getInt(KEY_THREADS, 4), lowPriority = false).also {
            it.opsScale = prefs.getInt("workload_pct", 100).coerceIn(50, 300) / 100f
            it.setProfile(getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MODE, "balanced") ?: "balanced")
            it.start()
        }
        netGen = if (prefs.getBoolean(KEY_NET_LOAD, true)) NetworkLoadGenerator(this).also {
            it.setHost(prefs.getString("net_host", "1.1.1.1")!!)
            it.setPerformanceMode(getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MODE, "balanced") == "performance")
            it.start()
        } else null
        hintMgr = hintMgr ?: HintBoost.create(this)
        hintSession?.close()
        hintSession = null
        createHintSession()
    }

    private fun startControl() {
        running = true
        controllerOn = true
        tickCount = 0
        userPaused = false
        screenPaused = false
        guardLatched = false
        battBrakeLatched = false
        lastAppliedQ = -1; pendingQ = -1; pendingTicks = 0
        dwellMode = ""
        appliedIntensity = PolicyConfig.LOAD[1]
        lastFps = 0.0
        lastWorkMf = 0.0
        lastMbps = 0.0
        lastLatencyMs = -1f
        lastPacketLoss = -1f
        lastReward = 0f
        try { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("gm_should_run", true).apply() } catch (_: Exception) {}
        engFailTicks = 0
        startedAtMs = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "balanced") ?: "balanced"
        curMode = mode
        liveProfile = mode

        engine = loadEngine(prefs, mode)

        loadGen = LoadGenerator(threads = prefs.getInt(KEY_THREADS, 4), lowPriority = false).also {
            it.opsScale = prefs.getInt("workload_pct", 100).coerceIn(50, 300) / 100f
            it.setProfile(mode)
            it.start()
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wm != null) {
                wifiLock = if (Build.VERSION.SDK_INT >= 29) {
                    wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "IQOOGameMode:LowLatencyWifi")
                } else {
                    wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IQOOGameMode:HighPerfWifi")
                }
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
                Log.i(TAG, "acquired ultra-low latency WifiLock")
            }
        } catch (e: Exception) { Log.w(TAG, "wifi lock unavailable: ${e.message}") }
        val netLoadEnabled = getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_NET_LOAD, true)
        netGen = if (netLoadEnabled) NetworkLoadGenerator(this).also {
            it.setHost(getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("net_host", "1.1.1.1")!!)
            it.setPerformanceMode(mode == "performance")
            it.start()
        } else null
        enforceTraceCaps()
        LiveTelemetry.init(this)
        // ADPF PerformanceHint (reflection, @SystemApi): performance profile only —
        // boosting battery/cool workers would fight their low-heat mandate
        hintMgr = hintMgr ?: HintBoost.create(this)
        createHintSession()
        Log.i(TAG, "inference path: KotlinMlpEngine CPU (nIn=${engine?.nIn} nOut=${engine?.nOut} pure Kotlin); QNN HTP NPU: ${if (hintMgr != null) "hintMgr available" else "unavailable — CPU fallback only; backprop is hand-rolled Kotlin, no accelerator"}")
        startElapsed = SystemClock.elapsedRealtime()
        lastTickAt = startElapsed
        lastFrameCount = loadGen?.frameCount() ?: 0L
        lastFrameProgressAt = startElapsed
        try {
            val tf = getFileStreamPath("gamemode_trace.csv")
            val needHeader = !tf.exists() || tf.length() == 0L
            synchronized(writerLock) {
                writer = FileWriter(tf, true)
                if (needHeader) writer?.write("t_ms,mode,action,quality,net_tier,tc_c,ts_c,tm_c,freq_ratio,fps,mbps,latency_ms,loss_pct,target_temp,reward\n")
            }
        } catch (e: Exception) { Log.w(TAG, "trace open failed: ${e.message}") }
        handler?.post { if (running) { try { tick() } catch (e: Exception) { Log.e(TAG, "initial tick: ${e.message}") } } }
        loop()
        registerScreenReceiver()
        boostSystemRefresh()
        gameLoading(false)
        updateNotification("controller ON · mode=$mode · $modelUsed")
    }

    /** rotate trace/live CSVs when they exceed the user cap (old file kept as .old) */
    private fun enforceTraceCaps() {
        val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val value = prefs.getInt(KEY_TRACE_CAP_VALUE, 5).coerceIn(1, 4096)
        val bytes = value * 1024L * if (prefs.getString(KEY_TRACE_CAP_UNIT, "MB") == "KB") 1L else 1024L
        for (name in listOf("gamemode_trace.csv", "gamemode_live.csv")) {
            try {
                val f = getFileStreamPath(name)
                if (f.exists() && f.length() > bytes) {
                    val old = File(filesDir, "$name.old")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
            } catch (_: Exception) {}
        }
    }

    /** system-wide peak refresh while the governor runs (restored on stop) */
    private var prevPeak: Float? = null
    private var prevMin: Float? = null
    private val rrPrefs by lazy { getSharedPreferences(PREF, MODE_PRIVATE) }
    private fun boostSystemRefresh() {
        try {
            if (!android.provider.Settings.System.canWrite(this)) return
            val disp = (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val maxHz = disp.supportedModes.maxOf { it.refreshRate }
            val c = contentResolver
            val alreadySaved = rrPrefs.getBoolean("rr_saved", false)
            if (!alreadySaved) {
                prevPeak = android.provider.Settings.System.getFloat(c, "peak_refresh_rate", maxHz)
                prevMin = android.provider.Settings.System.getFloat(c, "min_refresh_rate", 0f)
                rrPrefs.edit().putFloat("rr_prev_peak", prevPeak ?: maxHz)
                    .putFloat("rr_prev_min", prevMin ?: 0f).putBoolean("rr_saved", true).commit()
            }
            android.provider.Settings.System.putFloat(c, "peak_refresh_rate", maxHz)
            if ((prevMin ?: 0f) > 0f) android.provider.Settings.System.putFloat(c, "min_refresh_rate", maxHz)
            Log.i(TAG, "system refresh forced to $maxHz while running")
        } catch (e: Exception) { Log.w(TAG, "refresh boost skipped: ${e.message}") }
    }

    private fun restoreSystemRefresh() {
        try {
            if (!android.provider.Settings.System.canWrite(this)) return
            val c = contentResolver
            val saved = rrSavedPair()
            val peak = prevPeak ?: saved.first
            val min = prevMin ?: saved.second
            peak?.let { android.provider.Settings.System.putFloat(c, "peak_refresh_rate", it) }
            min?.let { if (it >= 0f) android.provider.Settings.System.putFloat(c, "min_refresh_rate", it) }
        } catch (_: Exception) {}
        prevPeak = null; prevMin = null
        rrPrefs.edit().putBoolean("rr_saved", false).apply()
    }

    /** true when a previous process died with the override still applied */
    private fun rrSavedPair(): Pair<Float?, Float?> =
        if (rrPrefs.getBoolean("rr_saved", false))
            rrPrefs.getFloat("rr_prev_peak", -1f).takeIf { it > 0f } to
                rrPrefs.getFloat("rr_prev_min", -1f).takeIf { it >= 0f }
        else null to null

    private fun recoverRefreshIfNeeded() {
        val (peak, min) = rrSavedPair() ?: return
        try {
            if (!android.provider.Settings.System.canWrite(this)) return
            val c = contentResolver
            peak?.let { android.provider.Settings.System.putFloat(c, "peak_refresh_rate", it) }
            min?.let { if (it >= 0f) android.provider.Settings.System.putFloat(c, "min_refresh_rate", it) }
            Log.i(TAG, "recovered refresh-rate override from previous process ($peak/$min)")
        } catch (_: Exception) {}
        rrPrefs.edit().putBoolean("rr_saved", false).apply()
    }

    private fun stopControl() {
        running = false
        controllerOn = false
        tickCount = 0
        userPaused = false
        screenPaused = false
        guardLatched = false
        battBrakeLatched = false
        lastAppliedQ = -1; pendingQ = -1; pendingTicks = 0
        dwellMode = ""
        appliedIntensity = 0f
        lastFps = 0.0
        lastWorkMf = 0.0
        lastMbps = 0.0
        lastLatencyMs = -1f
        lastPacketLoss = -1f
        lastReward = 0f
        lastIntensity = 0f
        lastNetTier = 0
        lastAction = 0
        try { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("gm_should_run", false).apply() } catch (_: Exception) {}
        handler?.removeCallbacksAndMessages(null)
        unregisterScreenReceiver()
        restoreSystemRefresh()
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
        // clear the per-frame reporter BEFORE closing the session — in-flight
        // worker callbacks must never hit a closed binder object
        loadGen?.frameReporter = null
        hintSession?.close()
        hintSession = null
        hintMgr = null
        lastPowerEfficient = false
        loadGen?.stop()
        loadGen = null
        netGen?.stop()
        netGen = null
        engine = null
        LiveTelemetry.close()
        synchronized(writerLock) {
            try { writer?.flush(); writer?.close() } catch (e: Exception) {}
            writer = null
        }
        Log.i(TAG, "session stopped: mode=$curMode ticks=$tickCount model=$modelUsed")
    }

    /** ADPF PerformanceHint session over the load-gen threads (retries until their tids register) */
    private fun createHintSession() {
        val defThreads = Runtime.getRuntime().availableProcessors().coerceIn(6, 8)
        val expected = getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_THREADS, defThreads)
        val tids = loadGen?.tids()
        if (tids == null || tids.size < expected) {
            handler?.postDelayed({ if (running) createHintSession() }, 200)
            return
        }
        try {
            hintSession = hintMgr?.createSession(tids, 16_000_000L)
            if (hintSession != null) {
                Log.i(TAG, "hint session created for ${tids.size} threads")
                // frame-paced contract: workers reportActualWorkDuration EVERY cycle
                loadGen?.frameReporter = { ns -> hintSession?.reportFrame(ns) }
                val pe = curMode == "battery" || curMode == "cool"
                hintSession?.setPowerEfficient(pe)
                lastPowerEfficient = pe
            }
        } catch (e: Exception) {
            Log.w(TAG, "hint session failed: ${e.message}")
        }
    }

    private fun loop() {
        val tickMs = getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_TICK_MS, 2000)
            .coerceIn(500, 30_000).toLong()
        handler?.postDelayed({
            if (!running) return@postDelayed
            try { tick() } catch (e: Exception) { Log.e(TAG, "tick: ${e.message}") }
            if (running) loop()
        }, tickMs)
    }

    /** raw panel mode refresh rate, live — no smoothing, no windows */
    private fun currentDisplayHz(): Float = try {
        displayHzProvider?.invoke() ?: run {
            val disp = (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            disp.mode.refreshRate
        }
    } catch (_: Exception) { 0f }

    private fun tick() {
        val tickStartNs = SystemClock.elapsedRealtimeNanos()
        lastTickAt = SystemClock.elapsedRealtime()
        // heartbeat for kill detection (Part 2.4)
        try { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong("gm_last_heartbeat_ms", lastTickAt).apply() } catch (_: Exception) {}
        val gen = loadGen
        val eng = engine
        if (gen == null || eng == null) {
            // engine failed to load → halt generators (they'd burn CPU/net ungoverned);
            // retry the model load at most every 15 ticks instead of every tick
            if (eng == null && running) {
                engFailTicks++
                if (engFailTicks == 1) {
                    Log.w(TAG, "engine unavailable — generators halted until model reloads")
                    loadGen?.stop()
                    netGen?.stop()
                    updateNotification("model load failed — retrying")
                }
                if (engFailTicks % 15 == 0) {
                    gameLoading(true)
                    engine = loadEngine(getSharedPreferences(PREF, Context.MODE_PRIVATE), curMode)
                    if (engine != null) {
                        engFailTicks = 0
                        restartGenerators(curMode == "battery" || curMode == "cool")
                        updateNotification("model reloaded · $modelUsed")
                    }
                    gameLoading(false)
                }
            }
            return
        }
        if (paused) {
            val chip = thermal.zoneMaxBySubstring(listOf("cpu-", "gpuss", "ddr"))?.div(1000f) ?: lastTempChip
            val freshBatt = (thermal.zoneValue("batt_therm") ?: thermal.zoneValue("battery") ?: thermal.zoneMaxBySubstring(listOf("batt")) ?: 0L) / 1000f
            val skinC = if (freshBatt > 0f) freshBatt.also { LiveTelemetry.skinC = it } else LiveTelemetry.skinC
            lastTempChip = chip; lastTempBatt = skinC; lastTempModem = LiveTelemetry.modemC
            lastIntensity = 0f; lastNetTier = 0; lastAction = -1
            tickCount++
            // re-evaluate latches while paused — otherwise deadlock (guard/battery never clears)
            val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val guardEnabled = prefs.getBoolean(KEY_THERMAL_GUARD_ON, false)
            val hotC = prefs.getInt(KEY_GUARD_HOT, 95)
            val resumeC = prefs.getInt(KEY_GUARD_RESUME, 88).coerceAtMost(hotC - 3)
            if (guardLatched && (!guardEnabled || chip < resumeC)) guardLatched = false
            val freshBi = try { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
            val plugged = freshBi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (freshBi != null) LiveTelemetry.isPlugged = plugged
            val battCeil = if (plugged) prefs.getInt("guard_batt_chg_c", 41).coerceIn(36, 48) else prefs.getInt("guard_batt_c", 43).coerceIn(38, 48)
            if (battBrakeLatched && skinC < battCeil - 3) battBrakeLatched = false
            if (!guardLatched && !battBrakeLatched && !screenPaused && !userPaused) applyPause(false)
            if (tickCount % 10 == 0) updateNotification("PAUSED · chip ${chip.toInt()}°C · batt ${skinC.toInt()}°C")
            if (paused) return
        }
        val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "balanced") ?: "balanced"
        val targetTemp = PolicyConfig.SKIN_KNEE[mode] ?: 45f
        val targetFps = prefs.getInt(KEY_TARGET_FPS, 120)
        lastTargetFps = targetFps
        curMode = mode
        liveProfile = mode

        val sampleFps = if (gen.fps() > 0.0) gen.fps().toFloat() else targetFps.toFloat()
        val sampleMbps = netGen?.mbps?.toFloat() ?: 0f
        val state = LiveTelemetry.sample(
            this,
            fps = sampleFps,
            mbps = sampleMbps,
            latencyMs = netGen?.latencyMs ?: -1f,
            lossPct = netGen?.packetLoss ?: -1f,
            action = lastAction,
            load = lastIntensity,
            netTier = lastNetTier,
            targetFps = targetFps
        )
        lastState = state

        // data-quality beacon: every 15 ticks log exactly what the RL sees
        if (tickCount % 15 == 0) {
            val bad = !PolicyConfig.plausibleState(state)
            val z = PolicyConfig.normalize(state)
            Log.i(TAG, "state[${state.joinToString { "%.2f".format(it) }}] zMax=${z.max().toString().take(6)} zMin=${z.min().toString().take(6)}${if (bad) " ← IMPLAUSIBLE" else ""}")
            if (bad) Log.w(TAG, "implausible state — check sensors: $state")
        }

        // GUARDIANS: deterministic safety rails that OVERRIDE model action
        // when battery is critically low or chip is overheating
        val minBatt = prefs.getInt(KEY_MIN_BATT, 15)
        if (!LiveTelemetry.isPlugged && LiveTelemetry.battLevel in 0f..minBatt.toFloat()) {
            Log.w(TAG, "guard: battery ${LiveTelemetry.battLevel}% <= $minBatt (unplugged) — stopping")
            stopControl()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // ACTIVE GUARDIANS: evaluate thermal guard and battery brake while RUNNING
        // (previously only evaluated in the paused block — device could cook at full load)
        val guardEnabled = prefs.getBoolean(KEY_THERMAL_GUARD_ON, false)
        val hotC = prefs.getInt(KEY_GUARD_HOT, 95).toFloat()
        val resumeC = prefs.getInt(KEY_GUARD_RESUME, 88).toFloat()
        if (guardEnabled && state[0] >= hotC && !guardLatched) {
            Log.w(TAG, "thermal guard: chip ${state[0]}°C >= ${hotC.toInt()}°C — latching pause")
            guardLatched = true; applyPause(true); return
        }
        val runPlugged = LiveTelemetry.isPlugged
        val runBattCeil = if (runPlugged) prefs.getInt("guard_batt_chg_c", 41).coerceIn(36, 48).toFloat()
                         else prefs.getInt("guard_batt_c", 43).coerceIn(38, 48).toFloat()
        if (state[1] >= runBattCeil && !battBrakeLatched) {
            Log.w(TAG, "battery brake: skin ${state[1]}°C >= ${runBattCeil.toInt()}°C — latching pause")
            battBrakeLatched = true; applyPause(true); return
        }

        // profile switch → snap instantly to the new personality (no dwell lag)
        var switchedProfile = false
        if (dwellMode != mode) {
            Log.i(TAG, "PROFILE SWITCH: $dwellMode → $mode")
            dwellMode = mode
            lastAppliedQ = -1; pendingQ = -1; pendingTicks = 0
            switchedProfile = true
            // per-profile model: respect user snapshot for same mode, else load trained_<mode>.bin
            val curActive = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_ACTIVE_MODEL, null)
            val isCustomForThisMode = curActive != null && (curActive.startsWith("trained_${mode}_") || curActive.startsWith("user_${mode}") || curActive == "trained_${mode}")
            val shouldLoadPerProfile = curActive == null || curActive in MODE_ASSET || curActive == mode || !isCustomForThisMode
            if (shouldLoadPerProfile) {
                val perProfile = Trainer.loadEngine(this, mode)
                if (perProfile != null) {
                    engine = perProfile
                    Log.i(TAG, "per-profile model loaded: $modelUsed for $mode (respecting custom: $isCustomForThisMode)")
                }
            }
            loadGen?.setProfile(mode)
            applyPowerEfficient(mode)
        }

        val s = PolicyConfig.normalize(state)
        val aRaw = PolicyConfig.chooseAction(eng, s, prefs, mode)
        var qT = aRaw / PolicyConfig.N_NET

        // 1) clamp band per profile
        when (mode) {
            "performance" -> qT = qT.coerceAtLeast(prefs.getInt(KEY_CLAMP_PERF_MIN, 2))
            "battery" -> qT = qT.coerceAtMost(prefs.getInt(KEY_CLAMP_BATT_MAX, 1))
            "cool" -> qT = qT.coerceAtMost(prefs.getInt(KEY_CLAMP_COOL_MAX, 1))
        }
        qT = qT.coerceIn(0, PolicyConfig.N_Q - 1)

        // 2) dwell hysteresis: hold a tier >= 3 ticks before allowing a change unless big jump
        if (qT != lastAppliedQ) {
            if (pendingQ != qT) { pendingQ = qT; pendingTicks = 1 } else pendingTicks++
            val bigJump = lastAppliedQ < 0 || Math.abs(qT - lastAppliedQ) >= 2
            if (bigJump || pendingTicks >= 3) {
                lastAppliedQ = qT; pendingTicks = 0
                Log.i(TAG, "tier applied q$qT load=${(PolicyConfig.LOAD[qT] * 100).toInt()}%")
            } else {
                qT = lastAppliedQ
            }
        } else {
            pendingQ = -1; pendingTicks = 0
        }
        var a = qT * PolicyConfig.N_NET + aRaw % PolicyConfig.N_NET
        lastAction = a
        val netTier = a % PolicyConfig.N_NET

        val targetInt = PolicyConfig.LOAD[qT]
        if (switchedProfile) {
            appliedIntensity = targetInt
        } else {
            val rampPct = prefs.getInt("ramp_pct", 35).coerceIn(10, 100) / 100f
            appliedIntensity += (targetInt - appliedIntensity) * rampPct
        }
        lastIntensity = appliedIntensity
        lastNetTier = netTier
        gen.intensity = appliedIntensity
        netGen?.setTier(netTier)

        lastFps = gen.fps()
        lastWorkMf = gen.mflops()
        lastMbps = netGen?.mbps ?: 0.0
        lastLatencyMs = netGen?.latencyMs ?: -1f
        lastPacketLoss = netGen?.packetLoss ?: -1f
        lastTempChip = state[0]; lastTempBatt = state[1]; lastTempModem = state[2]
        lastCpuFreqMhz = LiveTelemetry.cpuFreqMhz
        lastPrimeFreqMhz = LiveTelemetry.bigCoreFreqMhz
        lastCores = LiveTelemetry.coresOnline
        lastGpuPct = LiveTelemetry.gpuBusyPct
        lastHeadroom = LiveTelemetry.thermalHeadroomPct

        lastDisplayHz = currentDisplayHz()
        lastBattLevel = LiveTelemetry.battLevel
        lastFrameTargetMs = LiveTelemetry.frameTargetMs
        lastFrameActualMs = LiveTelemetry.frameActualMs

        try {
            val intensityNow = targetInt
            val qualityScale = if (mode == "performance") 1.30f + 0.60f * intensityNow else 0.70f + 0.45f * intensityNow
            val effFps = PolicyConfig.effectiveBoostFps(targetFps, mode, state[1], state[0], qualityScale, lastDisplayHz.takeIf { it > 0f } ?: 144f)
            hintSession?.updateTargetFps(effFps)
        } catch (e: Exception) {}

        val fc = gen.frameCount()
        if (fc != lastFrameCount) {
            lastFrameCount = fc; lastFrameProgressAt = SystemClock.elapsedRealtime()
        } else if (lastFrameProgressAt > 0 && SystemClock.elapsedRealtime() - lastFrameProgressAt > 5000) {
            hintSession?.reportFrame((SystemClock.elapsedRealtime() - lastFrameProgressAt) * 1_000_000L)
            lastFrameProgressAt = SystemClock.elapsedRealtime()
        }
        tickCount++

        val r = PolicyConfig.reward(state, a, mode, targetFps)
        lastReward = r
        // headroom-fresh comparison for the "we beat OS prediction" claim:
        // only pair when the OS value was genuinely just refreshed (≤12s)
        if (LiveTelemetry.isHeadroomFresh()) {
            android.util.Log.i(TAG, "headroom-fresh: os=${LiveTelemetry.thermalHeadroomPct.toInt()}% ours=${state[4].toInt()}% action=q${a / PolicyConfig.N_NET}")
        }
        // PURE INFERENCE doctrine: no online learning in the tick loop — the
        // reward is logged for trace/telemetry; training happens only on the
        // Train page (OfflineTrainer).

        synchronized(writerLock) {
            try {
                writer?.write(String.format(Locale.US, "%d,%s,%d,%d,%d,%.1f,%.1f,%.1f,%.3f,%.0f,%.1f,%.1f,%.1f,%.0f,%.2f\n",
                    SystemClock.elapsedRealtime() - startElapsed, mode, a, a / PolicyConfig.N_NET, netTier,
                    state[0], state[1], state[2], state[3], gen.fps(), lastMbps,
                    lastLatencyMs, lastPacketLoss, targetTemp, r))
                writer?.flush()
            } catch (e: Exception) {}
        }
        updateNotification("$mode · $modelUsed · ${lastDisplayHz.toInt()}Hz · netT$netTier · ${liveAvgMhz()}MHz · ${state[1].toInt()}°C")
    }

    private fun prefsTargetFps(): Int =
        getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_TARGET_FPS, 120)

    // ---- actuation stabilizers (dwell + ramp) ----
    private var lastAppliedQ = -1
    private var pendingQ = -1
    private var pendingTicks = 0
    private var dwellMode = ""
    @Volatile private var appliedIntensity = PolicyConfig.LOAD[1]
    private var engFailTicks = 0

    /** fused real-time state via LiveTelemetry: [chipC, skinC, modemC, freqRatio, headroomProxy, fps, netMbps, tSec] */
    private fun buildState(gen: LoadGenerator, targetFps: Int): FloatArray =
        LiveTelemetry.sample(
            this,
            gen.fps().toFloat(),
            (netGen?.mbps ?: 0.0).toFloat(),
            netGen?.latencyMs ?: -1f,
            netGen?.packetLoss ?: -1f,
            lastAction,
            lastIntensity,
            lastNetTier,
            targetFps,
        )

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
    }

    /** live CPU frequency read straight from sysfs at post time — the MAX
     *  current frequency across ALL online cores (the boost-relevant number,
     *  not just cpu7); cached tick samples go stale during pause */
    /** live ALL-CORE average frequency from sysfs at post time (not cached) */
    private fun liveAvgMhz(): Int {
        var sum = 0L; var n = 0
        for (i in 0..7) {
            val khz = com.iqoo.perfcollect.SafeRead.readLong(
                "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: 0L
            if (khz > 0) { sum += khz; n++ }
        }
        return if (n > 0) ((sum / n) / 1000L).toInt() else 0
    }

    private fun livePrimeMhz(): Int {
        var maxKhz = 0L
        for (i in 0..7) {
            val khz = com.iqoo.perfcollect.SafeRead.readLong(
                "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: 0L
            if (khz > maxKhz) maxKhz = khz
        }
        return (maxKhz / 1000L).toInt()
    }

    private val stopPi by lazy {
        android.app.PendingIntent.getForegroundService(
            this, 1,
            Intent(this, GameModeService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val pausePi by lazy {
        android.app.PendingIntent.getForegroundService(
            this, 2,
            Intent(this, GameModeService::class.java).setAction(ACTION_PAUSE),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val resumePi by lazy {
        android.app.PendingIntent.getForegroundService(
            this, 3,
            Intent(this, GameModeService::class.java).setAction(ACTION_RESUME),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(text: String): Notification {
        val primeMhz = LiveTelemetry.bigCoreFreqMhz.toInt().takeIf { it > 0 } ?: lastPrimeFreqMhz.toInt()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("iQOO Game Mode · cpu ${primeMhz}MHz")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(if (paused) R.string.action_resume else R.string.action_pause), if (paused) resumePi else pausePi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .build()
    }

    private var lastNotifAt = 0L
    private var lastNotifText: String? = null
    private var lastForcedAt = 0L
    private fun updateNotification(text: String) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        // at most every 8s for identical text, but force a refresh if >30s stale
        if (text == lastNotifText && now - lastNotifAt < 8_000 && now - lastForcedAt < 30_000) return
        lastNotifAt = now; lastNotifText = text; lastForcedAt = now
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {}
    }

    private fun scheduleRestartIfNeeded(wasRunning: Boolean, wasShouldRun: Boolean) {
        try {
            if (!wasRunning || !wasShouldRun) return
            if (running) return
            val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val pi = android.app.PendingIntent.getForegroundService(
                this, 9001,
                Intent(this, GameModeService::class.java).setAction(ACTION_START),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5000, pi)
                Log.i(TAG, "scheduled inexact auto-restart in 5s (fallback for missing exact alarm permission)")
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5000, pi)
                Log.i(TAG, "scheduled exact auto-restart in 5s (Monster Mode survival)")
            }
        } catch (e: Exception) { Log.w(TAG, "restart schedule failed: ${e.message}") }
    }

    override fun onDestroy() {
        val wasRunning = running
        val wasShouldRun = getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("gm_should_run", false)
        stopControl()
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        thread?.quitSafely()
        thread?.join(1500)
        if (wasRunning && wasShouldRun) scheduleRestartIfNeeded(wasRunning, wasShouldRun)
        super.onDestroy()
    }
}