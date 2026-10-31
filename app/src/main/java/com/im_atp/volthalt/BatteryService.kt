package com.im_atp.volthalt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BatteryService : Service() {

    companion object {
        const val ACTION_STOP_SERVICE             = "STOP_SERVICE"
        const val ACTION_STOP_ALARM               = "STOP_ALARM"
        const val ACTION_STOP_MAX_ALARM_FROM_TILE = "STOP_MAX_ALARM_FROM_TILE"
        const val ACTION_ALARM_STOPPED            = "com.im_atp.volthalt.ALARM_STOPPED"
        const val EXTRA_ALARM_TYPE                = "alarm_type"
        const val ALARM_TYPE_MAX                  = "max_battery"
        const val ALARM_TYPE_LOW                  = "low_battery"

        private const val SERVICE_CHANNEL_ID = "BatteryServiceChannel"
        private const val SERVICE_NOTIF_ID   = 1
        private const val ALARM_CHANNEL_ID   = "BatteryAlarmChannel"
        private const val ALARM_NOTIF_ID     = 2
        private const val TAG                 = "BatteryService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmPlayer: AlarmPlayer

    // Both flags are only ever touched on the main thread.
    private var isMaxAlarmPlaying = false
    private var isLowAlarmPlaying = false
    private var isMonitoring = false

    // In-memory preference cache used by the battery receiver.
    private var maxEnabled   = false
    private var maxTarget    = 80
    private var maxSoundType = "ringtone"
    private var maxTtsText   = ""
    private var lowEnabled   = false
    private var lowTarget    = 20
    private var lowSoundType = "ringtone"
    private var lowTtsText   = ""

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

            val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            if (level == -1 || scale == -1) return

            val pct        = (level * 100) / scale
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            checkBatteryLevel(pct, isCharging)
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
        alarmPlayer        = AlarmPlayer(applicationContext)
        createNotificationChannels()
        maxTtsText = getString(R.string.default_max_tts)
        lowTtsText = getString(R.string.default_low_tts)

        // Keep alarm flags and thresholds synchronized with DataStore.
        // Refresh the ongoing notification whenever either alarm setting changes.
        serviceScope.launch {
            combine(
                preferencesManager.alarmEnabledFlow,
                preferencesManager.targetPercentageFlow,
                preferencesManager.lowAlarmEnabledFlow,
                preferencesManager.lowTargetPercentageFlow
            ) { maxActive, maxLevel, lowActive, lowLevel ->
                maxEnabled = maxActive
                maxTarget = maxLevel
                lowEnabled = lowActive
                lowTarget = lowLevel
            }.collect {
                // Silence an alarm immediately if its corresponding switch is disabled.
                if (!maxEnabled && isMaxAlarmPlaying) stopMaxAlarm()
                if (!lowEnabled && isLowAlarmPlaying) stopLowAlarm()
                updateServiceNotification()
            }
        }

        // Cache sound preferences separately for alarm playback.
        serviceScope.launch { preferencesManager.maxSoundTypeFlow.collect { maxSoundType = it } }
        serviceScope.launch { preferencesManager.maxTtsTextFlow.collect   { maxTtsText = it } }
        serviceScope.launch { preferencesManager.lowSoundTypeFlow.collect  { lowSoundType = it } }
        serviceScope.launch { preferencesManager.lowTtsTextFlow.collect    { lowTtsText = it } }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            // User hit "Stop Monitoring" — disable both alarms and kill the service.
            ACTION_STOP_SERVICE -> {
                serviceScope.launch {
                    try {
                        // Do not destroy the service until both settings are saved.
                        preferencesManager.setAlarmEnabled(false)
                        preferencesManager.setLowAlarmEnabled(false)
                        stopMonitoringService(startId)
                    } catch (exception: Exception) {
                        Log.e(TAG, "Unable to save alarm settings while stopping monitoring.", exception)
                    }
                }
                // stopSelf(startId) prevents a successful stop from being restarted.
                // If saving fails, the still-running service retains sticky behavior.
                return START_STICKY
            }

            // Quick tile toggled the max-battery alarm off.
            // If the low-battery alarm is still active we keep the service running.
            ACTION_STOP_MAX_ALARM_FROM_TILE -> {
                // A tile action may reach a newly-created service instance. Promote it
                // immediately so the service meets Android's foreground-service deadline.
                if (!isMonitoring) {
                    isMonitoring = true
                    startForeground(SERVICE_NOTIF_ID, createServiceNotification())
                }

                serviceScope.launch {
                    if (isMaxAlarmPlaying) {
                        isMaxAlarmPlaying = false
                        if (!isLowAlarmPlaying) {
                            alarmPlayer.stop()
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            nm.cancel(ALARM_NOTIF_ID)
                        }
                        broadcastAlarmStopped()
                    }

                    // Query DataStore directly: the in-memory collector may still be
                    // processing the tile's most recent setting change.
                    val maxStillEnabled = preferencesManager.alarmEnabledFlow.first()
                    val lowStillEnabled = preferencesManager.lowAlarmEnabledFlow.first()

                    if (!maxStillEnabled && !lowStillEnabled) {
                        stopMonitoringService(startId)
                    } else {
                        updateServiceNotification()
                    }
                }
                // If low-battery monitoring remains enabled, keep sticky restart behavior.
                return START_STICKY
            }

            // Silence the alarm but keep the service monitoring.
            ACTION_STOP_ALARM -> {
                stopAllAlarms()
                // Silencing an alarm does not mean monitoring has been disabled.
                return START_STICKY
            }
        }

        // Normal start — begin foreground monitoring.
        isMonitoring = true
        startForeground(SERVICE_NOTIF_ID, createServiceNotification())
        return START_STICKY
    }

    // Evaluates the current battery state against stored thresholds.
    // Only reads in-memory fields on the battery broadcast path.
    private fun checkBatteryLevel(currentLevel: Int, isCharging: Boolean) {
        if (maxEnabled && isCharging && currentLevel >= maxTarget) {
            if (!isMaxAlarmPlaying) {
                isMaxAlarmPlaying = true
                serviceScope.launch(Dispatchers.IO) { startAlarm(ALARM_TYPE_MAX) }
            }
        } else if (isMaxAlarmPlaying) {
            stopMaxAlarm()
        }

        if (lowEnabled && !isCharging && currentLevel <= lowTarget) {
            if (!isLowAlarmPlaying) {
                isLowAlarmPlaying = true
                serviceScope.launch(Dispatchers.IO) { startAlarm(ALARM_TYPE_LOW) }
            }
        } else if (isLowAlarmPlaying) {
            stopLowAlarm()
        }
    }

    // Reads the remaining alarm preferences and hands off to AlarmPlayer.
    private suspend fun startAlarm(type: String) {
        if (type == ALARM_TYPE_MAX) {
            val vibration   = preferencesManager.vibrationEnabledFlow.first()
            val volume      = preferencesManager.alarmVolumeFlow.first()
            if (maxSoundType == "tts") {
                alarmPlayer.playTts(maxTtsText, vibration, volume)
            } else {
                val ringtoneUri = preferencesManager.ringtoneUriFlow.first()
                alarmPlayer.play(ringtoneUri, vibration, volume)
            }
        } else {
            val vibration   = preferencesManager.lowVibrationEnabledFlow.first()
            val volume      = preferencesManager.lowAlarmVolumeFlow.first()
            if (lowSoundType == "tts") {
                alarmPlayer.playTts(lowTtsText, vibration, volume)
            } else {
                val ringtoneUri = preferencesManager.lowRingtoneUriFlow.first()
                alarmPlayer.play(ringtoneUri, vibration, volume)
            }
        }
        showAlarmNotification(type)
    }

    private fun showAlarmNotification(type: String) {
        val alarmIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(EXTRA_ALARM_TYPE, type)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 3, alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this, 4,
            Intent(this, BatteryService::class.java).apply { action = ACTION_STOP_ALARM },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (type == ALARM_TYPE_MAX) getString(R.string.notification_max_battery_title) else getString(R.string.notification_low_battery_title)
        val body  = if (type == ALARM_TYPE_MAX)
            getString(R.string.notification_max_battery_message)
        else
            getString(R.string.notification_low_battery_message)

        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(0, getString(R.string.stop_alarm), stopPending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALARM_NOTIF_ID, notification)
    }

    private fun stopMaxAlarm() {
        if (!isMaxAlarmPlaying) return
        isMaxAlarmPlaying = false
        if (!isLowAlarmPlaying) {
            alarmPlayer.stop()
            cancelAlarmNotification()
        }
        broadcastAlarmStopped()
    }

    private fun stopLowAlarm() {
        if (!isLowAlarmPlaying) return
        isLowAlarmPlaying = false
        if (!isMaxAlarmPlaying) {
            alarmPlayer.stop()
            cancelAlarmNotification()
        }
        broadcastAlarmStopped()
    }

    fun stopAllAlarms() {
        isMaxAlarmPlaying = false
        isLowAlarmPlaying = false
        alarmPlayer.stop()
        cancelAlarmNotification()
        broadcastAlarmStopped()
    }

    private fun cancelAlarmNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALARM_NOTIF_ID)
        updateServiceNotification()
    }

    private fun broadcastAlarmStopped() {
        sendBroadcast(Intent(ACTION_ALARM_STOPPED))
    }

    /**
     * Stops the service after the relevant preference writes have completed.
     * Used by the Stop Monitoring action and when the tile turns off the last alarm.
     */
    private fun stopMonitoringService(startId: Int) {
        isMaxAlarmPlaying = false
        isLowAlarmPlaying = false
        alarmPlayer.stop()
        isMonitoring = false

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALARM_NOTIF_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastAlarmStopped()
        stopSelf(startId)
    }

    private fun createServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, BatteryService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = getString(
            R.string.alarm_status_format,
            maxTarget,
            getString(if (maxEnabled) R.string.alarm_status_enabled else R.string.alarm_status_disabled),
            lowTarget,
            getString(if (lowEnabled) R.string.alarm_status_enabled else R.string.alarm_status_disabled)
        )

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSubText(getString(R.string.monitoring_battery))
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_monitoring), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateServiceNotification() {
        if (!isMonitoring) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(SERVICE_NOTIF_ID, createServiceNotification())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID, getString(R.string.battery_monitoring_channel), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.battery_monitoring_channel_description)
            }

            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID, getString(R.string.battery_alarm_channel), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description          = getString(R.string.battery_alarm_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setBypassDnd(true)
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(alarmChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        alarmPlayer.stop()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
