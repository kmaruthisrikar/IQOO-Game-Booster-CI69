package com.iqoo.perfcollect.collect

import android.net.TrafficStats
import com.iqoo.perfcollect.SafeRead
import org.json.JSONObject

object NetworkCollector {

    fun collect(out: JSONObject) {
        val n = JSONObject()
        SafeRead.attempt("Net") {
            val uid = android.os.Process.myUid()
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val rxp = TrafficStats.getTotalRxPackets()
            val txp = TrafficStats.getTotalTxPackets()
            if (rx != -1L) n.put("sys_rx_bytes", rx)
            if (tx != -1L) n.put("sys_tx_bytes", tx)
            if (rxp != -1L) n.put("sys_rx_packets", rxp)
            if (txp != -1L) n.put("sys_tx_packets", txp)

            val urx = TrafficStats.getUidRxBytes(uid)
            val utx = TrafficStats.getUidTxBytes(uid)
            if (urx != -1L) n.put("uid_rx_bytes", urx)
            if (utx != -1L) n.put("uid_tx_bytes", utx)
        }
        out.put("net", n)
    }
}