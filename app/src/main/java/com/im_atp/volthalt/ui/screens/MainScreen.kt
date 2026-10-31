package com.im_atp.volthalt.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.im_atp.volthalt.AlarmPlayer
import com.im_atp.volthalt.BatteryService
import com.im_atp.volthalt.PreferencesManager
import com.im_atp.volthalt.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Subscribes to ACTION_BATTERY_CHANGED (a sticky broadcast) and returns the
// current battery level and charging state, updated in real time.
@Composable
private fun rememberBatteryState(): Pair<Int, Boolean> {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(0) }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val lvl    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                level      = (lvl * 100) / scale
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    return Pair(level, isCharging)
}

// Copies the installed APK into the external cache dir and fires an ACTION_SEND
// chooser so the user can share it with any app that accepts APK files.
private suspend fun shareApk(context: Context) = withContext(Dispatchers.IO) {
    try {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val destDir   = File(context.externalCacheDir ?: context.cacheDir, "share").apply { mkdirs() }
        val destApk   = File(destDir, "${context.getString(R.string.app_name)}.apk")

        sourceApk.copyTo(destApk, overwrite = true)

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destApk
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, apkUri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(
            Intent.createChooser(shareIntent, context.getString(R.string.share_apk_chooser, context.getString(R.string.app_name)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private enum class PreviewAlarmType { MAX, LOW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    preferencesManager: PreferencesManager,
    onNavigateToSettings: () -> Unit,
    onNavigateToSetup: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val context        = LocalContext.current

    // isIgnoringBatteryOptimizations makes a binder call, so we memoize the
    // result and only re-check it when the context changes.
    val isBatteryOptimizationDisabled = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    val maxEnabled by preferencesManager.alarmEnabledFlow.collectAsState(initial = false)
    val maxTarget  by preferencesManager.targetPercentageFlow.collectAsState(initial = 80)
    val lowEnabled by preferencesManager.lowAlarmEnabledFlow.collectAsState(initial = false)
    val lowTarget  by preferencesManager.lowTargetPercentageFlow.collectAsState(initial = 20)

    val (batteryLevel, isCharging) = rememberBatteryState()

    var showPreviewSheet by remember { mutableStateOf(false) }
    var previewingType   by remember { mutableStateOf<PreviewAlarmType?>(null) }
    val previewPlayer    = remember { AlarmPlayer(context) }

    DisposableEffect(Unit) {
        onDispose { previewPlayer.stop() }
    }

    fun startPreview(type: PreviewAlarmType) {
        previewingType = type
        coroutineScope.launch(Dispatchers.IO) {
            if (type == PreviewAlarmType.MAX) {
                val soundType   = preferencesManager.maxSoundTypeFlow.first()
                val vibration   = preferencesManager.vibrationEnabledFlow.first()
                val volume      = preferencesManager.alarmVolumeFlow.first()
                if (soundType == "tts") {
                    previewPlayer.playTts(preferencesManager.maxTtsTextFlow.first(), vibration, volume)
                } else {
                    previewPlayer.play(preferencesManager.ringtoneUriFlow.first(), vibration, volume)
                }
            } else {
                val soundType   = preferencesManager.lowSoundTypeFlow.first()
                val vibration   = preferencesManager.lowVibrationEnabledFlow.first()
                val volume      = preferencesManager.lowAlarmVolumeFlow.first()
                if (soundType == "tts") {
                    previewPlayer.playTts(preferencesManager.lowTtsTextFlow.first(), vibration, volume)
                } else {
                    previewPlayer.play(preferencesManager.lowRingtoneUriFlow.first(), vibration, volume)
                }
            }

            delay(5000L)
            previewPlayer.stop()
            withContext(Dispatchers.Main) {
                previewingType   = null
                showPreviewSheet = false
            }
        }
    }

    fun stopPreview() {
        previewPlayer.stop()
        previewingType   = null
        showPreviewSheet = false
    }

    if (showPreviewSheet) {
        ModalBottomSheet(
            onDismissRequest = { stopPreview() },
            sheetState    = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            shape         = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            PreviewAlarmSheetContent(
                previewingType = previewingType,
                onPreviewMax   = { startPreview(PreviewAlarmType.MAX) },
                onPreviewLow   = { startPreview(PreviewAlarmType.LOW) },
                onStop         = { stopPreview() }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text       = stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 22.sp
                    )
                },
                actions = {
                    IconButton(onClick = { coroutineScope.launch { shareApk(context) } }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_apk),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Warn the user if battery optimization is still enabled, since that
            // can delay or kill background alarms on aggressive OEM ROMs.
            AnimatedVisibility(visible = !isBatteryOptimizationDisabled) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(16.dp),
                    color    = MaterialTheme.colorScheme.surface,
                    border   = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color(0xFFFB923C).copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFFB923C).copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.BatteryAlert,
                                contentDescription = null,
                                tint     = Color(0xFFFB923C),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.optimization_recommended),
                                fontWeight = FontWeight.SemiBold,
                                fontSize   = 14.sp,
                                color      = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.optimization_permissions_description),
                                fontSize   = 12.sp,
                                lineHeight = 16.sp,
                                color      = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        TextButton(
                            onClick        = onNavigateToSetup,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.fix), fontWeight = FontWeight.Bold, color = Color(0xFFFB923C))
                        }
                    }
                }
            }

            PreviewAlarmButton(
                isPlaying = previewingType != null,
                onClick   = { showPreviewSheet = true }
            )

            BatteryStatusCard(level = batteryLevel, isCharging = isCharging)

            Text(
                text     = stringResource(R.string.alarms),
                style    = MaterialTheme.typography.labelLarge,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp)
            )

            val isDark    = isSystemInDarkTheme()
            val maxAccent = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
            val lowAccent = if (isDark) Color(0xFFFB923C) else Color(0xFFC2410C)

            AlarmCard(
                title          = stringResource(R.string.max_battery_alarm),
                description    = stringResource(R.string.max_battery_alarm_description),
                icon           = Icons.Default.BatteryChargingFull,
                thresholdLabel = stringResource(R.string.threshold_label, maxTarget),
                isEnabled      = maxEnabled,
                accentColor    = maxAccent,
                onToggle       = { newState ->
                    coroutineScope.launch {
                        preferencesManager.setAlarmEnabled(newState)
                        if (newState) {
                            val intent = Intent(context, BatteryService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                context.startForegroundService(intent)
                            else
                                context.startService(intent)
                        }
                    }
                },
                onClick = onNavigateToSettings
            )

            AlarmCard(
                title          = stringResource(R.string.low_battery_alarm),
                description    = stringResource(R.string.low_battery_alarm_description),
                icon           = Icons.Default.BatteryAlert,
                thresholdLabel = stringResource(R.string.threshold_label, lowTarget),
                isEnabled      = lowEnabled,
                accentColor    = lowAccent,
                onToggle       = { newState ->
                    coroutineScope.launch {
                        preferencesManager.setLowAlarmEnabled(newState)
                        if (newState) {
                            val intent = Intent(context, BatteryService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                context.startForegroundService(intent)
                            else
                                context.startService(intent)
                        }
                    }
                },
                onClick = onNavigateToSettings
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick  = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onBackground
                )
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.configure_alarm_settings), fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PreviewAlarmButton(isPlaying: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "previewPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.018f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "previewScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue  = 0.45f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val targetScale = when {
        isPressed -> 0.98f
        isPlaying -> pulseScale
        else      -> 1f
    }
    val animatedScale by animateFloatAsState(
        targetValue   = targetScale,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "buttonScale"
    )

    val accentColor     = MaterialTheme.colorScheme.primary
    val buttonGradient  = if (isPlaying) {
        Brush.linearGradient(listOf(Color(0xFF4F46E5).copy(alpha = 0.85f), Color(0xFF7C3AED).copy(alpha = 0.85f)))
    } else {
        Brush.linearGradient(listOf(accentColor.copy(alpha = 0.18f), Color(0xFFA78BFA).copy(alpha = 0.12f)))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animatedScale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border    = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPlaying) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(buttonGradient)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.fillMaxWidth()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(Color.White.copy(alpha = glowAlpha), CircleShape)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isPlaying) Color.White.copy(alpha = 0.25f)
                                else accentColor.copy(alpha = 0.18f),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint     = if (isPlaying) Color.White else accentColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = if (isPlaying) stringResource(R.string.preview_playing) else stringResource(R.string.preview_alarm),
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 16.sp,
                        color      = if (isPlaying) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text  = if (isPlaying) stringResource(R.string.tap_to_stop_preview) else stringResource(R.string.test_alarm_sound),
                        fontSize = 12.sp,
                        color = if (isPlaying)
                            Color.White.copy(alpha = 0.75f)
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }

                Spacer(Modifier.width(8.dp))

                AnimatedVisibility(
                    visible = isPlaying,
                    enter   = fadeIn() + scaleIn(),
                    exit    = fadeOut() + scaleOut()
                ) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(22.dp),
                        color       = Color.White,
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewAlarmSheetContent(
    previewingType: PreviewAlarmType?,
    onPreviewMax: () -> Unit,
    onPreviewLow: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text       = stringResource(R.string.preview_alarm),
            fontWeight = FontWeight.Bold,
            fontSize   = 20.sp,
            color      = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text       = stringResource(R.string.choose_alarm_preview) + "\n" + stringResource(R.string.preview_instructions),
            fontSize   = 13.sp,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
            modifier   = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        PreviewOptionCard(
            title       = stringResource(R.string.max_battery_alarm),
            description = stringResource(R.string.preview_charge_description),
            icon        = Icons.Default.BatteryChargingFull,
            accentColor = Color(0xFF4ADE80),
            isActive    = previewingType == PreviewAlarmType.MAX,
            onClick     = { if (previewingType == null) onPreviewMax() }
        )

        Spacer(Modifier.height(12.dp))

        PreviewOptionCard(
            title       = stringResource(R.string.low_battery_alarm),
            description = stringResource(R.string.preview_low_description),
            icon        = Icons.Default.BatteryAlert,
            accentColor = Color(0xFFFB923C),
            isActive    = previewingType == PreviewAlarmType.LOW,
            onClick     = { if (previewingType == null) onPreviewLow() }
        )

        AnimatedVisibility(
            visible = previewingType != null,
            enter   = fadeIn(tween(200)) + scaleIn(tween(200)),
            exit    = fadeOut(tween(200)) + scaleOut(tween(200))
        ) {
            Column {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick  = onStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(16.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor   = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.stop_preview), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun PreviewOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "optionPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (isActive) 1.01f else 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "optionScale"
    )
    val animatedScale by animateFloatAsState(
        targetValue   = if (isPressed) 0.985f else pulseScale,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "optionScale"
    )
    val bgAlpha by animateFloatAsState(
        targetValue   = if (isActive) 0.16f else 0.06f,
        animationSpec = tween(300),
        label         = "bgAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animatedScale)
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                enabled           = !isActive,
                onClick           = onClick
            ),
        shape  = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) accentColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accentColor.copy(alpha = bgAlpha))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(accentColor.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            Spacer(Modifier.width(8.dp))

            if (isActive) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    color       = accentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    tint     = accentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun BatteryStatusCard(level: Int, isCharging: Boolean) {
    val isDark       = isSystemInDarkTheme()
    val batteryColor = when {
        level >= 70 -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
        level >= 30 -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
        else        -> if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    }

    val gradientBrush = Brush.linearGradient(
        colors = listOf(batteryColor.copy(alpha = 0.15f), batteryColor.copy(alpha = 0.04f))
    )

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, batteryColor.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(batteryColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                        contentDescription = null,
                        tint     = batteryColor,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(Modifier.width(20.dp))

                Column {
                    Text(
                        text       = "$level%",
                        fontSize   = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color      = batteryColor
                    )
                    Text(
                        text     = if (isCharging) stringResource(R.string.charging) else stringResource(R.string.not_charging),
                        fontSize = 14.sp,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(Modifier.weight(1f))

                BatteryBar(level = level, color = batteryColor)
            }
        }
    }
}

@Composable
private fun BatteryBar(level: Int, color: Color) {
    val segments       = 5
    val filledSegments = ((level / 100f) * segments).toInt().coerceIn(0, segments)
    Column(
        verticalArrangement  = Arrangement.spacedBy(4.dp),
        horizontalAlignment  = Alignment.CenterHorizontally
    ) {
        repeat(segments) { index ->
            val filled = (segments - 1 - index) < filledSegments
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (filled) color else color.copy(alpha = 0.15f))
            )
        }
    }
}

@Composable
private fun AlarmCard(
    title: String,
    description: String,
    icon: ImageVector,
    thresholdLabel: String,
    isEnabled: Boolean,
    accentColor: Color,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedScale by animateFloatAsState(
        targetValue   = if (isPressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "cardScale"
    )

    val animatedBg by animateColorAsState(
        targetValue   = if (isEnabled) accentColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label         = "cardBg"
    )
    val animatedAccent by animateColorAsState(
        targetValue   = if (isEnabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label         = "accent"
    )

    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .scale(animatedScale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape     = RoundedCornerShape(20.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border    = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isEnabled) animatedAccent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(animatedBg)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(animatedAccent.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint     = animatedAccent,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 16.sp,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = description,
                    fontSize = 12.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(animatedAccent.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text       = thresholdLabel,
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color      = animatedAccent
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Switch(
                checked        = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = Color.White,
                    checkedTrackColor   = accentColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
