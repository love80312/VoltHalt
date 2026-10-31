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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for the charging-limit alarm.
 *
 * The tile represents only the max-battery alarm setting. The low-battery alarm
 * has its own setting in the app and does not change this tile's active state.
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
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            preferencesManager.alarmEnabledFlow.collect { maxAlarmEnabled ->
                applyTileState(maxAlarmEnabled)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listeningJob?.cancel()
        listeningJob = null
    }

    override fun onClick() {
        super.onClick()

        val newActive = qsTile?.state != Tile.STATE_ACTIVE

        // Update the tile immediately; the DataStore collector will confirm the state.
        applyTileState(newActive)
        serviceScope.launch { preferencesManager.setAlarmEnabled(newActive) }

        try {
            if (newActive) {
                val intent = Intent(applicationContext, BatteryService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } else {
                applicationContext.startService(
                    Intent(applicationContext, BatteryService::class.java).apply {
                        action = BatteryService.ACTION_STOP_MAX_ALARM_FROM_TILE
                    }
                )
            }
        } catch (_: Exception) {
            // Some OEMs restrict background service starts. The app UI can still
            // be opened to review permissions and monitoring settings.
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
