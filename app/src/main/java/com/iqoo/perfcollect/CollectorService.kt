package com.iqoo.perfcollect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.iqoo.perfcollect.collect.TickBuilder
import com.iqoo.perfcollect.data.SessionStore

class CollectorService : Service() {

    companion object {
        private const val TAG = "CollectorService"
        private const val CHANNEL_ID = "perfcollect"
        private const val NOTIF_ID = 1
        const val PREF = "perfcollect"
        const val KEY_POLL_MS = "poll_interval_ms"
        const val DEFAULT_POLL_MS = 10_000L
        const val ACTION_START = "com.iqoo.perfcollect.START"
        const val ACTION_STOP = "com.iqoo.perfcollect.STOP"
        const val ACTION_EVENT = "com.iqoo.perfcollect.EVENT"
        const val EXTRA_LABEL = "label"
        @Volatile var isRunning = false
            private set
        @Volatile var liveSessionId = -1L
            private set
        @Volatile var liveSampleCount = 0L
            private set
    }

    private fun currentPollMs(): Long =
        getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_POLL_MS, DEFAULT_POLL_MS)

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var sessionId: Long = -1L
    private var seq = 0L
    private var tickBuilder: TickBuilder? = null
    private var store: SessionStore? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // heartbeat gap detection
        try {
            val hp = getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("collector_heartbeat_ms", 0L)
            if (hp > 0L) {
                val gap = android.os.SystemClock.elapsedRealtime() - hp
                val poll = getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_POLL_MS, DEFAULT_POLL_MS)
                if (gap > poll * 2 + 5000) Log.w(TAG, "collector kill-detected: gap ${gap}ms")
            }
        } catch (_: Exception) {}
        createChannel()
        store = SessionStore(this)
        tickBuilder = TickBuilder(this)
        handlerThread = HandlerThread("collector").also { it.start() }
        handler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("collector…"))
        when (intent?.action) {
            ACTION_STOP -> {
                stopCollection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EVENT -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "MARK"
                val sId = sessionId
                if (running && sId > 0) {
                    val tNs = android.os.SystemClock.elapsedRealtimeNanos()
                    handler?.post {
                        store?.addEvent(sId, tNs, label)
                        Log.i(TAG, "event recorded: $label")
                    }
                }
                if (!running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                val shouldRun = getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("collector_should_run", false)
                if (!running && (intent?.action == ACTION_START || shouldRun)) {
                    startCollection()
                } else if (!running && !shouldRun) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
    }

    private fun startCollection() {
        running = true
        isRunning = true
        try { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("collector_should_run", true).apply() } catch (_: Exception) {}
        com.iqoo.perfcollect.ml.LiveTelemetry.init(this)
        sessionId = store?.startSession() ?: -1L
        liveSessionId = sessionId
        liveSampleCount = 0L
        Log.i(TAG, "session started: $sessionId")
            updateNotification("session #$sessionId running")
            scheduleTick(0, 0L)
    }

    private fun stopCollection() {
        running = false
        isRunning = false
        try { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("collector_should_run", false).apply() } catch (_: Exception) {}
        com.iqoo.perfcollect.ml.LiveTelemetry.close()
        handler?.removeCallbacksAndMessages(null)
        if (sessionId > 0) {
            store?.endSession(sessionId)
            Log.i(TAG, "session ended: $sessionId")
        }
        sessionId = -1L
        liveSessionId = -1L
    }

    private fun scheduleTick(delayMs: Long, intervalMs: Long) {
        if (!running) return
        handler?.postDelayed({ collectTick(intervalMs) }, delayMs)
    }

    private fun collectTick(intervalMs: Long) {
        if (!running || sessionId < 0) return
        try { getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong("collector_heartbeat_ms", android.os.SystemClock.elapsedRealtime()).apply() } catch (_: Exception) {}
        try {
            val payload = tickBuilder?.buildTick(seq) ?: return
            // advance seq on EVERY tick (skips included) so the 1-in-6
            // keepalive modulo stays aligned with wall-clock ticks
            seq++
            val next = if (intervalMs > 0) intervalMs else currentPollMs()
            if (payload.contains("\"skip\"")) {
                scheduleTick(next, next)
                return
            }
            store?.addSample(sessionId, android.os.SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(), payload)
            liveSampleCount++
            if (seq % 60 == 0L) store?.cleanup(sessionId)
            updateNotification("session #$sessionId · samples=${store?.sampleCount() ?: 0}")
            scheduleTick(next, next)
        } catch (e: Throwable) {
            Log.e(TAG, "tick failed", e)
            // never die silently; keep the loop going
            scheduleTick(currentPollMs(), currentPollMs())
        }
    }

    private fun scheduleRestartIfNeeded(wasRunning: Boolean, wasShouldRun: Boolean) {
        try {
            if (!wasRunning || !wasShouldRun) return
            if (running) return
            val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val pi = android.app.PendingIntent.getForegroundService(
                this, 9002,
                Intent(this, CollectorService::class.java).setAction(ACTION_START),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                Log.w(TAG, "Exact alarm permission missing — cannot schedule FGS background restart on Android 14+")
                return
            }
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 5000, pi)
            Log.i(TAG, "collector auto-restart scheduled")
        } catch (e: Exception) { Log.w(TAG, "restart schedule failed: ${e.message}") }
    }

    override fun onDestroy() {
        val wasRunning = isRunning
        val wasShouldRun = getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("collector_should_run", false)
        stopCollection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        handlerThread?.quitSafely()
        handlerThread?.join(1500)
        if (wasRunning && wasShouldRun) scheduleRestartIfNeeded(wasRunning, wasShouldRun)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Perf collection", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PerfCollect")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "notif failed: ${e.message}")
        }
    }
}