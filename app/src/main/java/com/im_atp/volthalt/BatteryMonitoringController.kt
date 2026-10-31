package com.im_atp.volthalt

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps BatteryService running exactly while at least one alarm is enabled.
 * Call this after persisting a change to either alarm's enabled preference.
 */
object BatteryMonitoringController {

    private val syncMutex = Mutex()

    suspend fun sync(context: Context, preferencesManager: PreferencesManager) {
        syncMutex.withLock {
            val maxEnabled = preferencesManager.alarmEnabledFlow.first()
            val lowEnabled = preferencesManager.lowAlarmEnabledFlow.first()
            val serviceIntent = Intent(context, BatteryService::class.java)

            if (maxEnabled || lowEnabled) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                serviceIntent.action = BatteryService.ACTION_STOP_SERVICE
                context.startService(serviceIntent)
            }
        }
    }
}
