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
 * Quick Settings tile for the charging-limit alarm.
 *
 * The tile's active state and click action represent the max-battery alarm.
 * Its subtitle also reports the low-battery alarm state and both thresholds.
 */
class AlarmTileService : TileService() {

    private data class TileStatus(
        val maxEnabled: Boolean,
        val maxTarget: Int,
        val lowEnabled: Boolean,
        val lowTarget: Int
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferencesManager: PreferencesManager
    private var listeningJob: Job? = null
    private var currentStatus = TileStatus(
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
            combine(
                preferencesManager.alarmEnabledFlow,
                preferencesManager.targetPercentageFlow,
                preferencesManager.lowAlarmEnabledFlow,
                preferencesManager.lowTargetPercentageFlow
            ) { maxEnabled, maxTarget, lowEnabled, lowTarget ->
                TileStatus(maxEnabled, maxTarget, lowEnabled, lowTarget)
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

        // The tile continues to toggle only the charging-limit alarm.
        val newActive = qsTile?.state != Tile.STATE_ACTIVE
        currentStatus = currentStatus.copy(maxEnabled = newActive)
        applyTileState(currentStatus)
        serviceScope.launch {
            try {
                // Persist first so BatteryService reads the latest alarm states.
                preferencesManager.setAlarmEnabled(newActive)

                if (newActive) {
                    ContextCompat.startForegroundService(
                        applicationContext,
                        Intent(applicationContext, BatteryService::class.java)
                    )
                } else {
                    ContextCompat.startForegroundService(
                        applicationContext,
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
    }

    private fun applyTileState(status: TileStatus) {
        val tile = qsTile ?: return
        tile.state = if (status.maxEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (status.maxEnabled) R.string.tile_alarm_on else R.string.tile_alarm_off)

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
