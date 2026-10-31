package com.im_atp.volthalt

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for VoltHalt.
 *
 * The tile represents the max-battery alarm toggle, but shows as "active"
 * whenever either alarm is enabled — so it accurately reflects whether any
 * background monitoring is happening.
 *
 * Tapping the tile updates the UI immediately (no DataStore round-trip wait),
 * giving it a snappy feel. A flow collector keeps it in sync if the alarm state
 * changes from inside the app.
 */
class AlarmTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferencesManager: PreferencesManager
    private var listeningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob = serviceScope.launch {
            combine(
                preferencesManager.alarmEnabledFlow,
                preferencesManager.lowAlarmEnabledFlow
            ) { maxEnabled, lowEnabled -> maxEnabled || lowEnabled }
            .collect { anyEnabled -> applyTileState(anyEnabled) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listeningJob?.cancel()
        listeningJob = null
    }

    override fun onClick() {
        super.onClick()

        val isCurrentlyActive = qsTile?.state == Tile.STATE_ACTIVE
        val newActive = !isCurrentlyActive

        // Update the tile immediately so it feels responsive to the tap.
        applyTileState(newActive)

        if (newActive) {
            serviceScope.launch { preferencesManager.setAlarmEnabled(true) }
            try {
                val intent = Intent(applicationContext, BatteryService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } catch (_: Exception) {
                // Background-start restriction on some OEMs — silently ignore.
            }
        } else {
            serviceScope.launch { preferencesManager.setAlarmEnabled(false) }
            try {
                applicationContext.startService(
                    Intent(applicationContext, BatteryService::class.java).apply {
                        action = BatteryService.ACTION_STOP_MAX_ALARM_FROM_TILE
                    }
                )
            } catch (_: Exception) {}
        }
    }

    private fun applyTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (active) R.string.tile_alarm_on else R.string.tile_alarm_off)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.tile_monitoring else R.string.tile_disabled)
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
