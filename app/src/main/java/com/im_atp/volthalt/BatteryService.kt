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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmPlayer: AlarmPlayer

    // Both flags are only ever touched on the main thread (batteryReceiver +
    // onStartCommand both run there), so no synchronisation is needed.
    private var isMaxAlarmPlaying = false
    private var isLowAlarmPlaying = false

    // In-memory preference cache — kept in sync by lightweight collectors below.
    // Using cached values in the battery receiver avoids DataStore reads on
    // every battery broadcast, which can fire several times per minute.
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

        // Start collectors that keep the in-memory cache in sync with DataStore.
        // Each one is a single suspended collect() — negligible CPU, and DataStore
        // itself caches the file in memory after the first read.
        serviceScope.launch { preferencesManager.alarmEnabledFlow.collect       { maxEnabled   = it } }
        serviceScope.launch { preferencesManager.targetPercentageFlow.collect    { maxTarget    = it } }
        serviceScope.launch { preferencesManager.maxSoundTypeFlow.collect        { maxSoundType = it } }
        serviceScope.launch { preferencesManager.maxTtsTextFlow.collect          { maxTtsText   = it } }
        serviceScope.launch { preferencesManager.lowAlarmEnabledFlow.collect     { lowEnabled   = it } }
        serviceScope.launch { preferencesManager.lowTargetPercentageFlow.collect { lowTarget    = it } }
        serviceScope.launch { preferencesManager.lowSoundTypeFlow.collect        { lowSoundType = it } }
        serviceScope.launch { preferencesManager.lowTtsTextFlow.collect          { lowTtsText   = it } }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            // User hit "Stop Monitoring" — disable both alarms and kill the service.
            ACTION_STOP_SERVICE -> {
                serviceScope.launch {
                    preferencesManager.setAlarmEnabled(false)
                    preferencesManager.setLowAlarmEnabled(false)
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(ALARM_NOTIF_ID)
                alarmPlayer.stop()
                broadcastAlarmStopped()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            // Quick tile toggled the max-battery alarm off.
            // If the low-battery alarm is still active we keep the service running.
            ACTION_STOP_MAX_ALARM_FROM_TILE -> {
                if (isMaxAlarmPlaying) {
                    isMaxAlarmPlaying = false
                    if (!isLowAlarmPlaying) {
                        alarmPlayer.stop()
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(ALARM_NOTIF_ID)
                    }
                    broadcastAlarmStopped()
                }
                if (!lowEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            // Silence the alarm but keep the service monitoring.
            ACTION_STOP_ALARM -> {
                stopAllAlarms()
                return START_NOT_STICKY
            }
        }

        // Normal start — begin foreground monitoring.
        startForeground(SERVICE_NOTIF_ID, createServiceNotification(getString(R.string.monitoring_battery)))
        return START_STICKY
    }

    // Evaluates the current battery state against stored thresholds.
    // Only reads in-memory fields — no coroutines, no IO, no allocation on the hot path.
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

    // Reads the remaining alarm preferences (volume, vibration, ringtone) from DataStore
    // and hands off to AlarmPlayer. Runs on the IO dispatcher.
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
        nm.notify(SERVICE_NOTIF_ID, createServiceNotification(getString(R.string.monitoring_battery)))
    }

    private fun broadcastAlarmStopped() {
        sendBroadcast(Intent(ACTION_ALARM_STOPPED))
    }

    private fun createServiceNotification(contentText: String): Notification {
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
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_monitoring), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        alarmPlayer.stop()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
