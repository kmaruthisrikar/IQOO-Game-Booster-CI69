package com.iqoo.perfcollect.collect

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.iqoo.perfcollect.SafeRead
import org.json.JSONObject

object AdpfCollector {

    private const val HEADROOM_MIN_INTERVAL_MS = 10_000L
    @Volatile private var lastHeadroomMs = 0L
    @Volatile private var lastHeadroomVal = Float.NaN

    fun collect(context: Context, out: JSONObject) {
        val a = JSONObject()
        SafeRead.attempt("Adpf") {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

            if (Build.VERSION.SDK_INT >= 29) {
                a.put("status", pm.currentThermalStatus)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastHeadroomMs >= HEADROOM_MIN_INTERVAL_MS) {
                    lastHeadroomMs = now
                    val headroom = pm.getThermalHeadroom(10)
                    if (!java.lang.Float.isNaN(headroom)) {
                        lastHeadroomVal = headroom
                    }
                }
                if (!java.lang.Float.isNaN(lastHeadroomVal)) {
                    a.put("headroom_10s", lastHeadroomVal)
                }
            }
        }
        out.put("adpf", a)
    }
}