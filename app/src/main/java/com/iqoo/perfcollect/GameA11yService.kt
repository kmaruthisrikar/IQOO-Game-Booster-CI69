package com.iqoo.perfcollect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Event-driven game detector (replaces the polling watcher).
 * Fires the instant a window state changes — zero polling, zero usage-access.
 * Starts Game Mode when a picked/system game opens; schedules idle-stop after.
 */
class GameA11yService : AccessibilityService() {

    companion object {
        const val PREF = "perfcollect"
        const val KEY_AUTO_RUN = "auto_run_enabled"
        const val KEY_AUTO_STOP_MIN = "auto_stop_min"
        const val KEY_AUTO_APPS = "auto_apps"

        @Volatile var active = false; private set
        @Volatile var detectedGame: String? = null; private set
        @Volatile var lastTrigger: String? = null; private set

        fun enabled(c: Context): Boolean {
            val s = android.provider.Settings.Secure.getString(
                c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return s.contains("${c.packageName}/.${GameA11yService::class.java.simpleName}") ||
                s.contains("${c.packageName}/com.iqoo.perfcollect.GameA11yService")
        }

        private val gamePkgCategoryCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private fun isGamePkg(c: Context, pkg: String?): Boolean {
            if (pkg.isNullOrEmpty() || pkg == c.packageName) return false
            val picked = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getStringSet(KEY_AUTO_APPS, emptySet()) ?: emptySet()
            if (pkg in picked) return true
            gamePkgCategoryCache[pkg]?.let { return it }
            val isGame = try {
                val ai = c.packageManager.getApplicationInfo(pkg, 0)
                ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME ||
                    (ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0
            } catch (_: Exception) { false }
            gamePkgCategoryCache[pkg] = isGame
            return isGame
        }

        private fun batteryOk(c: Context): Int = try {
            val i = c.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val lvl = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val sc = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (lvl >= 0 && sc > 0) lvl * 100 / sc else 100
        } catch (_: Exception) { 100 }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stopPending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active) return
        val prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_RUN, false)) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) {
            // our own UI — cancel any pending stop, keep controller alive
            handler.removeCallbacksAndMessages(null); stopPending = false
            return
        }
        val isSystemOverlay = pkg.startsWith("com.android.systemui") ||
            pkg.contains("inputmethod") || pkg == "android"
        if (isSystemOverlay) return

        val game = isGamePkg(this, pkg)
        if (game) {
            detectedGame = pkg
            lastTrigger = pkg
            stopPending = false
            handler.removeCallbacksAndMessages(null)
            val mapped = try { org.json.JSONObject(
                getSharedPreferences(GameModeService.PREF, Context.MODE_PRIVATE)
                    .getString("gamemap", "{}") ?: "{}"
            ).optString(pkg).takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

            if (!GameModeService.controllerOn && batteryOk(this) >
                getSharedPreferences(GameModeService.PREF, Context.MODE_PRIVATE).getInt("min_batt_pct", 15)
            ) {
                // start the governor SILENTLY — never steal foreground from the game;
                // a pinned per-game profile overrides the global mode pref
                try {
                    val i = Intent(this, GameModeService::class.java).setAction(GameModeService.ACTION_START)
                    mapped?.let { i.putExtra(GameModeService.EXTRA_MODE, it) }
                    startForegroundService(i)
                    if (mapped != null) android.util.Log.i("A11y", "auto-start $pkg with pinned mode $mapped")
                } catch (e: Exception) { android.util.Log.w("A11y", "FGS start denied: ${e.message}") }
            } else if (GameModeService.controllerOn && mapped != null) {
                // live switch profile for newly foregrounded pinned game
                try {
                    val i = Intent(this, GameModeService::class.java).setAction(GameModeService.ACTION_START)
                        .putExtra(GameModeService.EXTRA_MODE, mapped)
                    startForegroundService(i)
                    android.util.Log.i("A11y", "switched active profile to $mapped for $pkg")
                } catch (_: Exception) {}
            }
            return
        }
        // non-game foreground — schedule idle-stop (game==false)
        if (!GameModeService.controllerOn || stopPending) return
        val stopMin = prefs.getInt(KEY_AUTO_STOP_MIN, 5)
        handler.postDelayed({
            stopPending = false
            if (GameModeService.controllerOn) {
                try {
                    startService(Intent(this, GameModeService::class.java).setAction(GameModeService.ACTION_STOP))
                } catch (_: Exception) {}
            }
        }, stopMin * 60_000L)
        stopPending = true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        stopPending = false
        super.onDestroy()
    }
}
