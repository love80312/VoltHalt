package com.im_atp.volthalt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im_atp.volthalt.ui.theme.VoltHaltTheme

class AlarmActivity : ComponentActivity() {

    private var wakeLock: PowerManager.WakeLock? = null

    // Which alarm type this screen is currently showing.
    private var currentAlarmType = BatteryService.ALARM_TYPE_MAX

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes
    }

    // BatteryService sends this when the alarm has been stopped (e.g. via the
    // notification "Stop Alarm" action), so we can dismiss ourselves.
    private val alarmStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BatteryService.ACTION_ALARM_STOPPED) finish()
        }
    }

    // For max-battery alarms we watch for the charger being unplugged.
    // When that happens the alarm condition is resolved and we dismiss.
    private val batteryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            if (currentAlarmType != BatteryService.ALARM_TYPE_MAX) return

            val status     = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            if (!isCharging) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make sure the screen turns on and the alarm shows over the lock screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON   or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Wake lock keeps the CPU running while the alarm screen is visible.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoltHalt::AlarmWakeLock"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        // ACTION_BATTERY_CHANGED is sticky, so registering immediately delivers
        // the current state without waiting for the next broadcast.
        registerReceiver(batteryStateReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val alarmFilter = IntentFilter(BatteryService.ACTION_ALARM_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmStoppedReceiver, alarmFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmStoppedReceiver, alarmFilter)
        }

        val alarmType = intent?.getStringExtra(BatteryService.EXTRA_ALARM_TYPE)
            ?: BatteryService.ALARM_TYPE_MAX

        showAlarmUi(alarmType)
    }

    // Called when the activity is already running and a new alarm intent arrives.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val alarmType = intent?.getStringExtra(BatteryService.EXTRA_ALARM_TYPE)
            ?: BatteryService.ALARM_TYPE_MAX
        showAlarmUi(alarmType)
    }

    private fun showAlarmUi(alarmType: String) {
        currentAlarmType = alarmType
        setContent {
            VoltHaltTheme(darkTheme = true) {
                AlarmScreen(
                    alarmType = alarmType,
                    onStopAlarm = {
                        stopAlarmInService()
                        finish()
                    }
                )
            }
        }
    }

    private fun stopAlarmInService() {
        startService(
            Intent(this, BatteryService::class.java).apply {
                action = BatteryService.ACTION_STOP_ALARM
            }
        )
    }

    // Prevent the back button from dismissing the alarm — the user must press Stop.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* intentionally empty */ }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(alarmStoppedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryStateReceiver) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

@Composable
private fun AlarmScreen(
    alarmType: String,
    onStopAlarm: () -> Unit
) {
    val isMaxBattery = alarmType == BatteryService.ALARM_TYPE_MAX

    val backgroundGradient = if (isMaxBattery) {
        Brush.radialGradient(colors = listOf(Color(0xFF1A3A1A), Color(0xFF0D1F0D), Color(0xFF050E05)))
    } else {
        Brush.radialGradient(colors = listOf(Color(0xFF3A1A1A), Color(0xFF1F0D0D), Color(0xFF0E0505)))
    }

    val accentColor = if (isMaxBattery) Color(0xFF4ADE80) else Color(0xFFFB923C)
    val title       = if (isMaxBattery) stringResource(R.string.max_battery_reached) else stringResource(R.string.low_battery_warning)
    val subtitle    = if (isMaxBattery)
        stringResource(R.string.max_battery_alarm_message)
    else
        stringResource(R.string.low_battery_alarm_message)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue  = 1.12f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue  = 0.5f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Box(contentAlignment = Alignment.Center) {
                // Pulsing outer glow ring
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(scale)
                        .background(accentColor.copy(alpha = ringAlpha), CircleShape)
                )
                // Icon container
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .background(accentColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isMaxBattery) Icons.Default.BatteryFull else Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint     = accentColor,
                        modifier = Modifier.size(56.dp).scale(scale)
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text       = title,
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                textAlign  = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text       = subtitle,
                fontSize   = 15.sp,
                color      = Color.White.copy(alpha = 0.7f),
                textAlign  = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick  = onStopAlarm,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape    = RoundedCornerShape(20.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor   = Color(0xFF050505)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
            ) {
                Text(
                    text          = stringResource(R.string.stop_alarm_uppercase),
                    fontSize      = 18.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
