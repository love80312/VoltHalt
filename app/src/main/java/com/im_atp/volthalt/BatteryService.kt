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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BatteryService : Service() {

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_STOP_ALARM = "STOP_ALARM"
        const val ACTION_ALARM_STOPPED = "com.im_atp.volthalt.ALARM_STOPPED"
        const val EXTRA_ALARM_TYPE = "alarm_type"
        const val ALARM_TYPE_MAX = "max_battery"
        const val ALARM_TYPE_LOW = "low_battery"

        private const val SERVICE_CHANNEL_ID = "BatteryServiceChannel"
        private const val SERVICE_NOTIF_ID = 1
        private const val ALARM_CHANNEL_ID = "BatteryAlarmChannel"
        private const val ALARM_NOTIF_ID = 2
        private const val TAG = "BatteryService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val monitoringCommandMutex = Mutex()
    private var maxAlarmStartJob: Job? = null
    private var lowAlarmStartJob: Job? = null
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmPlayer: AlarmPlayer

    // Both flags are only ever touched on the main thread.
    private var isMaxAlarmPlaying = false
    private var isLowAlarmPlaying = false
    private var isMonitoring = false

    // In-memory preference cache used by the battery receiver.
    private var maxEnabled = false
    private var maxTarget = 80
    private var lowEnabled = false
    private var lowTarget = 20

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            if (level == -1 || scale == -1) return

            val pct = (level * 100) / scale
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            checkBatteryLevel(pct, isCharging)
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
        alarmPlayer = AlarmPlayer(applicationContext)
        createNotificationChannels()

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

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            // The Quick Settings tile starts monitoring without changing either alarm setting.
            ACTION_START_MONITORING -> {
                isMonitoring = true
                startForeground(SERVICE_NOTIF_ID, createServiceNotification())

                serviceScope.launch {
                    monitoringCommandMutex.withLock {
                        try {
                            preferencesManager.setMonitoringEnabled(true)
                            isMonitoring = true
                            // Refresh after the setting is saved and the preference cache updates.
                            startForeground(SERVICE_NOTIF_ID, createServiceNotification())
                        } catch (exception: Exception) {
                            Log.e(TAG, "Unable to save monitoring state while starting.", exception)
                            stopMonitoringService(startId)
                        }
                    }
                }
                return START_STICKY
            }

            // The tile or the persistent notification turns monitoring off.
            // Alarm preferences are intentionally preserved.
            ACTION_STOP_SERVICE -> {
                // The stop action can be delivered to a newly-created service instance.
                // Enter foreground immediately before performing asynchronous DataStore work.
                if (!isMonitoring) {
                    isMonitoring = true
                    startForeground(SERVICE_NOTIF_ID, createServiceNotification())
                }

                serviceScope.launch {
                    monitoringCommandMutex.withLock {
                        try {
                            preferencesManager.setMonitoringEnabled(false)
                            stopMonitoringService(startId)
                        } catch (exception: Exception) {
                            Log.e(TAG, "Unable to save monitoring state while stopping.", exception)
                        }
                    }
                }
                return START_STICKY
            }

            // Silence the alarm but keep battery monitoring active.
            ACTION_STOP_ALARM -> {
                stopAllAlarms()
                return START_STICKY
            }
        }

        // Normal start or system sticky restart. Start foreground immediately to meet
        // Android's deadline, then verify that the saved master switch is still enabled.
        isMonitoring = true
        startForeground(SERVICE_NOTIF_ID, createServiceNotification())
        serviceScope.launch {
            try {
                if (!preferencesManager.monitoringEnabledFlow.first()) {
                    stopMonitoringService(startId)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to read monitoring state.", exception)
            }
        }
        return START_STICKY
    }

    // Evaluates the current battery state against stored thresholds.
    // Alarm startup and cancellation stay on the service's main-thread scope so
    // a stop request cannot race a background player.start() call.
    private fun checkBatteryLevel(currentLevel: Int, isCharging: Boolean) {
        if (maxEnabled && isCharging && currentLevel >= maxTarget) {
            if (!isMaxAlarmPlaying) {
                isMaxAlarmPlaying = true
                maxAlarmStartJob = serviceScope.launch {
                    startAlarm(ALARM_TYPE_MAX)
                }
            }
        } else if (isMaxAlarmPlaying) {
            stopMaxAlarm()
        }

        if (lowEnabled && !isCharging && currentLevel <= lowTarget) {
            if (!isLowAlarmPlaying) {
                isLowAlarmPlaying = true
                lowAlarmStartJob = serviceScope.launch {
                    startAlarm(ALARM_TYPE_LOW)
                }
            }
        } else if (isLowAlarmPlaying) {
            stopLowAlarm()
        }
    }

    // Read the latest preferences, re-check the alarm state, then start playback.
    // The final check and player call run without a suspension between them.
    private suspend fun startAlarm(type: String) {
        when (type) {
            ALARM_TYPE_MAX -> {
                if (!isMaxAlarmPlaying || !preferencesManager.alarmEnabledFlow.first()) {
                    return
                }

                val soundType = preferencesManager.maxSoundTypeFlow.first()
                val ttsText = preferencesManager.maxTtsTextFlow.first()
                val vibration = preferencesManager.vibrationEnabledFlow.first()
                val volume = preferencesManager.alarmVolumeFlow.first()
                val ringtoneUri = if (soundType == "tts") {
                    null
                } else {
                    preferencesManager.ringtoneUriFlow.first()
                }

                if (!isMaxAlarmPlaying || !preferencesManager.alarmEnabledFlow.first()) {
                    return
                }

                if (soundType == "tts") {
                    alarmPlayer.playTts(ttsText, vibration, volume)
                } else {
                    alarmPlayer.play(ringtoneUri, vibration, volume)
                }
            }

            ALARM_TYPE_LOW -> {
                if (!isLowAlarmPlaying || !preferencesManager.lowAlarmEnabledFlow.first()) {
                    return
                }

                val soundType = preferencesManager.lowSoundTypeFlow.first()
                val ttsText = preferencesManager.lowTtsTextFlow.first()
                val vibration = preferencesManager.lowVibrationEnabledFlow.first()
                val volume = preferencesManager.lowAlarmVolumeFlow.first()
                val ringtoneUri = if (soundType == "tts") {
                    null
                } else {
                    preferencesManager.lowRingtoneUriFlow.first()
                }

                if (!isLowAlarmPlaying || !preferencesManager.lowAlarmEnabledFlow.first()) {
                    return
                }

                if (soundType == "tts") {
                    alarmPlayer.playTts(ttsText, vibration, volume)
                } else {
                    alarmPlayer.play(ringtoneUri, vibration, volume)
                }
            }

            else -> return
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

        val title = if (type == ALARM_TYPE_MAX) {
            getString(R.string.notification_max_battery_title)
        } else {
            getString(R.string.notification_low_battery_title)
        }
        val body = if (type == ALARM_TYPE_MAX) {
            getString(R.string.notification_max_battery_message)
        } else {
            getString(R.string.notification_low_battery_message)
        }

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
        if (!isMaxAlarmPlaying && maxAlarmStartJob == null) return

        isMaxAlarmPlaying = false
        maxAlarmStartJob?.cancel()
        maxAlarmStartJob = null

        if (!isLowAlarmPlaying) {
            alarmPlayer.stop()
            cancelAlarmNotification()
        }
        broadcastAlarmStopped()
    }

    private fun stopLowAlarm() {
        if (!isLowAlarmPlaying && lowAlarmStartJob == null) return

        isLowAlarmPlaying = false
        lowAlarmStartJob?.cancel()
        lowAlarmStartJob = null

        if (!isMaxAlarmPlaying) {
            alarmPlayer.stop()
            cancelAlarmNotification()
        }
        broadcastAlarmStopped()
    }

    fun stopAllAlarms() {
        maxAlarmStartJob?.cancel()
        maxAlarmStartJob = null
        lowAlarmStartJob?.cancel()
        lowAlarmStartJob = null

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

    /** Stops monitoring and removes its foreground notification without changing alarm settings. */
    private fun stopMonitoringService(startId: Int) {
        maxAlarmStartJob?.cancel()
        maxAlarmStartJob = null
        lowAlarmStartJob?.cancel()
        lowAlarmStartJob = null

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
                description = getString(R.string.battery_alarm_channel_description)
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
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        maxAlarmStartJob?.cancel()
        maxAlarmStartJob = null
        lowAlarmStartJob?.cancel()
        lowAlarmStartJob = null
        alarmPlayer.stop()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
