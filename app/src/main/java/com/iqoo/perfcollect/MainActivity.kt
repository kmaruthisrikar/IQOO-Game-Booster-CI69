package com.iqoo.perfcollect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.iqoo.perfcollect.data.SessionMeta
import com.iqoo.perfcollect.data.SessionStore
import com.iqoo.perfcollect.export.ModelsDir
import com.iqoo.perfcollect.export.SessionExporter
import com.iqoo.perfcollect.export.SessionFiles
import com.iqoo.perfcollect.ml.HintBoost
import com.iqoo.perfcollect.ml.KotlinMlpEngine
import com.iqoo.perfcollect.ml.LiveTelemetry
import com.iqoo.perfcollect.ml.LoadGenerator
import com.iqoo.perfcollect.ml.OfflineTrainer
import com.iqoo.perfcollect.ml.PolicyConfig
import com.iqoo.perfcollect.ml.Trainer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * iQOO Game Mode — 4-page dark UI sized to the display.
 *   Game Mode: profile pills + FAVORITES + models (up to 5 snapshots) + LIVE + sliders
 *   Train:     CSV picker, profile, epochs, offline training → snapshots to ModelsDir
 *   Telemetry: collector, sessions, export
 *   Weights:   import custom .bin weights + hot-swap into the running controller
 * Models live in /sdcard/iqoo-data/models (ModelsDir, max 5 snapshots). Favorites
 * are a star-toggled subset (max 5) shown in the FAV section; the active model is
 * stored in prefs and loaded by GameModeService.
 */
class MainActivity : Activity() {

    private companion object {
        val BG = Color.parseColor("#000000")
        val PANEL = Color.parseColor("#0A0C10")
        val PANEL2 = Color.parseColor("#12161C")
        val ACCENT = Color.parseColor("#00E5FF")
        val TEXT = Color.parseColor("#E6EDF3")
        val DIM = Color.parseColor("#8B98A5")
        val GOOD = Color.parseColor("#2BD576")
        val WARN = Color.parseColor("#FFB020")
        val HOT = Color.parseColor("#FF4D4F")
        const val PICK_CSV = 1001
        const val PICK_BIN = 1002
        val FROZEN = listOf("performance", "balanced", "battery", "cool")
        const val BENCH_CYCLE_COOLDOWN_MS = 60_000L
        const val BENCH_SAMPLE_MS = 2_000L
    }

    private lateinit var store: SessionStore
    private val uiHandler = Handler(Looper.getMainLooper())
    private var elapsedSec = 0L
    private var currentPage = 0

    // Telemetry page
    private lateinit var statusView: TextView
    private lateinit var sessionsView: LinearLayout

    // Train page
    private lateinit var csvInfo: TextView
    private lateinit var trainProgress: TextView
    private lateinit var trainBar: ProgressBar
    private lateinit var trainResult: TextView
    private var trainSessionsView: LinearLayout? = null
    private var selectedCsvText: String? = null
    private var selectedCsvFile: File? = null
    private var trainMode = "balanced"
    private var trainFrom: String? = null
    private var training = false
    private var activeTrainer: OfflineTrainer? = null
    private var cancelTrainBtn: Button? = null

    // Game page
    private var v: Views? = null
    private lateinit var modelsView: LinearLayout

    // Weights page
    private lateinit var weightsStatus: TextView

    // Tools page
    private lateinit var autoRunStatus: TextView
    private lateinit var gameMapStatus: TextView
    private lateinit var retrainSourceLabel: TextView
    private lateinit var retrainProgress: TextView
    private lateinit var retrainBar: ProgressBar
    private lateinit var chartInfo: TextView
    private lateinit var slotsLabel: TextView
    private var chart: ChartView? = null
    private var retraining = false

    // Bench page
    private lateinit var benchStatus: TextView
    private lateinit var benchResult: TextView
    @Volatile private var benchRunning = false
    private var profilePillRestyle: (() -> Unit)? = null

