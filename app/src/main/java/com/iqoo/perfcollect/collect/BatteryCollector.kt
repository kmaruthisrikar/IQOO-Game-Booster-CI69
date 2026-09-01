package com.iqoo.perfcollect.collect

import com.iqoo.perfcollect.SafeRead
import org.json.JSONArray
import org.json.JSONObject

object BatteryCollector {

    fun collect(context: android.content.Context, out: JSONObject) {
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        val b = JSONObject()

        SafeRead.attempt("Battery") {
            val cap = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (cap != Integer.MIN_VALUE && cap >= 0) b.put("level_pct", cap)

            val cur = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != Integer.MIN_VALUE) b.put("current_ua", cur)

            val curAvg = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            if (curAvg != Integer.MIN_VALUE) b.put("current_avg_ua", curAvg)

            val energy = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            if (energy != Long.MIN_VALUE) b.put("energy_uwh", energy)

            val charge = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (charge != Long.MIN_VALUE) b.put("charge_counter_uah", charge)
        }

        SafeRead.attempt("BatteryIntent") {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val health = intent.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1)
                val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                val temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)
                val volt = intent.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)
                if (status >= 0) b.put("status", status)
                if (health >= 0) b.put("health", health)
                b.put("plugged", plugged)
                if (temp != -1) b.put("temp_c", temp / 10.0)
                if (volt >= 0) b.put("voltage_mv", volt)
                if (level >= 0 && scale > 0) b.put("raw_level_pct", level * 100.0 / scale)
            }
        }

        out.put("battery", b)
    }
}