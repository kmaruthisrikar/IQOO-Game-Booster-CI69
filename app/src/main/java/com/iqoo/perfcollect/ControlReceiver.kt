package com.iqoo.perfcollect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("action") ?: intent.action ?: return
        val action = when (raw) {
            CollectorService.ACTION_START, "start" -> "start"
            CollectorService.ACTION_STOP, "stop" -> "stop"
            CollectorService.ACTION_EVENT, "event" -> "event"
            GameModeService.ACTION_START, "gm_start" -> "gm_start"
            GameModeService.ACTION_STOP, "gm_stop" -> "gm_stop"
            else -> raw
        }
        fun safe(block: () -> Unit) = try { block() } catch (_: Exception) {}
        when (action) {
            "start" -> safe {
                context.startForegroundService(
                    Intent(context, CollectorService::class.java).setAction(CollectorService.ACTION_START)
                )
            }
            "stop" -> safe {
                if (CollectorService.isRunning) {
                    context.startForegroundService(
                        Intent(context, CollectorService::class.java).setAction(CollectorService.ACTION_STOP)
                    )
                }
            }
            "event" -> safe {
                if (CollectorService.isRunning) {
                    context.startForegroundService(
                        Intent(context, CollectorService::class.java).setAction(CollectorService.ACTION_EVENT)
                            .putExtra(CollectorService.EXTRA_LABEL, intent.getStringExtra("label") ?: "MARK")
                    )
                }
            }
            "gm_start" -> safe {
                context.startForegroundService(
                    Intent(context, GameModeService::class.java).setAction(GameModeService.ACTION_START)
                )
            }
            "gm_stop" -> safe {
                if (GameModeService.controllerOn) {
                    context.startForegroundService(
                        Intent(context, GameModeService::class.java).setAction(GameModeService.ACTION_STOP)
                    )
                }
            }
        }
    }
}