    private var uiTicks = 0L
    private var lastSamples = -1L
    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.iqoo.perfcollect.ml.PolicyConfig.init(this)
        store = SessionStore(this)
        Thread { com.iqoo.perfcollect.Storage.migrateLegacyOnce(this) }.start()

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }
        val root = buildUi()
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            root.post {
                val ins = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars()) ?: return@post
                root.setPadding(0, ins.top, 0, ins.bottom)
            }
        }
        uiHandler.post(uiLoop)
        maybeWizard()
        val ab = intent?.getIntExtra("autobench", 0) ?: 0
        intent?.removeExtra("autobench")
        if (ab == 1) uiHandler.postDelayed({
            if (!benchRunning && !GameModeService.controllerOn) startExtremeBench()
        }, 2500)
        if (ab == 2) uiHandler.postDelayed({
            if (!benchRunning && !GameModeService.controllerOn) startBench()
        }, 2500)
    }

    private fun wizardStatus(): BooleanArray = booleanArrayOf(
        if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true,
        GameA11yService.enabled(this),
        android.provider.Settings.System.canWrite(this),
        if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager() else true,
    )

    private fun showWizardSummary() {
        val st = wizardStatus()
        val names = listOf("Notifications", "Usage access (auto-game)", "Modify system settings (144Hz boost)", "All-files storage (iqoo-data)")
        val msg = buildString {
            append("Setup complete. Current permissions:\n\n")
            names.forEachIndexed { i, n -> append(if (st[i]) "✅ " else "❌ ").append(n).append('\n') }
            if (st.any { !it }) append("\nAnything ❌ can be fixed anytime: Tools → 'Re-run first-start setup'.")
            else append("\nEverything granted — the app is fully functional.")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Permissions summary")
            .setMessage(msg)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun maybeWizard(force: Boolean = false) {
        val prefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
        if (prefs.getBoolean("wizard_done", false) && !force) return
        var step = 0
        val steps = listOf(
            arrayOf(
                "Setup 1/7 — Notifications",
                "The controller shows a live notification with PAUSE / STOP. Allow notifications?"
            ),
            arrayOf(
                "Setup 2/7 — Auto-run permission",
                "Enable the Game auto-run detector (accessibility). It tells the app the instant a game opens — no polling, no usage access."
            ),
            arrayOf(
                "Setup 3/7 — Modify system settings",
                "Lets Game Mode force the display to peak refresh (144Hz) while playing and restore it after. Optional but recommended."
            ),
            arrayOf(
                "Setup 4/7 — All-files storage",
                "Needed to export session CSVs and bench results to /sdcard/iqoo-data. Skip if you only train on-device."
            ),
            arrayOf(
                "Setup 5/7 — Battery optimization",
                "Excluding the app from battery optimization stops Android from killing the governor mid-game."
            ),
            arrayOf(
                "Setup 6/7 — vivo Autostart (Monster Mode survival)",
                "OriginOS/iQOO kills background apps aggressively, even foreground services, unless Autostart is allowed.\n\nGo to Settings > Apps > Autostart management → enable this app.\nIf you can't find it, open Settings and search 'Autostart'.\nAlso check Game Booster > Optimized apps and EXCLUDE this app if listed — then test whether Monster Mode still kills it."
            ),
            arrayOf(
                "Setup 7/7 — Network target",
                "IP the network actuator probes (latency + keep-alive). Use your game server for meaningful numbers."
            )
        )
        fun run() {
            if (step >= steps.size) {
                prefs.edit().putBoolean("wizard_done", true).apply()
                showWizardSummary()
                return
            }
            val (title, msg) = steps[step]
            val d = android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Open") { _, _ ->
                    when (step) {
                        0 -> requestNotifPermission()
                        1 -> try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                        2 -> try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                        3 -> ensureStorageAccess()
                        4 -> try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                        5 -> try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                        6 -> askNetHost { }
                    }
                    step++; uiHandler.postDelayed({ run() }, 600)
                }
                .setNegativeButton("Skip") { _, _ -> step++; run() }
                .show()
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ACCENT)
        }
        run()
    }

    private fun askNetHost(after: () -> Unit) {
        val input = EditText(this).apply {
            hint = "0.0.0.0"
            setSingleLine(true)
            setText(getSharedPreferences(GameModeService.PREF, MODE_PRIVATE).getString("net_host", "1.1.1.1") ?: "1.1.1.1")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Network target")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val h = input.text.toString().trim()
                if (h.isNotEmpty()) getSharedPreferences(GameModeService.PREF, MODE_PRIVATE).edit().putString("net_host", h).apply()
                after()
            }
            .setNegativeButton("Use default") { _, _ -> after() }
            .show()
    }

    // ---------- helpers ----------

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
            if (mono) typeface = Typeface.MONOSPACE
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(PANEL); cornerRadius = dpf(20f) }
        setPadding(dp(22f), dp(20f), dp(22f), dp(20f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14f) }
    }

    private fun pillButton(text: String, accent: Boolean = false): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(if (accent) BG else TEXT)
        b.textSize = 13f
        b.setAllCaps(false)
        b.minHeight = dp(48f)
        b.background = GradientDrawable().apply {
            setColor(if (accent) ACCENT else PANEL2)
            cornerRadius = dpf(22f)
        }
        return b
    }

    /** horizontal button row: equal-width, never overflows */
    private fun buttonRow(buttons: List<Button>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < buttons.size - 1) lp.rightMargin = dp(10f)
            b.layoutParams = lp
            row.addView(b)
        }
        return row
    }

    private fun sectionTitle(text: String): TextView = tv(text.uppercase(Locale.US), 11f, DIM, bold = true).apply {
        setPadding(0, dp(6f), 0, dp(8f))
    }

    /** a card pre-titled with an uppercase section header */
    private fun groupCard(title: String): LinearLayout = card().apply { addView(sectionTitle(title)) }

    /** one-line explainer under a control ("what is this for") */
    private fun cap(s: String): TextView = tv(s, 10f, DIM)

    private fun tempColor(c: Float): Int = when {
        c >= 85f -> HOT
        c >= 70f -> WARN
        else -> GOOD
    }

    private fun bigActionButton(text: String): Button = Button(this).apply {
        this.text = text
        setTextColor(BG)
        setTextSize(15f)
        setAllCaps(false)
        setTypeface(Typeface.DEFAULT_BOLD)
        minHeight = dp(56f)
        background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dpf(16f) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }
    }

    /** equal-width toggle pills; no RadioGroup so rows never overlap */
    private fun segmented(options: List<Pair<String, String>>, selected: String, onChange: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = options.map { (k, label) ->
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                minHeight = dp(44f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4f); marginStart = dp(4f)
                }
            }
        }
        fun style(b: Button, on: Boolean) {
            b.background = GradientDrawable().apply { setColor(if (on) ACCENT else PANEL2); cornerRadius = dpf(20f) }
            b.setTextColor(if (on) BG else TEXT)
        }
        btns.forEachIndexed { i, b ->
            style(b, options[i].first == selected)
            b.setOnClickListener {
                btns.forEachIndexed { j, o -> style(o, j == i) }
                onChange(options[i].first)
            }
            row.addView(b)
        }
        return row
    }

    /** wrapping pills: flow onto multiple rows when the option list is long */
    private fun flowPills(options: List<Pair<String, String>>, selected: String, onChange: (String) -> Unit): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val availPx = resources.displayMetrics.widthPixels - dp(84f)
        val paint = android.text.TextPaint().apply {
            typeface = Typeface.DEFAULT
            textSize = 12f * resources.displayMetrics.density
        }
        val buttons = mutableListOf<Button>()
        var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var usedPx = 0f
        fun style(b: Button, on: Boolean) {
            b.background = GradientDrawable().apply { setColor(if (on) ACCENT else PANEL2); cornerRadius = dpf(20f) }
            b.setTextColor(if (on) BG else TEXT)
        }
        options.forEach { (k, label) ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                minHeight = dp(40f)
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                includeFontPadding = false
            }
            buttons.add(b)
            val wPx = paint.measureText(label) + dp(28f)
            if (usedPx + wPx > availPx && usedPx > 0f) {
                container.addView(row)
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                usedPx = 0f
            }
            row.addView(b)
            usedPx += wPx + dp(8f)
        }
        container.addView(row)
        buttons.forEachIndexed { i, b ->
            style(b, options[i].first == selected)
            b.setOnClickListener {
                buttons.forEachIndexed { j, o -> style(o, j == i) }
                onChange(options[i].first)
            }
        }
        return container
    }

    // ---------- UI assembly ----------

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22f), dp(14f), dp(22f), dp(8f)) }
        header.addView(tv("iQOO GAME MODE", 20f, TEXT, bold = true))
        header.addView(tv("RL thermal governor · favorites · on-device training", 11f, DIM))
        root.addView(header)

        val pager = SwipePager(this)
        pager.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(pager)

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6f), dp(4f), dp(6f), dp(6f))
            background = GradientDrawable().apply { setColor(PANEL); cornerRadius = dpf(18f) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8f); marginEnd = dp(8f); bottomMargin = dp(8f) }
        }
        val tabGame = pillButton("GAME")
        val tabTrain = pillButton("TRAIN")
        val tabTele = pillButton("TELE")
        val tabWeights = pillButton("WGT")
        val tabTools = pillButton("TOOLS")
        val tabBench = pillButton("BENCH")
        listOf(tabGame, tabTrain, tabTele, tabWeights, tabTools, tabBench).forEach { b ->
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(1f); marginStart = dp(1f)
            }
            b.minHeight = dp(48f)
            b.textSize = 11f
            navBar.addView(b)
        }
        root.addView(navBar)

        fun page() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(6f), dp(20f), dp(28f))
        }
        val scGame = ScrollView(this).apply { isFillViewport = true; addView(page()) }
        val scTrain = ScrollView(this).apply { isFillViewport = true; addView(page()) }
        val scTele = ScrollView(this).apply { isFillViewport = true; addView(page()) }
        val scWeights = ScrollView(this).apply { isFillViewport = true; addView(page()) }
        val scTools = ScrollView(this).apply { isFillViewport = true; addView(page()) }
        val scBench = ScrollView(this).apply { isFillViewport = true; addView(page()) }

        val tabs = listOf(tabGame, tabTrain, tabTele, tabWeights, tabTools, tabBench)
        var syncing = false
        fun select(idx: Int) {
            currentPage = idx
            if (pager.current != idx && !syncing) pager.setCurrent(idx, smooth = true)
            tabs.forEachIndexed { i, b ->
                b.background = GradientDrawable().apply { setColor(if (i == idx) ACCENT else PANEL2); cornerRadius = dpf(20f) }
                b.setTextColor(if (i == idx) BG else TEXT)
            }
            if (idx == 1) refreshTrainSessions()
            if (idx == 2) refreshTelemetry()
                        if (idx == 4) { refreshTools(); loadChart() }
        }
        tabs.forEachIndexed { i, b -> b.setOnClickListener { select(i) } }
        pager.onPageChange = { idx -> syncing = true; select(idx); syncing = false }

        buildGamePage(scGame.getChildAt(0) as LinearLayout)
        buildTrainPage(scTrain.getChildAt(0) as LinearLayout)
        buildTelePage(scTele.getChildAt(0) as LinearLayout)
        buildWeightsPage(scWeights.getChildAt(0) as LinearLayout)
        buildToolsPage(scTools.getChildAt(0) as LinearLayout)
        buildBenchPage(scBench.getChildAt(0) as LinearLayout)

        pager.addPages(listOf(scGame, scTrain, scTele, scWeights, scTools, scBench))
        select(0)
        return root
    }

    // ---------- Page 1: Game Mode ----------

    private fun buildGamePage(page: LinearLayout) {
        val gmPrefs = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)

        // 1 · PROFILE
        page.addView(groupCard("1 · Profile").apply {
            val profOpts = listOf("performance" to "Perf", "balanced" to "Balanced", "battery" to "Battery", "cool" to "Cool")
            val profSeg = segmented(
                profOpts,
                gmPrefs.getString(GameModeService.KEY_MODE, "balanced") ?: "balanced"
            ) { k ->
                // extreme bench rewrites KEY_MODE per profile + restores it — ignore taps meanwhile
                if (benchRunning) {
                    Toast.makeText(this@MainActivity, "bench in progress", Toast.LENGTH_SHORT).show()
                    return@segmented
                }
                gmPrefs.edit()
                    .putString(GameModeService.KEY_MODE, k)
                    .apply()
                rebuildModels()
                // live switch through the service (identical path to adb tests):
                // restarts actuation with the new personality in ≤1 tick
                startService(Intent(this@MainActivity, GameModeService::class.java)
                    .setAction(GameModeService.ACTION_START)
                    .putExtra(GameModeService.EXTRA_MODE, k))
                Toast.makeText(this@MainActivity,
                    if (GameModeService.controllerOn) "$k applied live" else getString(R.string.toast_profile_set, k),
                    Toast.LENGTH_SHORT).show()
            }
            addView(profSeg)
            profilePillRestyle = {
                val cur = gmPrefs.getString(GameModeService.KEY_MODE, "balanced") ?: "balanced"
                for (i in 0 until profSeg.childCount) (profSeg.getChildAt(i) as? Button)?.let { b ->
                    val on = profOpts.getOrNull(i)?.first == cur
                    b.background = GradientDrawable().apply { setColor(if (on) ACCENT else PANEL2); cornerRadius = dpf(20f) }
                    b.setTextColor(if (on) BG else TEXT)
                }
            }
            addView(tv("Sets reward weights + selects the profile's base model.", 10f, DIM))
            addView(cap("Judges the model: Perf=fps-first · Cool=heat-first. Your chosen model stays."))
        })

        // 2 · CONTROL
        page.addView(groupCard("2 · Control").apply {

            addView(bigActionButton("START GAME MODE").apply {
                setOnClickListener {
                    // bench injects its own load + actuation — never run the controller mid-bench
                    if (benchRunning) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_bench_in_progress), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    startForegroundService(Intent(this@MainActivity, GameModeService::class.java)
                        .setAction(GameModeService.ACTION_START))
                    Toast.makeText(this@MainActivity, "controller starting — inference only", Toast.LENGTH_LONG).show()
                }
            })
            addView(pillButton("STOP GAME MODE").apply {
                setOnClickListener {
                    startService(Intent(this@MainActivity, GameModeService::class.java)
                        .setAction(GameModeService.ACTION_STOP))
                }
            })
            addView(tv("Synthetic game workers (1–8) — heat/fps source:", 12f, TEXT))
            val thrBar = SeekBar(this@MainActivity).apply { max = 7; progress = gmPrefs.getInt(GameModeService.KEY_THREADS, 4) - 1 }
            val thrVal = tv("${thrBar.progress + 1} workers", 12f, ACCENT, bold = true)
            thrVal.text = "${thrBar.progress + 1} workers"
            thrBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { thrVal.text = "${p + 1} workers" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    gmPrefs.edit().putInt(GameModeService.KEY_THREADS, thrBar.progress + 1).apply()
                    Toast.makeText(this@MainActivity,
                        if (GameModeService.controllerOn) "applies via APPLY SETTINGS NOW (Tools)" else "will use ${thrBar.progress + 1} workers on START",
                        Toast.LENGTH_SHORT).show()
                }
            })
            addView(thrBar); addView(thrVal)
            val qt = tv("quick test: idle", 10f, DIM, mono = true)
            addView(qt)
            addView(pillButton("QUICK SELF-TEST · ACTIVE MODEL").apply {
                setOnClickListener { quickSelfTest(qt) }
            })
        })

        // 3 · LIVE
        val dash = groupCard("3 · Live")
        val fpsLabel = tv("--", 42f, ACCENT, bold = true, mono = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        dash.addView(fpsLabel)
        dash.addView(tv("DISPLAY REFRESH RATE (HZ)", 10f, DIM).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val chipV = tv("--", 16f, TEXT, bold = true); val battV = tv("--", 16f, TEXT, bold = true); val modemV = tv("--", 16f, TEXT, bold = true)
        dash.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.apply {
            addView(col("CHIP", chipV)); addView(col("BATTERY", battV)); addView(col("MODEM", modemV))
        })
        val loadV = tv("--", 12f, TEXT); val netV = tv("--", 12f, TEXT); val actV = tv("--", 12f, TEXT)
        dash.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, 0) }.apply {
            addView(col("LOAD", loadV)); addView(col("NET", netV)); addView(col("ACTION", actV))
        })
        val latV = tv("--", 11f, TEXT); val lossV = tv("--", 11f, TEXT)
        dash.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.apply {
            addView(col("LATENCY", latV)); addView(col("PACKET LOSS", lossV))
        })
        dash.addView(tv("─", 11f, DIM).apply { setPadding(0, dp(8f), 0, dp(4f)) })
        val statsV = tv("idle", 11f, DIM, mono = true)
        dash.addView(statsV)
        val modelV = tv("model: —", 11f, ACCENT)
        dash.addView(modelV)
        page.addView(dash)

        v = Views().apply {
            fps = fpsLabel; chip = chipV; batt = battV; modem = modemV
            load = loadV; net = netV; action = actV; latency = latV; loss = lossV
            stats = statsV; model = modelV
        }

        // 4 · MODELS & FAVORITES
        page.addView(groupCard("4 · Models & favorites").apply {
            modelsView = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(modelsView)
        })
        rebuildModels()

        // 5 · LIMITS
        page.addView(groupCard("5 · Fps target").apply {
            addView(cap("Boost target eases automatically with heat, per profile: Perf holds hardest, Cool backs off soonest."))
            val fpsBar = SeekBar(this@MainActivity).apply { max = 114; progress = (gmPrefs.getInt(GameModeService.KEY_TARGET_FPS, 120) - 30).coerceIn(0, 114) }
            val fpsVal = tv("${30 + fpsBar.progress} fps", 12f, ACCENT, bold = true)
            fpsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    fpsVal.text = "${30 + p} fps"
                    gmPrefs.edit().putInt(GameModeService.KEY_TARGET_FPS, 30 + p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            addView(fpsBar); addView(fpsVal)
        })

        // 6 · NETWORK ACTUATOR
        page.addView(groupCard("6 · Network actuator").apply {
            val netHost = EditText(this@MainActivity).apply {
                setText(gmPrefs.getString("net_host", "1.1.1.1") ?: "1.1.1.1")
                setSingleLine(true)
                textSize = 13f
                setTextColor(TEXT)
                setHintTextColor(DIM)
                hint = "1.1.1.1 (active server IP)"
                background = GradientDrawable().apply { setColor(PANEL2); cornerRadius = dpf(14f); setStroke(1, PANEL) }
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            }
            addView(netHost)
            addView(tv("Probes go to this host — set the server you're actually using.", 10f, DIM))
            val netSave = pillButton("Save host")
            netSave.setOnClickListener {
                val h = netHost.text.toString().trim()
                if (h.isNotEmpty()) gmPrefs.edit().putString("net_host", h).apply()
                Toast.makeText(this@MainActivity, "network target: $h", Toast.LENGTH_SHORT).show()
            }
            addView(buttonRow(listOf(netSave)))
            addView(CheckBox(this@MainActivity).apply {
                text = "Network load enabled (model controls throughput)"
                isChecked = gmPrefs.getBoolean(GameModeService.KEY_NET_LOAD, true)
                setTextColor(TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, checked ->
                    gmPrefs.edit().putBoolean(GameModeService.KEY_NET_LOAD, checked).apply()
                }
            })

        })
    }

    // ---------- Models & Favorites ----------

    private fun activeModel(): String {
        val prefs = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
        val mode = prefs.getString(GameModeService.KEY_MODE, "balanced") ?: "balanced"
        return prefs.getString(GameModeService.KEY_ACTIVE_MODEL, mode) ?: mode
    }

    private fun resolve(name: String): Pair<String, String> =
        if (name in FROZEN) name to name
        else {
            val m = name.removePrefix("trained_").substringBefore('_').let { if (it in FROZEN) it else "balanced" }
            m to name
        }

    @Volatile private var modelsBuildSeq = 0

    /** dir listing + meta file reads are I/O — do them off the main thread,
     *  then inflate rows on the UI thread; stale builds are dropped via seq */
    private fun rebuildModels() {
        if (!::modelsView.isInitialized) return
        modelsView.removeAllViews()
        val seq = ++modelsBuildSeq
        val act = this
        Thread({
            val favs = ModelsDir.fav(act)
            val pool = ModelsDir.listModels(act)
            val extras = HashMap<String, String>()
            for (name in pool) {
                if (name in FROZEN) continue
                try {
                    ModelsDir.getMeta(act, name)?.let { mj ->
                        val j = org.json.JSONObject(mj)
                        extras[name] = " · ${j.optInt("rows")}r · loss ${String.format(Locale.US, "%.4f", j.optDouble("loss"))} · ${j.optString("date")}"
                    }
                } catch (_: Exception) {}
            }
            uiHandler.post {
                if (seq != modelsBuildSeq || !::modelsView.isInitialized) return@post
                modelsView.removeAllViews()
                renderModels(favs, pool, extras)
            }
        }, "models-list").apply { isDaemon = true }.start()
    }

    private fun renderModels(favs: List<String>, pool: List<String>, extras: Map<String, String>) {
        val active = activeModel()
        if (favs.isNotEmpty()) {
            modelsView.addView(sectionTitle("Favorites"))
            favs.forEach { name -> modelsView.addView(modelRow(name, active, starred = true, extra = extras[name] ?: "")) }
        }
        modelsView.addView(sectionTitle("All models"))
        FROZEN.filter { it !in favs }.forEach { name -> modelsView.addView(modelRow(name, active, starred = false, extra = "")) }
        pool.filter { it !in favs }
            .forEach { name -> modelsView.addView(modelRow(name, active, starred = false, extra = extras[name] ?: "")) }
        modelsView.addView(pillButton("Save active model as snapshot").apply {
            setOnClickListener { saveSnapshot() }
        })
        modelsView.addView(pillButton("Delete ALL models + cache").apply {
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete all models?")
                    .setMessage("Removes every snapshot + trained cache (trained_*.bin) and clears favorites. Frozen base models stay.")
                    .setPositiveButton("Delete") { _, _ ->
                        ModelsDir.deleteAllCaches(this@MainActivity)
                        getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                            .edit().remove(GameModeService.KEY_ACTIVE_MODEL).commit()
                        rebuildModels()
                        Toast.makeText(this@MainActivity, "all models + cache deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    private fun modelRow(name: String, active: String, starred: Boolean, extra: String): LinearLayout {
        val (m, nm) = resolve(name)
        val isActive = nm == active
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                setColor(if (isActive) PANEL2 else PANEL)
                cornerRadius = dpf(16f)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        val star = Button(this).apply {
            text = if (starred) "★" else "☆"
            setTextColor(if (starred) WARN else DIM)
            textSize = 16f
            minHeight = dp(36f); minWidth = dp(48f)
            background = GradientDrawable().apply { setColor(PANEL2); cornerRadius = dpf(18f) }
            setOnClickListener {
                val was = ModelsDir.isFav(this@MainActivity, name)
                val ok = ModelsDir.toggleFav(this@MainActivity, name)
                Toast.makeText(this@MainActivity,
                    if (ok) "★ favorited $name"
                    else if (was) "removed $name from favorites"
                    else "favorites full (max 5)", Toast.LENGTH_SHORT).show()
                rebuildModels()
            }
        }
        row.addView(star)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(tv(if (name in FROZEN) name.replaceFirstChar { it.uppercase() } + " · frozen" else name, 12f,
            if (isActive) ACCENT else TEXT, bold = isActive, mono = name !in FROZEN))
        info.addView(tv("profile $m" + extra + if (isActive) "  ·  ACTIVE" else "", 10f, if (isActive) GOOD else DIM))
        row.addView(info)
        if (name !in FROZEN) {
            val del = Button(this).apply {
                text = "✕"
                setTextColor(HOT)
                textSize = 15f
                minHeight = dp(36f); minWidth = dp(48f)
                background = GradientDrawable().apply { setColor(PANEL2); cornerRadius = dpf(18f) }
                setOnClickListener {
                    ModelsDir.deleteModel(this@MainActivity, name)
                    val prefs = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                    if (prefs.getString(GameModeService.KEY_ACTIVE_MODEL, "") == name) {
                        prefs.edit().remove(GameModeService.KEY_ACTIVE_MODEL).commit()
                    }
                    rebuildModels()
                    Toast.makeText(this@MainActivity, "deleted $name", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(del)
        }
        row.setOnClickListener {
            val prefs = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
            prefs.edit().putString(GameModeService.KEY_ACTIVE_MODEL, nm).commit()
            rebuildModels()
            if (GameModeService.controllerOn) {
                startService(Intent(this@MainActivity, GameModeService::class.java)
                    .setAction(GameModeService.ACTION_APPLY_MODEL)
                    .putExtra(GameModeService.EXTRA_MODEL, nm))
                Toast.makeText(this, "hot-swapped to $nm live", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "model: $nm (loads on next START)", Toast.LENGTH_SHORT).show()
            }
        }
        return row
    }

    private fun saveSnapshot() {
        try {
            val active = activeModel()
            val (m, _) = resolve(active)
            val bytes = if (active in FROZEN) assets.open("qnet_$active.bin").readBytes()
            else ModelsDir.loadModel(this, active)?.readBytes()
            if (bytes == null) { Toast.makeText(this, "no model to save", Toast.LENGTH_SHORT).show(); return }
            val name = "trained_${m}_${SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())}"
            ModelsDir.saveModel(this, name, bytes)
            rebuildModels()
            Toast.makeText(this, "saved snapshot: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Page 2: Train ----------

    private fun buildTrainPage(page: LinearLayout) {
        // 1 · DATA SOURCE
        page.addView(groupCard("1 · Data source").apply {

            csvInfo = tv("no file selected", 12f, DIM, mono = true)
            addView(csvInfo)
            val pickBtn = pillButton("Pick file…")
            pickBtn.setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/*" }
                startActivityForResult(i, PICK_CSV)
            }
            val traceBtn = pillButton("Use app trace")
            traceBtn.setOnClickListener {
                val f = getFileStreamPath("gamemode_trace.csv")
                if (f.exists()) selectCsv(f) else Toast.makeText(this@MainActivity, "no trace yet — run Game Mode first", Toast.LENGTH_LONG).show()
            }
            addView(buttonRow(listOf(pickBtn, traceBtn)))
            val recent = pillButton("Latest device export")
            recent.setOnClickListener { listLatestCsv() }
            addView(buttonRow(listOf(recent)))
            val reset = pillButton("Reset trace CSV (new data)")
            reset.setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset trace CSV?")
                    .setMessage("Deletes gamemode_trace.csv so the next game-mode run starts a FRESH training set (no accumulation).")
                    .setPositiveButton("Reset") { _, _ ->
                        try { File(filesDir, "gamemode_trace.csv").delete() } catch (_: Exception) {}
                        Toast.makeText(this@MainActivity, "trace CSV reset — next run starts fresh", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            addView(reset)
            addView(sectionTitle("Exported session CSVs"))
            trainSessionsView = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(trainSessionsView)
        })

        // 2 · TARGET MODEL
        page.addView(groupCard("2 · Target model").apply {
            addView(cap("TRAINING OUTPUT ONLY — does not change the running profile. Switch profiles on the Game page."))

            val activeMode = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                .getString(GameModeService.KEY_MODE, "balanced") ?: "balanced"
            trainMode = activeMode
            val trainModeLabel = tv("will train & save the $trainMode model", 12f, TEXT)
            addView(segmented(
                listOf("performance" to "Performance", "balanced" to "Balanced", "battery" to "Battery", "cool" to "Cool"),
                trainMode,
            ) { m -> trainMode = m; trainModeLabel.text = "will train & save the $m model" })
            addView(trainModeLabel)
            addView(sectionTitle("Start from (fine-tune)"))
            val poolModels = ModelsDir.listModels(this@MainActivity)
            val fromLabel = tv("", 12f, TEXT)
            fun fromDesc() = if (trainFrom == null) "start from base $trainMode model" else "start from pool snapshot: ${trainFrom}"
            fromLabel.text = fromDesc()
            val fromOptions = listOf("" to "Base $activeMode") + poolModels.map { it to it }
            addView(flowPills(fromOptions, trainFrom ?: "") { m ->
                trainFrom = if (m.isEmpty()) null else m
                fromLabel.text = fromDesc()
            })
            addView(fromLabel)
            if (poolModels.isEmpty()) addView(tv("no pool snapshots yet — train once to create them", 10f, DIM))
        })

        // 3 · RUN
        page.addView(groupCard("3 · Run").apply {

            val epochBar = SeekBar(this@MainActivity).apply { max = 99; progress = 19 }
            val epochVal = tv("epochs: ${epochBar.progress + 1}", 12f, ACCENT, bold = true)
            epochBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { epochVal.text = "epochs: ${p + 1}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            addView(epochBar); addView(epochVal)
            val trainBtn = bigActionButton("TRAIN MODEL").apply {
                setOnClickListener {
                    if (training) { Toast.makeText(this@MainActivity, "training in progress…", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    val text = selectedCsvText
                    if (text == null) { Toast.makeText(this@MainActivity, "select a CSV first", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                    startTraining(text, epochBar.progress + 1)
                }
            }
            cancelTrainBtn = pillButton("CANCEL TRAINING", accent = true).apply {
                visibility = View.GONE
                setOnClickListener {
                    activeTrainer?.stop()
                    Toast.makeText(this@MainActivity, "cancelling after this epoch…", Toast.LENGTH_SHORT).show()
                }
            }
            addView(buttonRow(listOf(trainBtn, cancelTrainBtn!!)))
            addView(pillButton("RETRAIN ALL PROFILES (uses this CSV)").apply {
                setOnClickListener {
                    if (selectedCsvText == null) { Toast.makeText(this@MainActivity, "pick a CSV above first", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                    startRetrainAll()
                    Toast.makeText(this@MainActivity, "retrain-all started — watch Tools page", Toast.LENGTH_SHORT).show()
                }
            })
            trainBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            addView(trainBar)
            trainProgress = tv("", 11f, DIM, mono = true)
            addView(trainProgress)
            trainResult = tv("", 12f, TEXT)
            addView(trainResult)
        })
    }

    private fun selectCsv(f: File) {
        csvInfo.text = "loading ${f.name}…"; csvInfo.setTextColor(DIM)
        Thread({
            try {
                val text = f.readText()
                val n = text.split('\n').count { it.trim().isNotEmpty() && !it.startsWith("t_ms") }
                uiHandler.post {
                    selectedCsvText = text
                    selectedCsvFile = f
                    csvInfo.text = "${f.name}  ·  $n rows"; csvInfo.setTextColor(TEXT)
                }
            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(this@MainActivity, "read failed: ${e.message}", Toast.LENGTH_LONG).show()
                    csvInfo.text = "read failed: ${e.message}"; csvInfo.setTextColor(HOT)
                }
            }
        }, "load-csv").apply { isDaemon = true }.start()
    }

    private fun listLatestCsv() {
        ensureStorageAccess()
        try {
            val dir = File(SessionExporter.targetExportDir(this), "csv")
            val files = (dir.listFiles()?.filter { it.name.endsWith(".csv") && !it.name.endsWith("_events.csv") } ?: emptyList()).sortedByDescending { it.lastModified() }
            if (files.isEmpty()) { Toast.makeText(this, "no CSVs exported yet — export sessions on Telemetry page", Toast.LENGTH_LONG).show(); return }
            selectCsv(files[0])
        } catch (e: Exception) {
            Toast.makeText(this, "list failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshTrainSessions() {
        val v = trainSessionsView ?: return
        v.removeAllViews()
        val dir = File(SessionExporter.targetExportDir(this), "csv")
        val files = (dir.listFiles()?.filter { it.name.endsWith(".csv") && !it.name.endsWith("_events.csv") } ?: emptyList())
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            v.addView(tv("no exported sessions yet — record on Telemetry, then EXPORT ALL SESSIONS", 11f, DIM))
            return
        }
        files.take(8).forEach { f ->
            val b = pillButton("▸ " + f.nameWithoutExtension)
            b.setOnClickListener { selectCsv(f); Toast.makeText(this, "selected ${f.name}", Toast.LENGTH_SHORT).show() }
            v.addView(buttonRow(listOf(b)))
        }
    }

    private fun startTraining(text: String, epochs: Int) {
        val mode = trainMode
        val from = trainFrom
        training = true
        trainResult.text = "training $mode (${epochs} epochs)…"; trainResult.setTextColor(TEXT)
        trainProgress.text = "preparing…"; trainBar.progress = 0
        // engine load + full CSV parse are heavy I/O/CPU — run them inside the
        // trainer thread, not on the UI thread; ANY failure resets the latch
        Thread({
            try {
                val engine = if (from == null) Trainer.loadEngine(this, mode)
                else ModelsDir.loadModel(this, from)?.readBytes()?.let { KotlinMlpEngine.fromBytes(it) }
                    ?: throw IllegalStateException("pool model not found")
                uiHandler.post {
                    trainResult.text = "training $mode (${epochs} epochs)" + (if (from != null) " from $from" else "") + "…"
                }
                val srcKind = when {
                    Trainer.isRlTrace(text) -> "app trace"
                    Trainer.isLiveCsv(text) -> "live sensors"
                    Trainer.isCollectorCsv(text) -> "telemetry session"
                    else -> "unknown"
                }
                val targetFps = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                    .getInt(GameModeService.KEY_TARGET_FPS, 120)
                val rows = when {
                    Trainer.isRlTrace(text) -> Trainer.parseCsv(text, mode, targetFps)
                    Trainer.isLiveCsv(text) -> Trainer.parseLiveCsv(text, mode, engine, targetFps)
                    Trainer.isCollectorCsv(text) -> Trainer.parseCollectorCsv(text, mode, engine, targetFps)
                    else -> Trainer.parseCsv(text, mode, targetFps)
                }
                if (rows.size < 32) {
                    uiHandler.post {
                        training = false
                        Toast.makeText(
                            this,
                            if (srcKind == "unknown") "could not parse '$srcKind' — pick a trace/telemetry/live CSV"
                            else "$srcKind: need ≥32 usable rows, found ${rows.size}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }
                val tp = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                val lr = (tp.getInt("train_lr_e4", 2).coerceIn(1, 100)) * 1e-4f
                val gamma = tp.getInt("train_gamma_pct", 90).coerceIn(50, 99) / 100f
                val batch = tp.getInt("train_batch", 32).coerceIn(8, 256)
                val t = OfflineTrainer(engine, rows, epochs, lr, gamma, batch, targetFps, mode)
                activeTrainer = t
                t.run(
            onProgress = { ep, total, loss, ms ->
                uiHandler.post { trainBar.progress = (ep * 100f / total).toInt(); trainProgress.text = "epoch $ep/$total · loss ${String.format("%.5f", loss)} · ${ms / 1000}s" }
            },
            onDone = { losses ->
                uiHandler.post {
                    training = false
                    activeTrainer = null
                    // the source CSV may have grown while training — refresh its row count
                    selectedCsvFile?.let { f ->
                        if (f.exists()) {
                            val n = f.readText().split('\n').count { it.trim().isNotEmpty() && !it.startsWith("t_ms") }
                            csvInfo.text = "${f.name}  ·  $n rows"; csvInfo.setTextColor(TEXT)
                        }
                    }
                    refreshTrainSessions()
                    try {
                        val fin = losses.lastOrNull() ?: 0f
                        if (!fin.isFinite()) {
                            trainResult.text = "training diverged (NaN loss)\n" +
                                "fix: fewer epochs, then retrain (learn-rate auto-clipped)"
                            trainResult.setTextColor(HOT)
                            return@post
                        }
                        val f = Trainer.trainedFile(this, mode)
                        ModelsDir.atomicWrite(f, engine.toBytes())
                        val name = "trained_${mode}_${SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())}"
                        ModelsDir.saveModel(this, name, engine.toBytes())
                        ModelsDir.saveMeta(this, name, org.json.JSONObject()
                            .put("date", SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date()))
                            .put("rows", rows.size)
                            .put("loss", fin)
                            .put("source", srcKind)
                            .put("reward", "v2").toString())
                        rebuildModels()
                        // live handover: freshly trained model takes over the
                        // running controller immediately (same profile)
                        if (GameModeService.controllerOn && mode == trainMode) {
                            startService(Intent(this@MainActivity, GameModeService::class.java)
                                .setAction(GameModeService.ACTION_APPLY_MODEL)
                                .putExtra(GameModeService.EXTRA_MODEL, name))
                            Toast.makeText(this@MainActivity, "live-swapped into running controller", Toast.LENGTH_SHORT).show()
                        }
                        trainResult.text = "done · $name.bin saved\n" +
                            "final loss ${String.format("%.5f", fin)} · rows ${rows.size} · " +
                            (if (srcKind == "unknown") "app trace" else srcKind) +
                            "\nselect it under Models & Favorites."
                        trainResult.setTextColor(GOOD)
                    } catch (e: Exception) {
                        trainResult.text = "save failed: ${e.message}"; trainResult.setTextColor(HOT)
                    }
                }
            },
            onError = { msg ->
                uiHandler.post { training = false; activeTrainer = null; trainResult.text = "error: $msg"; trainResult.setTextColor(HOT) }
            }
        )
            } catch (e: Throwable) {
                uiHandler.post {
                    training = false
                    trainResult.text = "error: ${e.message ?: e.javaClass.simpleName}"
                    trainResult.setTextColor(HOT)
                }
            }
        }, "train-prep").apply { isDaemon = true }.start()
    }

    private fun buildToolsPage(page: LinearLayout) {
        page.addView(groupCard("1 · Auto-run on game launch").apply {
            addView(cap("① Enable the accessibility toggle (opens settings) ② tick below → Game Mode starts INSTANTLY when you open a picked app, and stops after the idle timer. No polling, no usage access needed."))
            autoRunStatus = tv("checking…", 12f, DIM, mono = true)
            addView(autoRunStatus)
            val grantBtn = pillButton("Enable detector (accessibility)")
            grantBtn.setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33) {
                    try {
                        val i = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        i.putExtra(Intent.EXTRA_COMPONENT_NAME,
                            android.content.ComponentName(this@MainActivity, GameA11yService::class.java))
                        startActivity(i)
                    } catch (e: Exception) { openA11yList() }
                } else openA11yList()
            }
            val rerun = pillButton("Re-run first-start setup")
            rerun.setOnClickListener {
                getSharedPreferences("perfcollect", MODE_PRIVATE).edit().putBoolean("wizard_done", false).apply()
                maybeWizard(force = true)
            }
            addView(buttonRow(listOf(grantBtn)))
            addView(buttonRow(listOf(rerun)))
            val prefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
            addView(CheckBox(this@MainActivity).apply {
                text = "Auto-start Game Mode when a picked app opens"
                isChecked = prefs.getBoolean(GameA11yService.KEY_AUTO_RUN, false)
                setTextColor(TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, c ->
                    prefs.edit().putBoolean(GameA11yService.KEY_AUTO_RUN, c).apply()
                    applyAutoRun(c)
                }
            })
            addView(tv("Stop controller after no game for:", 12f, TEXT))
            val stopBar = SeekBar(this@MainActivity).apply { max = 14; progress = prefs.getInt(GameA11yService.KEY_AUTO_STOP_MIN, 5) - 1 }
            val stopVal = tv("${stopBar.progress + 1} min", 11f, DIM)
            stopBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { stopVal.text = "${p + 1} min" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt(GameA11yService.KEY_AUTO_STOP_MIN, stopBar.progress + 1).apply() }
            })
            addView(stopBar); addView(stopVal)
            val pickApps = pillButton("Choose apps for auto-start")
            pickApps.setOnClickListener { showAppPicker() }
            addView(buttonRow(listOf(pickApps)))
            addView(cap("PER-GAME MODES — pin performance/balanced/battery/cool per game; auto-start uses the pinned profile instead of the global one."))
            gameMapStatus = tv(gameMapSummary(), 11f, DIM, mono = true)
            addView(gameMapStatus)
            val pickModes = pillButton("Set mode per game")
            pickModes.setOnClickListener { showPerGameModePicker() }
            addView(buttonRow(listOf(pickModes)))
        })

        page.addView(groupCard("2 · Guardians & battery").apply {
            val gm = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
            addView(cap("Battery floor always on; thermal pause optional (model regulates heat itself)."))
            addView(CheckBox(this@MainActivity).apply {
                text = "Pause burn when screen off (OFF = model runs even with screen off)"
                isChecked = gm.getBoolean(GameModeService.KEY_SCREEN_PAUSE, true)
                setTextColor(TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, c -> gm.edit().putBoolean(GameModeService.KEY_SCREEN_PAUSE, c).apply() }
            })
            addView(tv("Stop controller below battery:", 12f, TEXT))
            val battBar = SeekBar(this@MainActivity).apply { max = 25; progress = gm.getInt(GameModeService.KEY_MIN_BATT, 15) - 5 }
            val battVal = tv("${battBar.progress + 5}%", 11f, DIM)
            battBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { battVal.text = "${p + 5}%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { gm.edit().putInt(GameModeService.KEY_MIN_BATT, battBar.progress + 5).apply() }
            })
            addView(battBar); addView(battVal)
            // battery brake ceilings — user-tunable (workload auto-pauses at these)
            addView(tv("Battery brake while using (pause & cool):", 12f, TEXT))
            val bbUse = SeekBar(this@MainActivity).apply { max = 10; progress = gm.getInt("guard_batt_c", 43) - 38 }
            val bbUseVal = tv("${gm.getInt("guard_batt_c", 43)}°C", 11f, DIM)
            bbUse.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { bbUseVal.text = "${p + 38}°C" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { gm.edit().putInt("guard_batt_c", bbUse.progress + 38).apply() }
            })
            addView(bbUse); addView(bbUseVal)
            addView(tv("Battery brake while charging (tighter — charge heat stacks):", 12f, TEXT))
            val bbChg = SeekBar(this@MainActivity).apply { max = 10; progress = gm.getInt("guard_batt_chg_c", 41) - 36 }
            val bbChgVal = tv("${gm.getInt("guard_batt_chg_c", 41)}°C", 11f, DIM)
            bbChg.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { bbChgVal.text = "${p + 36}°C" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { gm.edit().putInt("guard_batt_chg_c", bbChg.progress + 36).apply() }
            })
            addView(bbChg); addView(bbChgVal)
            addView(CheckBox(this@MainActivity).apply {
                text = "Emergency chip thermal pause"
                isChecked = gm.getBoolean(GameModeService.KEY_THERMAL_GUARD_ON, false)
                setTextColor(TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, c -> gm.edit().putBoolean(GameModeService.KEY_THERMAL_GUARD_ON, c).apply() }
            })
            addView(cap("Trace storage cap — old CSV rotates to *.old when exceeded:"))
            val capE = EditText(this@MainActivity).apply {
                setText(gm.getInt(GameModeService.KEY_TRACE_CAP_VALUE, 5).toString())
                setSingleLine(true); textSize = 12f
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(TEXT); gravity = Gravity.END
                background = GradientDrawable().apply { setColor(PANEL2); cornerRadius = dpf(12f); setStroke(1, PANEL) }
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            }
            var unitSel = gm.getString(GameModeService.KEY_TRACE_CAP_UNIT, "MB") ?: "MB"
            val unitHost = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val unitBtns = mutableMapOf<String, Button>()
            fun saveCap(vRaw: Int?, u: String) {
                val v = (vRaw ?: gm.getInt(GameModeService.KEY_TRACE_CAP_VALUE, 5)).coerceIn(1, 4096)
                if (vRaw != null) capE.setText(v.toString())
                unitSel = u
                gm.edit().putInt(GameModeService.KEY_TRACE_CAP_VALUE, v)
                    .putString(GameModeService.KEY_TRACE_CAP_UNIT, u).apply()
                Toast.makeText(this@MainActivity, "cap: $v $u", Toast.LENGTH_SHORT).show()
            }
            capE.setOnFocusChangeListener { _, has -> if (!has) saveCap(capE.text.toString().trim().toIntOrNull(), unitSel) }
            listOf("KB", "MB").forEach { u ->
                val b = pillButton(u); unitBtns[u] = b
                b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3f) }
                b.setOnClickListener {
                    saveCap(capE.text.toString().trim().toIntOrNull(), u)
                    unitBtns.forEach { (k, btn) ->
                        val on = k == u
                        btn.background = GradientDrawable().apply { setColor(if (on) ACCENT else PANEL2); cornerRadius = dpf(18f) }
                        btn.setTextColor(if (on) BG else TEXT)
                    }
                }
                if (u == unitSel) { b.background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dpf(18f) }; b.setTextColor(BG) }
                unitHost.addView(b)
            }
            val capRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                capE.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(capE); addView(unitHost)
            }
            addView(capRow)
            val optBtn = pillButton("Allow battery optimization exemption")
            optBtn.setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (_: Exception) { Toast.makeText(this@MainActivity, "unavailable: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
            addView(buttonRow(listOf(optBtn)))
        })

        page.addView(groupCard("3 · One-tap retrain all").apply {
            retrainSourceLabel = tv("source: pick a CSV on the Train page first", 11f, DIM, mono = true)
            addView(retrainSourceLabel)
            retrainBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            addView(retrainBar)
            retrainProgress = tv("", 11f, DIM, mono = true)
            addView(retrainProgress)
            addView(bigActionButton("RETRAIN ALL MODELS").apply { setOnClickListener { startRetrainAll() } })
        })

        page.addView(groupCard("4 · Session chart (trace)").apply {
            chart = ChartView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170f)).apply { bottomMargin = dp(6f) }
            }
            addView(chart)
            chartInfo = tv("no data", 10f, DIM, mono = true)
            addView(chartInfo)
            val refreshBtn = pillButton("Refresh chart")
            refreshBtn.setOnClickListener { loadChart() }
            addView(buttonRow(listOf(refreshBtn)))
        })

        page.addView(groupCard("5 · Model pool size").apply {
            slotsLabel = tv("", 12f, TEXT)
            addView(slotsLabel)
            val unlimited = CheckBox(this@MainActivity).apply {
                text = "Unlimited (never evict snapshots)"
                isChecked = ModelsDir.maxSlots(this@MainActivity) <= 0
                setTextColor(TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            }
            val slotsBar = SeekBar(this@MainActivity).apply { max = 47; progress = ModelsDir.maxSlots(this@MainActivity).coerceIn(3, 50) - 3 }
            fun updSlots() {
                if (unlimited.isChecked) { slotsLabel.text = "keep ALL snapshots"; slotsBar.isEnabled = false }
                else { slotsLabel.text = "keep ${slotsBar.progress + 3} snapshots (favorites never evicted)"; slotsBar.isEnabled = true }
            }
            updSlots()
            unlimited.setOnCheckedChangeListener { _, _ ->
                ModelsDir.setMaxSlots(this@MainActivity, if (unlimited.isChecked) 0 else slotsBar.progress + 3)
                updSlots(); rebuildModels()
            }
            slotsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { updSlots() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (!unlimited.isChecked) { ModelsDir.setMaxSlots(this@MainActivity, slotsBar.progress + 3); rebuildModels() }
                }
            })
            addView(unlimited); addView(slotsBar)
        })

        page.addView(groupCard("5 · Model health (NaN scan)").apply {
            addView(cap("Scans every model (pool + trained cache). Files with NaN weights are quarantined to *.bad so they can never be loaded — healthy models untouched."))
            val scanOut = tv("not run", 10f, DIM, mono = true)
            addView(scanOut)
            addView(bigActionButton("SCAN & QUARANTINE BAD MODELS").apply {
                setOnClickListener {
                    Thread({
                        val bad = ArrayList<String>()
                        val names = ModelsDir.listModels(this@MainActivity)
                        for (n in names) {
                            val f = ModelsDir.loadModel(this@MainActivity, n) ?: continue
                            val dims = KotlinMlpEngine.headerOf(f.readBytes())
                            var poison = dims == null
                            if (!poison) try {
                                val e = KotlinMlpEngine.fromBytes(f.readBytes())
                                if (e.qValues(FloatArray(e.nIn)).any { it.isNaN() }) poison = true
                            } catch (_: Exception) { poison = true }
                            if (poison) {
                                val dst = File(f.parentFile, "$n.bad")
                                f.renameTo(dst)
                                bad.add(n)
                            }
                        }
                        for (m in FROZEN) {
                            val f = Trainer.trainedFile(this@MainActivity, m)
                            if (f.exists()) {
                                var poison = false
                                try {
                                    val e = KotlinMlpEngine.fromBytes(f.readBytes())
                                    if (e.qValues(FloatArray(e.nIn)).any { it.isNaN() }) poison = true
                                } catch (_: Exception) { poison = true }
                                if (poison) {
                                    // replace poisoned trained-cache with the frozen base
                                    val assetName = "qnet_$m.bin"
                                    f.writeBytes(assets.open(assetName).readBytes())
                                    bad.add("$m (reset to frozen)")
                                }
                            }
                        }
                        uiHandler.post {
                            scanOut.text = if (bad.isEmpty()) "all ${names.size} models healthy"
                            else "quarantined:\n" + bad.joinToString("\n") { "· $it" }
                            scanOut.setTextColor(if (bad.isEmpty()) GOOD else WARN)
                            rebuildModels()
                        }
                    }, "nan-scan").apply { isDaemon = true }.start()
                }
            })
        })


        refreshTools()
    }

    private fun buildBenchPage(page: LinearLayout) {
        page.addView(groupCard("Full heavy-load A/B").apply {
            addView(cap("MAX load (6 threads @100%), alternating NORMAL vs MODEL blocks in random order — deltas are pure booster effect. Controller must be OFF."))
        })
        val prefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
        page.addView(groupCard("Test length").apply {
            val armBar = SeekBar(this@MainActivity).apply { max = 14; progress = prefs.getInt("bench_arm_min", 2) - 1 }
            val armVal = tv("${armBar.progress + 1} min/arm", 12f, ACCENT, bold = true)
            armBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { armVal.text = "${p + 1} min/arm" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt("bench_arm_min", armBar.progress + 1).apply() }
            })
            addView(armBar); addView(armVal)
            val cycBar = SeekBar(this@MainActivity).apply { max = 4; progress = prefs.getInt("bench_cycles", 2) - 1 }
            val cycVal = tv("${cycBar.progress + 1} cycles", 12f, ACCENT, bold = true)
            cycBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { cycVal.text = "${p + 1} cycles" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt("bench_cycles", cycBar.progress + 1).apply() }
            })
            addView(cycBar); addView(cycVal)
        })

        page.addView(groupCard("Benchmark model").apply {
            val bprefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
            val saved = bprefs.getString("bench_model", "@chain") ?: "@chain"
            val opts = listOf("@chain" to "Follow profile/favourite") +
                ModelsDir.listModels(this@MainActivity).map { it to it } + FROZEN.map { it to it }
            addView(flowPills(opts, saved.takeIf { o -> opts.any { it.first == o } } ?: "@chain") { k ->
                bprefs.edit().putString("bench_model", k).apply()
            })
            addView(cap("MODEL arm uses this model. '@chain' = favourite snapshot of each profile, else trained file, else frozen base."))
        })

        page.addView(groupCard("Run").apply {
            benchStatus = tv("idle — controller must be OFF", 12f, DIM, mono = true)
            addView(benchStatus)
            addView(bigActionButton("START BENCH (NORMAL vs MODEL)").apply { setOnClickListener { startBench() } })
            addView(bigActionButton("EXTREME TEST · ALL 4 PROFILES").apply { setOnClickListener { startExtremeBench() } })
            addView(pillButton("STOP BENCH").apply { setOnClickListener { benchRunning = false } })
            benchResult = tv("", 11f, DIM, mono = true)
            addView(benchResult)
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_CSV && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try {
                    val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: ""
                    selectedCsvText = text
                    selectedCsvFile = null
                    val n = text.split('\n').count { it.trim().isNotEmpty() && !it.startsWith("t_ms") }
                    csvInfo.text = "${uri.lastPathSegment}  ·  $n rows"; csvInfo.setTextColor(TEXT)
                } catch (e: Exception) {
                    Toast.makeText(this, "read failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (requestCode == PICK_BIN && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    importWeights(bytes, uri.lastPathSegment ?: "file")
                } catch (e: Exception) {
                    Toast.makeText(this, "import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- Page 3: Telemetry ----------

    private fun buildTelePage(page: LinearLayout) {
        // 1 · COLLECTOR
        page.addView(groupCard("1 · Collector").apply {

            statusView = tv("loading…", 12f, DIM, mono = true)
            addView(statusView)
            val startBtn = pillButton("Start collection", accent = true)
            startBtn.setOnClickListener {
                // collector sampling would contaminate both bench arms
                if (benchRunning) { Toast.makeText(this@MainActivity, getString(R.string.toast_bench_in_progress), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                requestNotifPermission(); startForegroundService(Intent(this@MainActivity, CollectorService::class.java).setAction(CollectorService.ACTION_START))
            }
            val stopBtn = pillButton("Stop collection")
            stopBtn.setOnClickListener { startService(Intent(this@MainActivity, CollectorService::class.java).setAction(CollectorService.ACTION_STOP)) }
            addView(buttonRow(listOf(startBtn, stopBtn)))
            val evtHeavy = pillButton("Mark: HEAVY")
            evtHeavy.setOnClickListener {
                if (!CollectorService.isRunning) { Toast.makeText(this@MainActivity, "start collection first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                startService(Intent(this@MainActivity, CollectorService::class.java).setAction(CollectorService.ACTION_EVENT).putExtra(CollectorService.EXTRA_LABEL, "WORKLOAD_HEAVY"))
            }
            val evtIdle = pillButton("Mark: IDLE")
            evtIdle.setOnClickListener {
                if (!CollectorService.isRunning) { Toast.makeText(this@MainActivity, "start collection first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                startService(Intent(this@MainActivity, CollectorService::class.java).setAction(CollectorService.ACTION_EVENT).putExtra(CollectorService.EXTRA_LABEL, "IDLE"))
            }
            addView(buttonRow(listOf(evtHeavy, evtIdle)))
        })

        // 2 · EXPORT & FILES
        page.addView(groupCard("2 · Export & files").apply {

            addView(bigActionButton("EXPORT ALL SESSIONS").apply {
                setOnClickListener {
                    ensureStorageAccess()
                    isEnabled = false
                    Thread({
                        val msg = try {
                            val res = SessionExporter(this@MainActivity).exportAllSessions()
                            if (res.isEmpty()) "no sessions to export"
                            else {
                                val ok = res.count { it.jsonl != null || it.csv != null || it.eventsCsv != null }
                                getString(R.string.toast_export_done, ok, res.size - ok) +
                                    " → " + SessionExporter.targetExportDir(this@MainActivity).absolutePath
                            }
                        } catch (e: Exception) {
                            "export failed: ${e.message}"
                        }
                        uiHandler.post {
                            isEnabled = true
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }, "export-all").apply { isDaemon = true }.start()
                }
            })
            addView(pillButton("Delete ALL sessions").apply {
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete everything?")
                        .setMessage("Removes all sessions + their exported CSV/JSONL files, and clears the app CSV cache (traces/live).")
                        .setPositiveButton("Delete") { _, _ ->
                            store.deleteAllSessions()
                            SessionFiles.deleteAll(this@MainActivity)
                            listOf("gamemode_trace.csv", "gamemode_live.csv").forEach { n ->
                                try { File(filesDir, n).delete() } catch (_: Exception) {}
                            }
                            refreshTelemetry()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            addView(pillButton("Clear CSV cache (traces)").apply {
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Clear CSV cache?")
                        .setMessage("Deletes gamemode_trace.csv + gamemode_live.csv (app-internal training data). Sessions are kept.")
                        .setPositiveButton("Clear") { _, _ ->
                            listOf("gamemode_trace.csv", "gamemode_live.csv").forEach { n ->
                                try { File(filesDir, n).delete() } catch (_: Exception) {}
                            }
                            Toast.makeText(this@MainActivity, "CSV cache cleared", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
        })

        // 3 · SETTINGS
        page.addView(groupCard("3 · Settings").apply {
            addView(CheckBox(this@MainActivity).apply {
                text = "Auto-start collection on boot"
                isChecked = getSharedPreferences("perfcollect", MODE_PRIVATE).getBoolean("autostart", true)
                setTextColor(TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { _, checked -> getSharedPreferences("perfcollect", MODE_PRIVATE).edit().putBoolean("autostart", checked).apply() }
            })
            addView(sectionTitle("Polling interval"))

            val options = listOf(
                "Heavy (2s)" to 2_000L, "Fast (5s)" to 5_000L, "Normal (10s)" to 10_000L, "Slow (30s)" to 30_000L, "Eco (60s)" to 60_000L,
            )
            val saved = getSharedPreferences("perfcollect", MODE_PRIVATE).getLong(CollectorService.KEY_POLL_MS, 10_000L)
            val radio = RadioGroup(this@MainActivity)
            options.forEach { (label, ms) ->
                val rb = RadioButton(this@MainActivity).apply {
                    text = label; id = ms.toInt()
                    isChecked = saved == ms
                    setTextColor(TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                }
                radio.addView(rb)
            }
            radio.setOnCheckedChangeListener { _, checkedId ->
                getSharedPreferences("perfcollect", MODE_PRIVATE).edit().putLong(CollectorService.KEY_POLL_MS, checkedId.toLong()).apply()
                Toast.makeText(this@MainActivity, "polling set to ${checkedId}ms", Toast.LENGTH_SHORT).show()
            }
            addView(radio)
        })

        // 4 · SESSIONS
        page.addView(groupCard("4 · Sessions").apply {
            sessionsView = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(sessionsView)
        })
    }

    // ---------- Page 4: Weights ----------

    private fun buildWeightsPage(page: LinearLayout) {
        page.addView(card().apply {
            addView(sectionTitle("Custom RL weights"))
            addView(tv("Load ANY Q-network weights (.bin, float32 header n1/n2/nOut/nIn) and hot-swap into the RUNNING controller. NO size or dimension limit — any shape loads. Best RL control with 8 inputs / ≥15 outputs; other shapes run best-effort.", 12f, TEXT))
        })

        page.addView(groupCard("Status").apply {
            weightsStatus = tv("controller OFF", 12f, DIM, mono = true)
            addView(weightsStatus)
        })

        page.addView(groupCard("Import").apply {
            addView(bigActionButton("IMPORT .BIN FROM STORAGE").apply {
                setOnClickListener {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra("android.content.extra.SHOW_ADVANCED", true)
                    }
                    startActivityForResult(i, PICK_BIN)
                }
            })
            addView(tv("Tip: any valid QNet .bin works — frozen assets, trained snapshots, or nets you trained elsewhere (8 inputs / 15 outputs required; hidden widths free).", 10f, DIM))
        })

        page.addView(groupCard("Profile tilt (instant steering)").apply {
            addView(cap("Per-profile nudge added to Q-values each tick — instant steering; training calibrates long-term."))
            val gp = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
            val hdr = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            hdr.addView(tv("", 11f, DIM).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
            })
            listOf("q0 idle", "q1 low", "q2 mid", "q3 high", "q4 max").forEach { h ->
                hdr.addView(tv(h, 8f, DIM).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3f) }
                })
            }
            addView(hdr)
            for (mode in FROZEN) {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(tv(mode, 11f, TEXT, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
                })
                val defs = com.iqoo.perfcollect.ml.PolicyConfig.PROFILE_BIAS[mode]!!
                for (qi in 0 until PolicyConfig.N_Q) {
                    val e = EditText(this@MainActivity).apply {
                        setText(String.format(Locale.US, "%.2f", gp.getFloat("bias_${mode}_$qi", defs[qi])))
                        setSingleLine(true); textSize = 9f
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setTextColor(TEXT); gravity = Gravity.CENTER
                        background = GradientDrawable().apply { setColor(PANEL2); cornerRadius = dpf(8f); setStroke(1, PANEL) }
                        setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
                        layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(3f) }
                        setOnFocusChangeListener { _, has -> if (!has) {
                            text.toString().trim().replace(',', '.').toFloatOrNull()?.let { v ->
                                gp.edit().putFloat("bias_${mode}_$qi", v.coerceIn(-2.0f, 2.0f)).apply()
                                if (GameModeService.controllerOn)
                                    Toast.makeText(this@MainActivity, "$mode q$qi tilt saved — live from next tick", Toast.LENGTH_SHORT).show()
                            }
                        } }
                    }
                    row.addView(e)
                }
                addView(row)
            }
            addView(cap("Saved on focus-loss · range −2.00…+2.00 · applies to the next tick."))
        })
    }

    private fun importWeights(bytes: ByteArray, display: String) {
        val dims = KotlinMlpEngine.headerOf(bytes)
        if (dims == null) {
            Toast.makeText(this, "rejected $display: not a valid QNet .bin (corrupt or wrong length)", Toast.LENGTH_LONG).show()
            return
        }
        val n1 = dims[0]; val n2 = dims[1]; val nOut = dims[2]; val nIn = dims[3]
        try { KotlinMlpEngine.fromBytes(bytes) } catch (e: Exception) {
            Toast.makeText(this, "invalid weights: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val name = "user_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
        ModelsDir.saveModel(this, name, bytes)
        rebuildModels()
        val shape = "$nIn×$n1×$n2×$nOut"
        val note = when {
            nIn == PolicyConfig.N_STATE && nOut >= PolicyConfig.N_ACTIONS -> ""
            else -> " · NOTE: nIn=8 & nOut≥15 give full RL control; this net runs best-effort"
        }
        if (GameModeService.controllerOn) {
            startService(Intent(this@MainActivity, GameModeService::class.java)
                .setAction(GameModeService.ACTION_APPLY_MODEL)
                .putExtra(GameModeService.EXTRA_MODEL, name))
            Toast.makeText(this, "$display imported ($shape) — hot-swapped into running controller$note", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "$display imported as $name ($shape) — now in Game › Models (tap to use)$note", Toast.LENGTH_LONG).show()
        }
    }

    private fun col(label: String, value: TextView): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        c.addView(tv(label, 9f, DIM, bold = true)); c.addView(value)
        return c
    }

    // ---------- live UI loop ----------

    private val uiLoop = object : Runnable {
        override fun run() {
            val on = GameModeService.controllerOn
            val g = GameModeService
            cancelTrainBtn?.visibility = if (training) View.VISIBLE else View.GONE
            if (on) elapsedSec = ((System.currentTimeMillis() - g.startedAtMs).coerceAtLeast(0)) / 1000 else elapsedSec = 0
            val view = v
            if (view != null) {
                val dispHz = if (on) g.lastDisplayHz.toInt() else (if (Build.VERSION.SDK_INT >= 30) display?.mode?.refreshRate?.toInt() ?: 120 else 120)
                view.fps.text = "$dispHz"
                view.fps.setTextColor(if (dispHz >= 90) ACCENT else if (dispHz >= 60) GOOD else WARN)

                val chipTemp = if (on && g.lastTempChip > 0f) g.lastTempChip else LiveTelemetry.chipC
                val battTemp = if (on && g.lastTempBatt > 0f) g.lastTempBatt else LiveTelemetry.skinC
                val modemTemp = if (on && g.lastTempModem > 0f) g.lastTempModem else LiveTelemetry.modemC

                fun setTemp(tv: TextView, c: Float) {
                    tv.text = if (c > 0f) String.format(java.util.Locale.US, "%.0f°", c) else "--"
                    tv.setTextColor(if (c > 0f) tempColor(c) else DIM)
                }
                setTemp(view.chip, chipTemp)
                setTemp(view.batt, battTemp)
                setTemp(view.modem, modemTemp)
                view.load.text = if (on) String.format(java.util.Locale.US, "%.0f%%", g.lastIntensity * 100) else "--"
                view.net.text = if (on) String.format(java.util.Locale.US, "%.0fM", g.lastMbps) else "--"
                view.action.text = if (on) "Q${g.lastAction / PolicyConfig.N_NET} / N${g.lastNetTier}" else "STANDBY"
                view.latency.text = if (on && g.lastLatencyMs >= 0) String.format(java.util.Locale.US, "%.0fms", g.lastLatencyMs) else "--"
                view.loss.text = if (on && g.lastPacketLoss >= 0) String.format(java.util.Locale.US, "%.0f%%", g.lastPacketLoss.coerceIn(0f, 100f)) else "--"

                val mm = String.format(java.util.Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
                val primeMhz = if (on) g.lastPrimeFreqMhz.toInt() else LiveTelemetry.bigCoreFreqMhz.toInt()
                val avgMhz = if (on) g.lastCpuFreqMhz.toInt() else LiveTelemetry.cpuFreqMhz.toInt()
                val cores = if (on) g.lastCores else LiveTelemetry.coresOnline
                val gpu = if (on) g.lastGpuPct.toInt() else LiveTelemetry.gpuBusyPct.toInt()
                val hdrm = if (on) g.lastHeadroom.toInt() else LiveTelemetry.thermalHeadroomPct.toInt()
                val battLvl = if (on) g.lastBattLevel.toInt() else LiveTelemetry.battLevel.toInt()
                val gp2 = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                val prof = if (on) g.liveProfile else (gp2.getString(GameModeService.KEY_MODE, "balanced") ?: "balanced")
                val activeMdl = if (on) g.modelUsed else (gp2.getString(GameModeService.KEY_ACTIVE_MODEL, null) ?: "qnet_$prof.bin")

                view.stats.text = buildString {
                    append(if (on) "Governor: ACTIVE" else "Governor: STANDBY")
                    if (on) append(" · ").append(mm).append(" (").append(g.tickCount).append(" ticks)")
                    append('\n')
                    append("CPU: ").append(primeMhz).append(" MHz prime · ").append(avgMhz).append(" MHz avg (").append(cores).append(" cores)\n")
                    append("GPU: ").append(gpu).append("% · Headroom: ").append(hdrm).append("% · Battery: ").append(battLvl).append("%")
                    if (on) {
                        append('\n')
                        append("RL Reward: ").append(String.format(java.util.Locale.US, "%+.2f", g.lastReward))
                        append(" · Workload: ").append(String.format(java.util.Locale.US, "%.0f fps", g.lastFps))
                        append(" (").append(String.format(java.util.Locale.US, "%.0f MFLOP/s", g.lastWorkMf)).append(")")
                        val sigDbm = LiveTelemetry.signalDbm
                        val sigStr = if (sigDbm != -1) "$sigDbm dBm" else "Good"
                        val antiChoke = if (LiveTelemetry.isLowSignal) " · Low-Signal Anti-Choke" else ""
                        append("\nLink: ").append(sigStr).append(antiChoke)
                    }
                }
                view.model.text = "Profile: $prof · Model: $activeMdl" + (if (on) " (active)" else "")
            }
            if (::weightsStatus.isInitialized) {
                weightsStatus.text = buildString {
                    append("controller ").append(if (on) "ON" else "OFF")
                    append(" · active ").append(activeModel()).append('\n')
                    if (on) append("running model: ").append(g.modelUsed)
                }
                weightsStatus.setTextColor(if (on) GOOD else DIM)
            }
            if (currentPage == 2) {
                uiTicks++
                val sc = CollectorService.liveSampleCount
                if (uiTicks % 6 == 0L || sc != lastSamples) { lastSamples = sc; refreshTelemetry() }
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private class Views {
        lateinit var fps: TextView; lateinit var chip: TextView; lateinit var batt: TextView
        lateinit var modem: TextView; lateinit var load: TextView; lateinit var net: TextView
        lateinit var action: TextView; lateinit var latency: TextView; lateinit var loss: TextView
        lateinit var stats: TextView; lateinit var model: TextView
    }

    override fun onResume() { super.onResume(); refreshTelemetry() }
    override fun onDestroy() {
        benchRunning = false
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Volatile private var teleRefreshInFlight = false

    private fun refreshTelemetry() {
        if (!::statusView.isInitialized || !::sessionsView.isInitialized) return
        if (teleRefreshInFlight) return
        teleRefreshInFlight = true
        val running = CollectorService.isRunning
        Thread({
            val (list, statusText) = try {
                val sc = store.sampleCount()
                val dbMb = store.dbSizeBytes() / 1048576.0
                store.listSessions() to buildString {
                    append("collector: ").append(if (running) "RUNNING" else "idle").append('\n')
                    append("samples stored: ").append(sc)
                    append("  ·  db ").append(String.format(Locale.US, "%.1f MB", dbMb))
                    if (CollectorService.liveSessionId > 0)
                        append("\nsession #").append(CollectorService.liveSessionId)
                            .append(" live · ").append(CollectorService.liveSampleCount).append(" samples")
                }
            } catch (e: Exception) {
                emptyList<SessionMeta>() to "collector: error (${e.message})"
            }
            uiHandler.post {
                teleRefreshInFlight = false
                if (!::statusView.isInitialized || !::sessionsView.isInitialized) return@post
                statusView.text = statusText
                sessionsView.removeAllViews()
                if (list.isEmpty()) { sessionsView.addView(tv("(no sessions yet)", 11f, DIM)); return@post }
                val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                for (s in list) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4f), 0, dp(4f)) }
                    val info = tv(buildString {
                        append("#").append(s.id)
                        append("  ").append(fmt.format(Date(s.startedAt)))
                        append("  n=").append(s.samples)
                        append("  ").append(String.format(Locale.US, "%.1fMB", s.sizeBytes / 1048576.0))
                        append(if (s.endedAt == null) "  RUN" else "")
                    }, 10f, DIM, mono = true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(info)
                    val del = Button(this).apply { text = "Delete"; minHeight = dp(36f) }
                    del.setOnClickListener {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete session #${s.id}?")
                            .setMessage("Removes the session + its exported CSV/JSONL files.")
                            .setPositiveButton("Delete") { _, _ ->
                                store.deleteSession(s.id)
                                SessionFiles.deleteForSession(this@MainActivity, s.id)
                                refreshTelemetry()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                    row.addView(del)
                    sessionsView.addView(row)
                }
            }
        }, "tele-refresh").apply { isDaemon = true }.start()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) return
            Toast.makeText(this, "granting All-files access (iqoo-data at /sdcard)", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).setData(Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun quickSelfTest(out: TextView) {
        out.text = "testing…"; out.setTextColor(DIM)
        Thread({
            val lines = ArrayList<String>()
            try {
                val t0 = System.currentTimeMillis()
                val eng = Trainer.loadEngine(this, activeModel())
                val stx = LiveTelemetry.sample(this, 0f, 0f, -1f, -1f, -1, 0f, 0, 120)
                val q = eng.qValues(PolicyConfig.normalize(stx))
                lines.add("infer ${System.currentTimeMillis() - t0}ms → #${q.indices.maxByOrNull { q[it] }}")
            } catch (e: Exception) { lines.add("infer FAIL ${e.message}") }
            try {
                LiveTelemetry.sample(this, 0f, 0f, -1f, -1f, -1, 0f, 0, 120)
                lines.add("${com.iqoo.perfcollect.collect.ThermalCollector().zoneCount()} zones · ${LiveTelemetry.cpuFreqMhz.toInt()}MHz · batt ${LiveTelemetry.battLevel.toInt()}%")
            } catch (e: Exception) { lines.add("sensors FAIL") }
            uiHandler.post {
                out.text = lines.joinToString("  ·  ")
                out.setTextColor(if (lines.any { it.contains("FAIL") }) HOT else GOOD)
            }
        }, "qtest").apply { isDaemon = true }.start()
    }

    private fun refreshTools() {
        if (!::autoRunStatus.isInitialized) return
        val prefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
        val hasAccess = GameA11yService.enabled(this)
        val enabled = prefs.getBoolean(GameA11yService.KEY_AUTO_RUN, false)
        autoRunStatus.text = buildString {
            append("detector: ").append(if (hasAccess) "ENABLED" else "disabled")
            append(" · auto-run: ").append(if (enabled) "ARMED" else "off").append('\n')
            GameA11yService.detectedGame?.let { append("in game: ").append(it).append('\n') }
            append("controller: ").append(if (GameModeService.controllerOn) "ON" else "OFF")
            if (GameModeService.controllerOn) append(" · ").append(if (GameModeService.paused) "PAUSED" else "active")
            GameA11yService.lastTrigger?.let { append("last trigger: ").append(it).append('\n') }
        }
        autoRunStatus.setTextColor(if (hasAccess && enabled) GOOD else DIM)
        if (::gameMapStatus.isInitialized) gameMapStatus.text = gameMapSummary()
        retrainSourceLabel.text = if (selectedCsvText != null) "source: $selectedCsvFile" else "source: pick a CSV on the Train page first"
    }

    private fun openA11yList() {
        try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        Toast.makeText(this,
            "Settings → Accessibility → Downloaded apps → 'iQOO Game Mode' → enable 'Game auto-run detector'",
            Toast.LENGTH_LONG).show()
    }

    private fun showAppPicker() {
        val prefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
        val selected = prefs.getStringSet(GameA11yService.KEY_AUTO_APPS, emptySet())!!.toMutableSet()
        val all = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { ri -> ri.activityInfo.packageName to (ri.loadLabel(packageManager).toString()) }
            .filter { it.first != packageName }
            .distinctBy { it.first }.sortedBy { it.second.lowercase(Locale.US) }
        if (all.isEmpty()) { Toast.makeText(this, "no apps visible", Toast.LENGTH_SHORT).show(); return }
        val labels = all.map { (pPkg, l) -> "$l  ·  $pPkg" }
        val checked = all.map { it.first in selected }.toBooleanArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Auto-start Game Mode for… (${all.size} apps)")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                val pPkg = all[which].first
                if (isChecked) selected.add(pPkg) else selected.remove(pPkg)
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet(GameA11yService.KEY_AUTO_APPS, selected).apply()
                Toast.makeText(this, "${selected.size} app(s) will auto-start Game Mode", Toast.LENGTH_SHORT).show()
                refreshTools()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun gameMapJson(): org.json.JSONObject = try {
        org.json.JSONObject(getSharedPreferences(GameModeService.PREF, MODE_PRIVATE).getString("gamemap", "{}") ?: "{}")
    } catch (_: Exception) { org.json.JSONObject() }

    private fun gameMapSummary(): String = "per-game modes: ${gameMapJson().length()} mapped"

    private fun showPerGameModePicker() {
        val cur = gameMapJson()
        val all = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { ri -> ri.activityInfo.packageName to ri.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale.US) }
        if (all.isEmpty()) { Toast.makeText(this, "no apps visible", Toast.LENGTH_SHORT).show(); return }
        val labels = all.map { (pPkg, l) ->
            val pin = cur.optString(pPkg).takeIf { it.isNotEmpty() }?.let { "[$it] " } ?: ""
            "$pin$l  ·  $pPkg"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Pin a game-mode profile… (${all.size} apps)")
            .setItems(labels.toTypedArray()) { _, which -> showPerGameModeChoice(all[which].first) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showPerGameModeChoice(pkg: String) {
        val modes = listOf("performance", "balanced", "battery", "cool")
        val opts = modes + "Remove mapping"
        val now = gameMapJson().optString(pkg).takeIf { it.isNotEmpty() }?.let { "  ·  now: $it" } ?: ""
        android.app.AlertDialog.Builder(this)
            .setTitle(pkg + now)
            .setItems(opts.toTypedArray()) { _, which ->
                val m = gameMapJson()
                if (which < modes.size) m.put(pkg, modes[which]) else m.remove(pkg)
                getSharedPreferences(GameModeService.PREF, MODE_PRIVATE).edit().putString("gamemap", m.toString()).apply()
                refreshTools()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun startRetrainAll() {
        if (retraining) { Toast.makeText(this, "retrain already running…", Toast.LENGTH_SHORT).show(); return }
        val text = selectedCsvText ?: run { Toast.makeText(this, "select a CSV on the Train page first", Toast.LENGTH_LONG).show(); return }
        retraining = true
        val epochs = 20
        Thread({
            val results = ArrayList<String>()
            try {
                for ((i, mode) in listOf("performance", "balanced", "battery", "cool").withIndex()) {
                    uiHandler.post { retrainProgress.text = "training $mode (${i + 1}/4)…"; retrainBar.progress = i * 25 }
                    try {
                        val engine = Trainer.loadEngine(this, mode)
                        val targetFps = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                            .getInt(GameModeService.KEY_TARGET_FPS, 120)
                        val rows = when {
                            Trainer.isRlTrace(text) -> Trainer.parseCsv(text, mode, targetFps)
                            Trainer.isLiveCsv(text) -> Trainer.parseLiveCsv(text, mode, engine, targetFps)
                            Trainer.isCollectorCsv(text) -> Trainer.parseCollectorCsv(text, mode, engine, targetFps)
                            else -> Trainer.parseCsv(text, mode, targetFps)
                        }
                        if (rows.size < 32) { results.add("$mode: skipped (${rows.size} rows)"); continue }
                        val tp = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
                        val lr = tp.getInt("train_lr_e4", 2).coerceIn(1, 100) * 1e-4f
                        val gamma = tp.getInt("train_gamma_pct", 90).coerceIn(50, 99) / 100f
                        val batch = tp.getInt("train_batch", 32).coerceIn(8, 256)
                        val targetFps2 = tp.getInt(GameModeService.KEY_TARGET_FPS, 120)
                        val t = OfflineTrainer(engine, rows, epochs, lr, gamma, batch, targetFps2, mode)
                        val losses = t.trainBlocking({ ep, total, loss, ms ->
                            uiHandler.post {
                                retrainProgress.text = "$mode · epoch $ep/$total · loss ${String.format("%.5f", loss)} · ${ms / 1000}s"
                                retrainBar.progress = (i * 100 + ep * 100 / total) / 4
                            }
                        }, { }) ?: continue
                        val finL = losses.lastOrNull() ?: 0f
                        if (!finL.isFinite()) { results.add("$mode: diverged (NaN) — skipped"); continue }
                        ModelsDir.atomicWrite(Trainer.trainedFile(this, mode), engine.toBytes())
                        val name = "trained_${mode}_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
                        ModelsDir.saveModel(this, name, engine.toBytes())
                        ModelsDir.saveMeta(this, name, org.json.JSONObject()
                            .put("date", SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date()))
                            .put("rows", rows.size)
                            .put("loss", finL)
                            .put("source", "retrain-all").put("reward", "v2").toString())
                        results.add("$mode: ok (loss ${String.format("%.4f", losses.lastOrNull() ?: 0f)}, ${rows.size} rows)")
                    } catch (e: Exception) { results.add("$mode: failed (${e.message})") }
                }
                uiHandler.post {
                    retrainProgress.text = results.joinToString("\n")
                    retrainBar.progress = 100
                    rebuildModels()
                }
            } finally {
                uiHandler.post { retraining = false }
            }
        }, "retrain-all").apply { isDaemon = true }.start()
    }

    private fun loadChart() {
        val c = chart ?: return
        try {
            val f = getFileStreamPath("gamemode_trace.csv")
            if (!f.exists()) {
                chartInfo.text = "no trace file yet — run Game Mode"
                c.setData(emptyList()); return
            }
            val tail = readTail(f, 90_000)
            val data = tail.split('\n').drop(1).mapNotNull { l ->
                val pL = l.split(',')
                if (pL.size < 15 || pL[0] == "t_ms") return@mapNotNull null
                val fps = pL[9].trim().toFloatOrNull() ?: return@mapNotNull null
                val ts = pL[6].trim().toFloatOrNull() ?: return@mapNotNull null
                floatArrayOf(fps, ts)
            }.takeLast(240)
                c.setData(data)
                chartInfo.text = if (data.isEmpty()) "no usable rows in trace"
                else String.format(java.util.Locale.US,
                    "%d ticks · fps %.0f-%.0f (now %.0f) · temp %.1f-%.1fC (now %.1f)",
                    data.size,
                    data.minOf { it[0] }, data.maxOf { it[0] }, data.last()[0],
                    data.minOf { it[1] }, data.maxOf { it[1] }, data.last()[1])
        } catch (e: Exception) {
            chartInfo.text = "chart failed: ${e.message}"
            android.util.Log.e("Chart", "loadChart failed", e)
        }
    }

    /** reads the last ~bytes of a file without loading it all */
    private fun readTail(f: File, bytes: Int): String {
        val len = Math.min(f.length(), bytes.toLong()).toInt()
        val raf = java.io.RandomAccessFile(f, "r")
        try {
            raf.seek((f.length() - len).coerceAtLeast(0L))
            val buf = ByteArray(len)
            raf.readFully(buf)
            return String(buf)
        } finally { raf.close() }
    }

    // ---------- Bench engine ----------

    private class BenchSt {
        val fps: MutableList<Float> = java.util.Collections.synchronizedList(ArrayList())
        val chip: MutableList<Float> = java.util.Collections.synchronizedList(ArrayList())
        val batt: MutableList<Float> = java.util.Collections.synchronizedList(ArrayList())
        val mhz: MutableList<Float> = java.util.Collections.synchronizedList(ArrayList())
        val ts: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList()) // wall-clock capture time per sample (real-time x-axis)

        fun snapFps(): List<Float> = synchronized(fps) { ArrayList(fps) }
        fun snapChip(): List<Float> = synchronized(chip) { ArrayList(chip) }
        fun snapBatt(): List<Float> = synchronized(batt) { ArrayList(batt) }
        fun snapMhz(): List<Float> = synchronized(mhz) { ArrayList(mhz) }
        fun snapTs(): List<Long> = synchronized(ts) { ArrayList(ts) }
    }

    private fun sliderFps(): Int =
        getSharedPreferences(GameModeService.PREF, MODE_PRIVATE).getInt(GameModeService.KEY_TARGET_FPS, 120)

    private fun benchEngineFor(mode: String): KotlinMlpEngine {
        val sel = getSharedPreferences("perfcollect", MODE_PRIVATE).getString("bench_model", "@chain") ?: "@chain"
        if (sel != "@chain") {
            ModelsDir.loadModel(this, sel)?.let { f ->
                try { return KotlinMlpEngine.fromBytes(f.readBytes()) } catch (_: Exception) {}
            }
        }
        ModelsDir.preferredFavourite(this, mode)?.let { n ->
            ModelsDir.loadModel(this, n)?.let { f ->
                try { return KotlinMlpEngine.fromBytes(f.readBytes()) } catch (_: Exception) {}
            }
        }
        return Trainer.loadEngine(this, mode)
    }

    private fun driveModelArm(eng: KotlinMlpEngine, mode: String, session: HintBoost.Session?, st: FloatArray, genFps: Double, gen: LoadGenerator) {
        try {
            val gmPrefs = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
            val a = PolicyConfig.chooseAction(eng, PolicyConfig.normalize(st), gmPrefs, mode)
            var qT = a / PolicyConfig.N_NET
            when (mode) {
                "performance" -> qT = qT.coerceAtLeast(gmPrefs.getInt(GameModeService.KEY_CLAMP_PERF_MIN, 2))
                "battery" -> qT = qT.coerceAtMost(gmPrefs.getInt(GameModeService.KEY_CLAMP_BATT_MAX, 1))
                "cool" -> qT = qT.coerceAtMost(gmPrefs.getInt(GameModeService.KEY_CLAMP_COOL_MAX, 1))
            }
            qT = qT.coerceIn(0, PolicyConfig.N_Q - 1)
            val demandScale = when (mode) {
                "cool" -> 0.20f
                "battery" -> 0.45f
                "balanced" -> 0.80f
                else -> 1.00f
            }
            val knee = PolicyConfig.SKIN_KNEE[mode] ?: 45f
            val thermalExcess = (st[1] - knee).coerceAtLeast(0f)
            val easeMultiplier = when (mode) {
                "cool" -> (1f - thermalExcess * 0.08f).coerceIn(0.40f, 1.0f)
                "battery" -> (1f - thermalExcess * 0.06f).coerceIn(0.50f, 1.0f)
                "balanced" -> (1f - thermalExcess * 0.04f).coerceIn(0.65f, 1.0f)
                else -> (1f - thermalExcess * 0.02f).coerceIn(0.80f, 1.0f)
            }
            val intensity = (PolicyConfig.LOAD[qT] * demandScale * easeMultiplier).coerceIn(0.05f, 1.0f)
            gen.intensity = intensity
            if (session != null) {
                session.setPowerEfficient(mode == "battery" || mode == "cool")
                val qualityScale = 0.7f + 0.45f * intensity
                session.updateTargetFps(PolicyConfig.effectiveBoostFps(sliderFps(), mode, st[1], st[0], qualityScale))
                if (genFps > 0.5) session.reportFrame((1_000_000_000.0 / genFps).toLong())
            }
        } catch (_: Exception) {}
    }

    private fun benchArmLoop(gen: LoadGenerator, session: HintBoost.Session?, eng: KotlinMlpEngine?,
                             mode: String, ms: Long, st: BenchSt, label: String) {
        var deadline = System.currentTimeMillis() + ms
        android.util.Log.i("Bench", "arm start: $label")
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        var n = 0
        var pauseStart = 0L
        while (benchRunning && System.currentTimeMillis() < deadline) {
            // anti-contamination + anti-"pocket cook": pause the burn while
            // the screen is off instead of letting 6 threads run unsupervised
            if (!pm.isInteractive) {
                gen.setPaused(true)
                if (pauseStart == 0L) pauseStart = System.currentTimeMillis()
                try { Thread.sleep(1500) } catch (_: InterruptedException) { gen.setPaused(false); return }
                continue
            }
            // screen back on — extend the wall-clock deadline by the paused time so a
            // screen-off blip doesn't eat into the arm's effective sample budget
            if (pauseStart != 0L) {
                deadline += System.currentTimeMillis() - pauseStart
                pauseStart = 0L
                gen.setPaused(false)
            }
            try {
                val smp = LiveTelemetry.sample(this, gen.fps().toFloat(), 0f, -1f, -1f, -1, 1f, 0, sliderFps())
                val fpsD = gen.fps()
                if (smp[5] > 0.5f && fpsD > 0.5) st.fps.add(smp[5])
                st.chip.add(smp[0]); st.batt.add(smp[1])
                st.mhz.add(LiveTelemetry.bigCoreFreqMhz)
                st.ts.add(System.currentTimeMillis())
                if (eng != null) driveModelArm(eng, mode, session, smp, fpsD, gen)
                n++
                if (n % 5 == 0) android.util.Log.i("Bench", "$label sample=$n fps=${gen.fps().toInt()} mhz=${LiveTelemetry.bigCoreFreqMhz.toInt()}")
            } catch (e: Exception) {
                android.util.Log.e("Bench", "sample failed in $label", e)
                break
            }
            uiHandler.post { if (::benchStatus.isInitialized)
                benchStatus.text = "$label · ${gen.fps().toInt()}fps · chip ${st.chip.lastOrNull()?.toInt() ?: 0}°C · ${LiveTelemetry.bigCoreFreqMhz.toInt()}MHz" }
            try { Thread.sleep(BENCH_SAMPLE_MS) } catch (_: InterruptedException) { return }
        }
        android.util.Log.i("Bench", "arm end: $label samples=$n running=$benchRunning")
    }

    /** sleeps up to ms in 1s slices; false when STOP was pressed during cooldown */
    private fun benchCooldown(ms: Long): Boolean {
        var left = ms
        while (left > 0 && benchRunning) {
            val slice = minOf(left, 1000L)
            try { Thread.sleep(slice) } catch (_: InterruptedException) { return false }
            left -= slice
        }
        return benchRunning
    }

    private fun setupBenchHardware(): Array<Any?>? = try {
        LiveTelemetry.init(this)
        val gmPrefs = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
        val defThreads = Runtime.getRuntime().availableProcessors().coerceIn(6, 8)
        val threads = gmPrefs.getInt(GameModeService.KEY_THREADS, defThreads)
        val g = LoadGenerator(threads = threads).apply {
            opsScale = gmPrefs.getInt("workload_pct", 100).coerceIn(50, 300) / 100f
            intensity = 1.0f
            start()
        }
        var sess: HintBoost.Session? = null
        val hm = HintBoost.create(this)
        repeat(10) {
            if (sess == null && g.tids().isNotEmpty()) sess = hm?.createSession(g.tids(), 8_333_333L)
            if (sess == null) Thread.sleep(200)
        }
        if (sess != null) {
            g.frameReporter = { ns -> sess?.reportFrame(ns) }
        }
        arrayOf(g, sess)
    } catch (e: Exception) {
        uiHandler.post { if (::benchStatus.isInitialized) benchStatus.text = "bench init failed: ${e.message}" }
        benchRunning = false; null
    }

    private fun teardownBench(gen: LoadGenerator?, session: HintBoost.Session?) {
        try { session?.close() } catch (_: Exception) {}
        gen?.stop()
        LiveTelemetry.close()
        benchRunning = false
    }

    private fun startBench() {
        if (!::benchStatus.isInitialized) return
        if (benchRunning) { Toast.makeText(this, "bench already running…", Toast.LENGTH_SHORT).show(); return }
        if (GameModeService.controllerOn) { Toast.makeText(this, "STOP Game Mode first", Toast.LENGTH_LONG).show(); return }
        val prefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
        val armMin = prefs.getInt("bench_arm_min", 2).coerceIn(1, 15)
        val cycles = prefs.getInt("bench_cycles", 2).coerceIn(1, 5)
        val mode = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
            .getString(GameModeService.KEY_MODE, "balanced") ?: "balanced"
        benchRunning = true
        Thread({
            var gen: LoadGenerator? = null
            var session: HintBoost.Session? = null
            val base = BenchSt(); val model = BenchSt()
            var doneCycles = 0
            try {
                val hw = setupBenchHardware() ?: return@Thread
                gen = hw[0] as LoadGenerator
                session = hw[1] as HintBoost.Session?
                gen.setProfile(mode)
                val eng = benchEngineFor(mode)
                val firstModel = java.util.Random().nextBoolean()
                for (c in 1..cycles) {
                    // ABBA: alternate arm order every cycle (first assignment random once)
                    // so thermal drift balances across arms instead of piling onto arm 2
                    val modelFirst = if (c % 2 == 1) firstModel else !firstModel
                    val order = if (modelFirst) listOf("MODEL" to model, "NORMAL" to base)
                                else listOf("NORMAL" to base, "MODEL" to model)
                    for ((name, st) in order) {
                        val isModel = name == "MODEL"
                        if (!isModel) gen.intensity = 1.0f
                        benchArmLoop(gen, if (isModel) session else null, if (isModel) eng else null,
                            mode, armMin * 60_000L, st, "cycle $c/$cycles · $name")
                        if (!benchRunning) break
                    }
                    if (!benchRunning) break
                    doneCycles++
                    // inter-cycle cooldown: start each cycle near thermal equilibrium
                    if (c < cycles && !benchCooldown(BENCH_CYCLE_COOLDOWN_MS)) break
                }
            } catch (e: Exception) {
                uiHandler.post { if (::benchStatus.isInitialized) benchStatus.text = "bench failed: ${e.message}" }
            } finally {
                if (base.fps.isNotEmpty() || model.fps.isNotEmpty())
                    emitVerdict(mode, base, model, quiet = false, partial = doneCycles < cycles)
                teardownBench(gen, session)
                uiHandler.post { if (::benchStatus.isInitialized && !benchStatus.text.contains("failed")) benchStatus.text = "idle" }
            }
        }, "bench").apply { isDaemon = true }.start()
    }

    private fun startExtremeBench() {
        if (!::benchStatus.isInitialized) return
        if (benchRunning) { Toast.makeText(this, "bench already running…", Toast.LENGTH_SHORT).show(); return }
        if (GameModeService.controllerOn) { Toast.makeText(this, "STOP Game Mode first", Toast.LENGTH_LONG).show(); return }
        val prefs = getSharedPreferences("perfcollect", MODE_PRIVATE)
        val armMin = prefs.getInt("bench_arm_min", 2).coerceIn(1, 3)
        val gm = getSharedPreferences(GameModeService.PREF, MODE_PRIVATE)
        val origMode = gm.getString(GameModeService.KEY_MODE, "balanced") ?: "balanced"
        benchRunning = true
        Thread({
            var gen: LoadGenerator? = null
            var session: HintBoost.Session? = null
            try {
                val hw = setupBenchHardware() ?: return@Thread
                gen = hw[0] as LoadGenerator
                session = hw[1] as HintBoost.Session?
                val summary = StringBuilder("EXTREME TEST — all profiles · ${armMin}min/arm\n")
                var doneProfiles = 0
                for (mode in listOf("performance", "balanced", "battery", "cool")) {
                    if (!benchRunning) break
                    gm.edit().putString(GameModeService.KEY_MODE, mode).commit()
                    gen.setProfile(mode)
                    gen.intensity = 1.0f // reset intensity for NORMAL baseline
                    val eng = benchEngineFor(mode)
                    val base = BenchSt(); val model = BenchSt()
                    benchArmLoop(gen, null, null, mode, armMin * 60_000L, base, "$mode · NORMAL")
                    if (!benchRunning) break
                    benchArmLoop(gen, session, eng, mode, armMin * 60_000L, model, "$mode · MODEL")
                    if (!benchRunning) break
                    doneProfiles++
                    summary.append(profileLine(mode, base, model)).append('\n')
                    emitVerdict("extreme_$mode", base, model, quiet = true, partial = false)
                    uiHandler.post { if (::benchResult.isInitialized) benchResult.text = summary.toString() }
                    if (doneProfiles < 4) benchCooldown(BENCH_CYCLE_COOLDOWN_MS)
                }
                if (doneProfiles < 4) summary.insert(0, "ABORTED/PARTIAL\n")
                try {
                    val dir = File(com.iqoo.perfcollect.Storage.benchDir(this@MainActivity),
                        "extreme_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date()))
                    dir.mkdirs()
                    val sf = File(dir, "summary.txt"); ModelsDir.atomicWriteText(sf, summary.toString())
                    android.media.MediaScannerConnection.scanFile(this@MainActivity,
                        arrayOf(sf.absolutePath), null, null)
                } catch (_: Exception) {}
                uiHandler.post {
                    if (::benchResult.isInitialized) { benchResult.text = summary.toString(); benchResult.setTextColor(GOOD) }
                }
            } catch (e: Exception) {
                uiHandler.post { if (::benchStatus.isInitialized) benchStatus.text = "extreme failed: ${e.message}" }
            } finally {
                gm.edit().putString(GameModeService.KEY_MODE, origMode).commit()
                teardownBench(gen, session)
                uiHandler.post {
                    profilePillRestyle?.invoke() // pills re-highlight the restored mode
                    if (::benchStatus.isInitialized && !benchStatus.text.contains("failed")) benchStatus.text = "idle"
                }
            }
        }, "bench-extreme").apply { isDaemon = true }.start()
    }

    private fun profileLine(mode: String, base: BenchSt, model: BenchSt): String {
        val baseFps = base.snapFps(); val modelFps = model.snapFps()
        val baseMhz = base.snapMhz(); val modelMhz = model.snapMhz()
        fun sus(f: List<Float>) = if (f.isEmpty()) 0f else f.takeLast((f.size / 4).coerceAtLeast(1)).average().toFloat()
        fun avg(l: List<Float>) = if (l.isEmpty()) 0f else l.average().toFloat()
        val d = if (sus(baseFps) > 0f) (sus(modelFps) / sus(baseFps) - 1f) * 100f else 0f
        return String.format(Locale.US, "%-11s NORMAL[sus %.1f clk %.0fMHz] MODEL[sus %.1f clk %.0fMHz] D%+.1f%%",
            mode, sus(baseFps), avg(baseMhz), sus(modelFps), avg(modelMhz), d)
    }

    private fun benchSummary(s: BenchSt): String {
        val f = s.snapFps()
        val c = s.snapChip()
        val m = s.snapMhz()
        fun avg(l: List<Float>) = if (l.isEmpty()) 0f else l.average().toFloat()
        val sortedFps = f.sorted()
        return "avg ${String.format(Locale.US, "%.1f", avg(f))}" +
            " · P50 ${String.format(Locale.US, "%.1f", pct(sortedFps, 0.50f))}" +
            " · 10%Low ${String.format(Locale.US, "%.1f", pct(sortedFps, 0.10f))}" +
            " · 1%Low ${String.format(Locale.US, "%.1f", pct(sortedFps, 0.01f))} fps" +
            " · chipPeak ${if (c.isEmpty()) 0 else c.max().toInt()}C · bigclk ${String.format(Locale.US, "%.0f", avg(m))}MHz"
    }

    /** percentile of a sorted-ascending series with linear interpolation */
    private fun pct(sortedAsc: List<Float>, p: Float): Float {
        if (sortedAsc.isEmpty()) return 0f
        if (sortedAsc.size == 1) return sortedAsc[0]
        val idx = (sortedAsc.size - 1) * p.coerceIn(0f, 1f)
        val low = idx.toInt().coerceIn(0, sortedAsc.size - 1)
        val high = (low + 1).coerceIn(0, sortedAsc.size - 1)
        val frac = idx - low
        return sortedAsc[low] * (1f - frac) + sortedAsc[high] * frac
    }

    private fun stdev(l: List<Float>): Float {
        if (l.size < 2) return 0f
        val m = l.average()
        return sqrt(l.map { (it - m) * (it - m) }.average()).toFloat()
    }

    /** skin-temp slope °C/min over the last half of samples at BENCH_SAMPLE_MS cadence */
    private fun tempSlopeCPerMin(s: BenchSt): Float {
        val c = s.snapChip()
        val half = c.takeLast((c.size / 2).coerceAtLeast(2))
        if (half.size < 2) return 0f
        val mins = (half.size - 1) * BENCH_SAMPLE_MS / 60_000f
        if (mins <= 0f) return 0f
        return (half.last() - half.first()) / mins
    }

    /** sustained-envelope line for one arm: fps stddev + temp slope over the LAST HALF */
    private fun sustainedLine(label: String, s: BenchSt): String {
        val f = s.snapFps()
        val half = f.takeLast((f.size / 2).coerceAtLeast(1))
        return String.format(Locale.US, "%s SUSTAINED(last-half): sd %.2f fps · slope %+.2f C/min",
            label, stdev(half), tempSlopeCPerMin(s))
    }

    private fun emitVerdict(tag: String, base: BenchSt, model: BenchSt, quiet: Boolean, partial: Boolean) {
        val baseFps = base.snapFps()
        val modelFps = model.snapFps()
        fun avg(l: List<Float>) = if (l.isEmpty()) 0f else l.average().toFloat()
        val avgDelta = if (avg(baseFps) > 0f) (avg(modelFps) / avg(baseFps) - 1f) * 100f else 0f
        // 10%Low is the headline: avg hides stutter, bottom-10% baseline does not
        val p90Base = pct(baseFps.sorted(), 0.10f)
        val p90Model = pct(modelFps.sorted(), 0.10f)
        val p90Delta = if (p90Base > 0f) (p90Model / p90Base - 1f) * 100f else 0f
        val verdict = buildString {
            if (partial) append("ABORTED/PARTIAL\n")
            append("NORMAL: ").append(benchSummary(base)).append('\n')
            append("MODEL : ").append(benchSummary(model)).append('\n')
            append(sustainedLine("NORMAL", base)).append('\n')
            append(sustainedLine("MODEL", model)).append('\n')
            append(String.format(Locale.US, "AVG DELTA : %+.1f%%\n", avgDelta))
            append(String.format(Locale.US, "10%%LOW DELTA: %+.1f%% ", p90Delta))
            append(if (p90Delta > 2f) "- model WINS" else if (p90Delta < -2f) "- model LOSES" else "- tie")
        }
        uiHandler.post {
            if (::benchResult.isInitialized) {
                benchResult.text = verdict
                benchResult.setTextColor(if (p90Delta > 2f) GOOD else if (p90Delta < -2f) HOT else DIM)
            }
        }
        try {
            val dir = File(com.iqoo.perfcollect.Storage.benchDir(this@MainActivity),
                tag + "_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date()))
            dir.mkdirs()
            val sf = File(dir, "summary.txt"); ModelsDir.atomicWriteText(sf, verdict + "\n")
            drawBenchGraph(base, model)?.let { bmp ->
                try {
                    val gf = File(dir, "graph.png")
                    java.io.FileOutputStream(gf).use { fos -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fos) }
                    try { android.media.MediaScannerConnection.scanFile(this@MainActivity,
                        arrayOf(sf.absolutePath, gf.absolutePath), null, null) } catch (_: Exception) {}
                } finally {
                    bmp.recycle()
                }
            }
            if (!quiet) uiHandler.post {
                Toast.makeText(this@MainActivity,
                    if (partial) "partial results (aborted)"
                    else "MODEL ${String.format(Locale.US, "%+.1f", p90Delta)}% sustained (10%Low) → ${dir.absolutePath}",
                    Toast.LENGTH_LONG).show()
            }
        } catch (_: Throwable) {}
    }

    /** fps + chip-temp chart with REAL numeric axes */
    private fun drawBenchGraph(base: BenchSt, model: BenchSt): android.graphics.Bitmap? {
        val bFps = base.snapFps(); val mFps = model.snapFps()
        val bChip = base.snapChip(); val mChip = model.snapChip()
        val bTs = base.snapTs(); val mTs = model.snapTs()
        val n = maxOf(bFps.size, mFps.size)
        if (n < 4) return null
        val W = 1080; val H = 620
        val lPad = 130f; val rPad = 150f; val tPad = 90f; val bPad = 110f
        val allF = bFps + mFps
        val allT = bChip + mChip
        if (allF.isEmpty()) return null
        val bmp = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(Color.parseColor("#0A0C10"))
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val fLo: Float
        val fHi: Float
        var tLo = ((allT.minOrNull() ?: 40f) - 2f).coerceAtLeast(0f)
        var tHi = (allT.maxOrNull() ?: 60f) * 1.03f
        var lo = ((allF.minOrNull() ?: 0f) * 0.9f).toInt().coerceAtLeast(0).toFloat()
        var hi = ((allF.maxOrNull() ?: 1f) * 1.08f).toInt().toFloat()
        // degenerate autoscale: all-identical values would divide ~0 and vanish lines — pad
        if (hi - lo < 1f) { lo -= 1f; hi += 1f }
        if (tHi - tLo < 1f) { tLo -= 0.5f; tHi += 0.5f }
        fLo = lo; fHi = hi
        fun fy(v: Float) = H - bPad - (v - fLo) / (fHi - fLo).coerceAtLeast(0.001f) * (H - tPad - bPad)
        fun ty(v: Float) = H - bPad - (v - tLo) / (tHi - tLo).coerceAtLeast(0.001f) * (H - tPad - bPad)
        val plotW = W - lPad - rPad
        // real-time x-axis from captured wall-clock sample times; index fallback if absent
        val haveTs = bTs.size == bFps.size && mTs.size == mFps.size &&
            bTs.size > 1 && mTs.size > 1
        val tMin = if (haveTs) minOf(bTs.first(), mTs.first()) else 0L
        val spanSec = if (haveTs) ((maxOf(bTs.last(), mTs.last()) - tMin) / 1000f).coerceAtLeast(0.001f)
                      else ((n - 1) * BENCH_SAMPLE_MS / 1000f).coerceAtLeast(1f)
        fun gx(i: Int) = lPad + i * plotW / (n - 1).coerceAtLeast(1)
        fun gxT(t: Long) = lPad + (t - tMin) / 1000f / spanSec * plotW
        p.strokeWidth = 1f; p.color = Color.parseColor("#1A2430")
        for (g in 0..5) {
            val fv = fLo + (fHi - fLo) * g / 5f
            val y = fy(fv)
            c.drawLine(lPad, y, W - rPad, y, p)
            p.textSize = 28f
            p.textAlign = android.graphics.Paint.Align.RIGHT; p.color = DIM
            c.drawText(String.format("%.0f", fv), lPad - 12f, y + 10f, p)
            p.textAlign = android.graphics.Paint.Align.LEFT
            c.drawText(String.format("%.0fC", tLo + (tHi - tLo) * g / 5f), W - rPad + 12f, y + 10f, p)
        }
        val secs = spanSec.toInt()
        val step = (secs / 5).coerceAtLeast(2)
        var sec = 0
        while (sec <= secs) {
            val xx = lPad + sec.toFloat() / spanSec * plotW
            c.drawLine(xx, H - bPad, xx, H - bPad + 12f, p)
            p.textAlign = android.graphics.Paint.Align.CENTER; p.color = DIM
            c.drawText("${sec}s", xx, H - bPad + 44f, p)
            sec += step
        }
        fun line(ys: List<Float>, xs: List<Long>, color: Int, wide: Boolean, dashed: Boolean, map: (Float) -> Float) {
            if (ys.size < 2) return
            p.color = color; p.strokeWidth = if (wide) 5f else 3f
            p.pathEffect = if (dashed) DashPathEffect(floatArrayOf(12f, 8f), 0f) else null
            for (i in 1 until ys.size) {
                val x0: Float; val x1: Float
                if (xs.size == ys.size && haveTs) { x0 = gxT(xs[i - 1]); x1 = gxT(xs[i]) }
                else { x0 = gx(i - 1); x1 = gx(i) }
                c.drawLine(x0, map(ys[i - 1]), x1, map(ys[i]), p)
            }
            p.pathEffect = null
        }
        line(bFps, bTs, Color.parseColor("#8B98A5"), true, false, ::fy)
        line(mFps, mTs, ACCENT, true, false, ::fy)
        line(bChip, bTs, WARN, false, true, ::ty)
        line(mChip, mTs, HOT, false, true, ::ty)
        p.pathEffect = null
        p.pathEffect = null
        p.textSize = 32f; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = android.graphics.Paint.Align.LEFT
        p.color = ACCENT; c.drawText("MODEL", lPad, 44f, p)
        p.color = Color.parseColor("#8B98A5"); c.drawText("NORMAL", lPad + 170f, 44f, p)
        p.color = HOT; c.drawText("MODEL temp", lPad + 350f, 44f, p)
        p.color = WARN; c.drawText("NORMAL temp", lPad + 600f, 44f, p)
        p.textSize = 24f; p.typeface = Typeface.DEFAULT; p.color = DIM
        c.drawText("solid=fps (left) · dashed=temp C (right) · x=real time", lPad, H - 16f, p)
        return bmp
    }

    // ---------- Chart ----------

    /** zero-dep line chart with min/max autoscale per series */
    private class ChartView(ctx: android.content.Context) : View(ctx) {
        @Volatile private var series: List<FloatArray> = emptyList()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val colors = intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#FFB020"))
        fun setData(rows: List<FloatArray>) {
            series = if (rows.isEmpty()) emptyList()
            else (0 until rows[0].size).map { col -> FloatArray(rows.size) { r -> rows[r][col] } }
            postInvalidate()
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas); canvas.drawColor(PANEL)
            val w = width.toFloat(); val h = height.toFloat()
            val localSeries = series
            if (localSeries.isEmpty()) {
                paint.style = android.graphics.Paint.Style.FILL
                paint.textSize = 30f; paint.color = DIM
                paint.textAlign = android.graphics.Paint.Align.CENTER
                canvas.drawText("no data — run Game Mode, then Refresh", w / 2f, h / 2f, paint)
                paint.textAlign = android.graphics.Paint.Align.LEFT
                return
            }
            paint.strokeWidth = 1f; paint.style = android.graphics.Paint.Style.STROKE; paint.color = PANEL2
            for (i in 1..3) canvas.drawLine(0f, h * i / 4f, w, h * i / 4f, paint)
            localSeries.forEachIndexed { si, ys ->
                if (ys.size < 2) return@forEachIndexed
                var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
                for (v in ys) { if (v < mn) mn = v; if (v > mx) mx = v }
                val rng = (mx - mn).coerceAtLeast(0.001f)
                paint.color = colors[si % colors.size]; paint.strokeWidth = 2.5f
                val stepX = w / (ys.size - 1).coerceAtLeast(1)
                var px = 0f; var py = h - (ys[0] - mn) / rng * (h - 16f) - 8f
                for (i in 1 until ys.size) {
                    val xN = i * stepX; val yN = h - (ys[i] - mn) / rng * (h - 16f) - 8f
                    canvas.drawLine(px, py, xN, yN, paint); px = xN; py = yN
                }
            }
        }
    }

    private fun applyAutoRun(enabled: Boolean) {
        if (enabled && !GameA11yService.enabled(this)) {
            Toast.makeText(this, "enable the accessibility detector first", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            return
        }
        refreshTools()
    }

    // ---------- swipe pager ----------

    private inner class SwipePager(ctx: android.content.Context) : ViewGroup(ctx) {
        private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        private val scroller = android.widget.OverScroller(ctx)
        private var velocityTracker: android.view.VelocityTracker? = null
        private var downX = 0f; private var downY = 0f; private var lastX = 0f
        private var dragging = false
        private var lockToSeekBar = false
        var onPageChange: ((Int) -> Unit)? = null
        var current: Int = 0; private set

        fun addPages(pages: List<View>) {
            pages.forEach {
                it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                addView(it)
            }
        }
        fun setCurrent(idx: Int, smooth: Boolean) {
            current = idx.coerceIn(0, (childCount - 1).coerceAtLeast(0))
            val target = current * width
            if (smooth && target != scrollX) { scroller.startScroll(scrollX, 0, target - scrollX, 0, 260); postInvalidateOnAnimation() }
            else { scroller.abortAnimation(); scrollTo(target, 0) }
        }
        override fun onMeasure(wms: Int, hms: Int) {
            val w = MeasureSpec.getSize(wms); val h = MeasureSpec.getSize(hms)
            setMeasuredDimension(w, h)
            for (i in 0 until childCount) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
            if (!dragging && scroller.isFinished) {
                scrollTo(current * w, 0)
            }
        }
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; lastX = ev.x; dragging = false
                    lockToSeekBar = seekBarUnder(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lockToSeekBar) return false
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f &&
                        canMove(if (dx < 0) 1 else -1)) { lastX = ev.x; dragging = true; parent.requestDisallowInterceptTouchEvent(true) }
                }
            }
            return dragging
        }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (velocityTracker == null) velocityTracker = android.view.VelocityTracker.obtain()
            velocityTracker?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; lastX = ev.x; velocityTracker?.clear(); parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> if (dragging) { scrollBy((lastX - ev.x).toInt(), 0); lastX = ev.x }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false; velocityTracker?.computeCurrentVelocity(1000)
                        val vx = velocityTracker?.xVelocity ?: 0f; val w = width.coerceAtLeast(1)
                        var target = Math.round(scrollX.toFloat() / w)
                        if (Math.abs(vx) > 800) target = if (vx < 0) current + 1 else current - 1
                        val prev = current
                        setCurrent(target, true)
                        if (prev != current) onPageChange?.invoke(current)
                    }
                    velocityTracker?.clear()
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
        override fun onDetachedFromWindow() {
            velocityTracker?.recycle()
            velocityTracker = null
            super.onDetachedFromWindow()
        }
        private fun canMove(dir: Int) = (current + dir) in 0 until childCount

        /** true when the gesture starts on a SeekBar or EditText — never steal those drags */
        private fun seekBarUnder(ev: MotionEvent): Boolean {
            val px = ev.x + scrollX; val py = ev.y
            for (i in 0 until childCount) {
                val page = getChildAt(i)
                if (px >= page.left && px < page.right && py >= page.top && py < page.bottom)
                    return hitTest(page, px - page.left + page.scrollX, py - page.top + page.scrollY)
            }
            return false
        }
        private fun hitTest(v: View, x: Float, y: Float): Boolean {
            if ((v is SeekBar || v is EditText) && v.isVisible) return true
            if (v !is ViewGroup || !v.isVisible) return false
            for (j in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(j)
                if (!c.isVisible) continue
                if (x >= c.left && x < c.right && y >= c.top && y < c.bottom)
                    if (hitTest(c, x - c.left + c.scrollX, y - c.top + c.scrollY)) return true
            }
            return false
        }
        private val View.isVisible get() = visibility == View.VISIBLE
        override fun computeScroll() {
            if (scroller.computeScrollOffset()) { scrollTo(scroller.currX, 0); postInvalidateOnAnimation() }
        }
    }
}