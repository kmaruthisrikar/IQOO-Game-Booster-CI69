package com.iqoo.perfcollect.collect

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import com.iqoo.perfcollect.SafeRead
import org.json.JSONObject

object DisplayCollector {

    fun collect(context: Context, out: JSONObject) {
        val d = JSONObject()
        SafeRead.attempt("Display") {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (display != null) {
                d.put("refresh_rate", display.mode.refreshRate)
                d.put("width", display.mode.physicalWidth)
                d.put("height", display.mode.physicalHeight)
                d.put("state", display.state)
            }
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            d.put("interactive", pm.isInteractive)
            d.put("device_idle", pm.isDeviceIdleMode)
        }
        out.put("disp", d)
    }
}