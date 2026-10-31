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

/** Restarts battery monitoring after boot only when the master switch is on. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val preferencesManager = PreferencesManager(appContext)
                preferencesManager.initializeMonitoringEnabledIfNeeded()
                if (!preferencesManager.setupCompletedFlow.first()) return@launch

                if (preferencesManager.monitoringEnabledFlow.first()) {
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
