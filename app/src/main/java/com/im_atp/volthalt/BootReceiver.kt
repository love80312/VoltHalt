package com.im_atp.volthalt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts battery monitoring after the system finishes booting.
 *
 * Monitoring is resumed only after the initial setup has completed and at
 * least one of the two alarm types is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val preferencesManager = PreferencesManager(appContext)

                val setupCompleted = preferencesManager.setupCompletedFlow.first()
                if (!setupCompleted) return@launch

                val maxAlarmEnabled = preferencesManager.alarmEnabledFlow.first()
                val lowAlarmEnabled = preferencesManager.lowAlarmEnabledFlow.first()

                if (maxAlarmEnabled || lowAlarmEnabled) {
                    val serviceIntent = Intent(appContext, BatteryService::class.java)
                    ContextCompat.startForegroundService(appContext, serviceIntent)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to resume battery monitoring after boot.", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "VoltHaltBootReceiver"
    }
}
