package com.im_atp.volthalt

import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for starting and stopping battery monitoring.
 * The tile does not change either alarm's enabled setting or threshold.
 */
class AlarmTileService : TileService() {

    private data class TileStatus(
        val monitoringEnabled: Boolean,
        val maxEnabled: Boolean,
        val maxTarget: Int,
        val lowEnabled: Boolean,
        val lowTarget: Int
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferencesManager: PreferencesManager
    private var listeningJob: Job? = null
    private var currentStatus = TileStatus(
        monitoringEnabled = false,
        maxEnabled = false,
        maxTarget = 80,
        lowEnabled = false,
        lowTarget = 20
    )

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            preferencesManager.initializeMonitoringEnabledIfNeeded()
            combine(
                preferencesManager.monitoringEnabledFlow,
                preferencesManager.alarmEnabledFlow,
                preferencesManager.targetPercentageFlow,
                preferencesManager.lowAlarmEnabledFlow,
                preferencesManager.lowTargetPercentageFlow
            ) { monitoringEnabled, maxEnabled, maxTarget, lowEnabled, lowTarget ->
                TileStatus(monitoringEnabled, maxEnabled, maxTarget, lowEnabled, lowTarget)
            }.collect { status ->
                currentStatus = status
                applyTileState(status)
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

        val newMonitoringState = !currentStatus.monitoringEnabled
        currentStatus = currentStatus.copy(monitoringEnabled = newMonitoringState)
        applyTileState(currentStatus)

        serviceScope.launch {
            try {
                val serviceIntent = Intent(applicationContext, BatteryService::class.java)
                if (newMonitoringState) {
                    serviceIntent.action = BatteryService.ACTION_START_MONITORING
                    ContextCompat.startForegroundService(applicationContext, serviceIntent)
                } else {
                    serviceIntent.action = BatteryService.ACTION_STOP_SERVICE
                    ContextCompat.startForegroundService(applicationContext, serviceIntent)
                }
            } catch (_: Exception) {
                // The service may be restricted by some device manufacturers.
                // Opening the app still allows the user to review permissions.
            }
        }
    }

    private fun applyTileState(status: TileStatus) {
        val tile = qsTile ?: return
        tile.state = if (status.monitoringEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            if (status.monitoringEnabled) R.string.tile_monitoring else R.string.tile_disabled
        )

        val statusText = getString(
            R.string.alarm_status_format,
            status.maxTarget,
            getString(if (status.maxEnabled) R.string.alarm_status_enabled else R.string.alarm_status_disabled),
            status.lowTarget,
            getString(if (status.lowEnabled) R.string.alarm_status_enabled else R.string.alarm_status_disabled)
        )

        // Tile subtitles are available from Android 10 (API 29).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = statusText
        }
        // State description is used by accessibility services on Android 11+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = statusText
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
