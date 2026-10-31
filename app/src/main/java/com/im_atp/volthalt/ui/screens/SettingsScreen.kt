package com.im_atp.volthalt.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.im_atp.volthalt.PreferencesManager
import com.im_atp.volthalt.R
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferencesManager: PreferencesManager,
    onNavigateBack: () -> Unit,
    onNavigateToSetup: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()

    // Max battery alarm state
    val maxTarget    by preferencesManager.targetPercentageFlow.collectAsState(initial = 80)
    val maxVibration by preferencesManager.vibrationEnabledFlow.collectAsState(initial = true)
    val maxRingtone  by preferencesManager.ringtoneUriFlow.collectAsState(initial = null)
    val maxVolume    by preferencesManager.alarmVolumeFlow.collectAsState(initial = 80)
    val maxSoundType by preferencesManager.maxSoundTypeFlow.collectAsState(initial = "ringtone")
    val maxTtsText   by preferencesManager.maxTtsTextFlow.collectAsState(initial = stringResource(R.string.default_max_tts))

    // Low battery alarm state
    val lowEnabled   by preferencesManager.lowAlarmEnabledFlow.collectAsState(initial = false)
    val lowTarget    by preferencesManager.lowTargetPercentageFlow.collectAsState(initial = 20)
    val lowVibration by preferencesManager.lowVibrationEnabledFlow.collectAsState(initial = true)
    val lowRingtone  by preferencesManager.lowRingtoneUriFlow.collectAsState(initial = null)
    val lowVolume    by preferencesManager.lowAlarmVolumeFlow.collectAsState(initial = 80)
    val lowSoundType by preferencesManager.lowSoundTypeFlow.collectAsState(initial = "ringtone")
    val lowTtsText   by preferencesManager.lowTtsTextFlow.collectAsState(initial = stringResource(R.string.default_low_tts))

    // Appearance state
    val themeMode by preferencesManager.themeModeFlow.collectAsState(initial = "system")

    val maxRingtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            coroutineScope.launch { preferencesManager.setRingtoneUri(uri?.toString() ?: "") }
        }
    }
    val lowRingtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            coroutineScope.launch { preferencesManager.setLowRingtoneUri(uri?.toString() ?: "") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp)
        ) {
            val isDark    = isSystemInDarkTheme()
            val maxAccent = if (isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
            val lowAccent = if (isDark) Color(0xFFFB923C) else Color(0xFFC2410C)

            // Max Battery Alarm section
            AlarmSectionHeader(
                title     = stringResource(R.string.max_battery_alarm),
                subtitle  = stringResource(R.string.max_alarm_settings_description),
                icon      = Icons.Default.BatteryChargingFull,
                iconColor = maxAccent
            )

            PercentageSliderRow(
                label       = stringResource(R.string.target_percentage),
                description = stringResource(R.string.charge_target_description),
                value       = maxTarget,
                range       = 10f..100f,
                steps       = 89,
                onValue     = { coroutineScope.launch { preferencesManager.setTargetPercentage(it) } }
            )
            SettingsDivider()
            AlarmSoundBlock(
                soundType          = maxSoundType,
                currentRingtoneUri = maxRingtone,
                ttsText            = maxTtsText,
                accentColor        = maxAccent,
                onSoundTypeChange  = { coroutineScope.launch { preferencesManager.setMaxSoundType(it) } },
                onPickRingtone     = { maxRingtoneLauncher.launch(buildRingtoneIntent(maxRingtone)) },
                onTtsTextChange    = { coroutineScope.launch { preferencesManager.setMaxTtsText(it) } }
            )
            SettingsDivider()
            VolumeSliderRow(
                value   = maxVolume,
                onValue = { coroutineScope.launch { preferencesManager.setAlarmVolume(it) } }
            )
            SettingsDivider()
            VibrationRow(
                checked   = maxVibration,
                onChanged = { coroutineScope.launch { preferencesManager.setVibrationEnabled(it) } }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Low Battery Alarm section
            AlarmSectionHeader(
                title     = stringResource(R.string.low_battery_alarm),
                subtitle  = stringResource(R.string.low_alarm_settings_description),
                icon      = Icons.Default.BatteryAlert,
                iconColor = lowAccent
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.enable_low_battery_alarm), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(
                        stringResource(R.string.enable_low_alarm_description),
                        fontSize = 13.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked         = lowEnabled,
                    onCheckedChange = { coroutineScope.launch { preferencesManager.setLowAlarmEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = lowAccent)
                )
            }
            SettingsDivider()
            PercentageSliderRow(
                label       = stringResource(R.string.trigger_percentage),
                description = stringResource(R.string.low_target_description),
                value       = lowTarget,
                range       = 5f..50f,
                steps       = 44,
                onValue     = { coroutineScope.launch { preferencesManager.setLowTargetPercentage(it) } },
                enabled     = lowEnabled
            )
            SettingsDivider()
            AlarmSoundBlock(
                soundType          = lowSoundType,
                currentRingtoneUri = lowRingtone,
                ttsText            = lowTtsText,
                accentColor        = lowAccent,
                enabled            = lowEnabled,
                onSoundTypeChange  = { if (lowEnabled) coroutineScope.launch { preferencesManager.setLowSoundType(it) } },
                onPickRingtone     = { if (lowEnabled) lowRingtoneLauncher.launch(buildRingtoneIntent(lowRingtone)) },
                onTtsTextChange    = { if (lowEnabled) coroutineScope.launch { preferencesManager.setLowTtsText(it) } }
            )
            SettingsDivider()
            VolumeSliderRow(
                value   = lowVolume,
                enabled = lowEnabled,
                onValue = { coroutineScope.launch { preferencesManager.setLowAlarmVolume(it) } }
            )
            SettingsDivider()
            VibrationRow(
                checked   = lowVibration,
                enabled   = lowEnabled,
                onChanged = { coroutineScope.launch { preferencesManager.setLowVibrationEnabled(it) } }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Appearance section
            AppearanceSectionHeader()
            AppearanceThemeRow(
                currentMode  = themeMode,
                onModeChange = { coroutineScope.launch { preferencesManager.setThemeMode(it) } }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Text-to-Speech section
            TtsSettingsRow()

            Spacer(modifier = Modifier.height(24.dp))

            // Setup & Permissions section
            SetupSectionHeader()
            SetupWizardRow(onNavigateToSetup = onNavigateToSetup)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupSectionHeader() {
    val isDark      = isSystemInDarkTheme()
    val accentColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = accentColor.copy(alpha = 0.14f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint     = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.setup_permissions), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = accentColor)
            Text(
                stringResource(R.string.setup_permissions_description),
                fontSize = 12.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetupWizardRow(onNavigateToSetup: () -> Unit) {
    val primaryColor      = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue   = if (isPressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "cardScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .scale(cardScale)
            .clip(RoundedCornerShape(16.dp)),
        color  = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.35f)),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = interactionSource, indication = null, onClick = onNavigateToSetup)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(primaryColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = primaryColor, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.rerun_setup_wizard),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.reconfigure_permissions),
                    fontSize   = 12.sp,
                    lineHeight = 17.sp,
                    color      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            Spacer(Modifier.width(10.dp))

            @Suppress("DEPRECATION")
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint     = primaryColor.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TtsSettingsRow() {
    val context     = LocalContext.current
    val accentColor = if (isSystemInDarkTheme()) Color(0xFF34D399) else Color(0xFF059669)

    // Try three intents in order to guarantee TTS settings open on any OEM ROM.
    fun openTtsSettings() {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // try next
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = accentColor.copy(alpha = 0.14f),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint     = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.text_to_speech), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = accentColor)
            Text(stringResource(R.string.voice_engine), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue   = if (isPressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "cardScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .scale(cardScale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(accentColor.copy(alpha = 0.12f), accentColor.copy(alpha = 0.04f))
                )
            ),
        color  = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        shape  = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = interactionSource, indication = null, onClick = { openTtsSettings() })
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = accentColor, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.configure_tts_voice),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.tts_voice_description),
                    fontSize   = 12.sp,
                    lineHeight = 17.sp,
                    color      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            Spacer(Modifier.width(10.dp))

            @Suppress("DEPRECATION")
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = stringResource(R.string.opens_android_settings),
                tint     = accentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AppearanceSectionHeader() {
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = primaryColor.copy(alpha = 0.14f),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Palette, contentDescription = null, tint = primaryColor, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.appearance), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = primaryColor)
            Text(stringResource(R.string.appearance_description), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceThemeRow(currentMode: String, onModeChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(stringResource(R.string.app_theme), fontWeight = FontWeight.Medium, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.theme_changes_immediately),
            fontSize = 12.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = currentMode == "system",
                onClick  = { onModeChange("system") },
                shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                icon = {
                    SegmentedButtonDefaults.Icon(active = currentMode == "system") {
                        Icon(Icons.Default.SettingsBrightness, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                    }
                }
            ) { Text(stringResource(R.string.theme_system)) }

            SegmentedButton(
                selected = currentMode == "light",
                onClick  = { onModeChange("light") },
                shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                icon = {
                    SegmentedButtonDefaults.Icon(active = currentMode == "light") {
                        Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                    }
                }
            ) { Text(stringResource(R.string.theme_light)) }

            SegmentedButton(
                selected = currentMode == "dark",
                onClick  = { onModeChange("dark") },
                shape    = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                icon = {
                    SegmentedButtonDefaults.Icon(active = currentMode == "dark") {
                        Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                    }
                }
            ) { Text(stringResource(R.string.theme_dark)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmSoundBlock(
    soundType: String,
    currentRingtoneUri: String?,
    ttsText: String,
    accentColor: Color,
    enabled: Boolean = true,
    onSoundTypeChange: (String) -> Unit,
    onPickRingtone: () -> Unit,
    onTtsTextChange: (String) -> Unit
) {
    val context = LocalContext.current
    val alpha   = if (enabled) 1f else 0.38f

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.alarm_sound),
            fontWeight = FontWeight.Medium,
            fontSize   = 15.sp,
            color      = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.alarm_sound_description),
            fontSize = 12.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )
        Spacer(Modifier.height(10.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = soundType == "ringtone",
                onClick  = { if (enabled) onSoundTypeChange("ringtone") },
                shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    SegmentedButtonDefaults.Icon(active = soundType == "ringtone") {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                    }
                }
            ) { Text(stringResource(R.string.ringtone)) }

            SegmentedButton(
                selected = soundType == "tts",
                onClick  = { if (enabled) onSoundTypeChange("tts") },
                shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    SegmentedButtonDefaults.Icon(active = soundType == "tts") {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                    }
                }
            ) { Text(stringResource(R.string.text_to_speech)) }
        }

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = soundType,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally(initialOffsetX = { 20 })) togetherWith
                (fadeOut(tween(180)) + slideOutHorizontally(targetOffsetX = { -20 }))
            },
            label = "soundTypeContent"
        ) { type ->
            when (type) {
                "tts" -> TtsTextRow(
                    ttsText      = ttsText,
                    accentColor  = accentColor,
                    enabled      = enabled,
                    onTextChange = onTtsTextChange,
                    context      = context
                )
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.selected_ringtone),
                                fontWeight = FontWeight.Medium,
                                fontSize   = 14.sp,
                                color      = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                            )
                            Text(
                                if (!currentRingtoneUri.isNullOrEmpty()) stringResource(R.string.custom_ringtone_selected) else stringResource(R.string.default_alarm_ringtone),
                                fontSize = 12.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                            )
                        }
                        OutlinedButton(
                            onClick        = onPickRingtone,
                            enabled        = enabled,
                            shape          = RoundedCornerShape(10.dp),
                            modifier       = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) { Text(stringResource(R.string.change), fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsTextRow(
    ttsText: String,
    accentColor: Color,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    context: android.content.Context
) {
    var draft     by remember(ttsText) { mutableStateOf(ttsText) }
    // Guard against the user tapping "Test Voice" multiple times rapidly.
    var isTesting by remember { mutableStateOf(false) }
    val alpha     = if (enabled) 1f else 0.38f

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.spoken_when_alarm_fires),
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )

        OutlinedTextField(
            value         = draft,
            onValueChange = { draft = it },
            modifier      = Modifier.fillMaxWidth(),
            enabled       = enabled,
            minLines      = 2,
            maxLines      = 4,
            placeholder   = { Text(stringResource(R.string.tts_placeholder)) },
            shape         = RoundedCornerShape(12.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor,
                cursorColor        = accentColor
            )
        )

        // Save button — only visible when the draft differs from what's stored.
        AnimatedVisibility(
            visible = draft != ttsText && draft.isNotBlank(),
            enter   = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(220)),
            exit    = shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(180))
        ) {
            OutlinedButton(
                onClick  = { onTextChange(draft.trim()) },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
                border   = androidx.compose.foundation.BorderStroke(1.dp, accentColor)
            ) {
                Text(stringResource(R.string.save_message), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Test Voice — speaks the draft once via the media stream.
        // isTesting prevents stacking multiple TTS instances if the button is tapped quickly.
        Button(
            onClick = {
                if (enabled && draft.isNotBlank() && !isTesting) {
                    isTesting = true
                    var testTts: TextToSpeech? = null
                    testTts = TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            testTts?.setLanguage(Locale.getDefault())
                            testTts?.setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            testTts?.setOnUtteranceProgressListener(
                                object : android.speech.tts.UtteranceProgressListener() {
                                    override fun onStart(id: String?) {}
                                    override fun onDone(id: String?) {
                                        testTts?.shutdown()
                                        isTesting = false
                                    }
                                    @Deprecated("Deprecated in API 21")
                                    override fun onError(id: String?) {
                                        testTts?.shutdown()
                                        isTesting = false
                                    }
                                }
                            )
                            testTts?.speak(draft, TextToSpeech.QUEUE_FLUSH, null, "test_voice")
                        } else {
                            isTesting = false
                        }
                    }
                }
            },
            enabled  = enabled && draft.isNotBlank() && !isTesting,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = accentColor.copy(alpha = 0.85f),
                contentColor   = Color.White
            )
        ) {
            @Suppress("DEPRECATION")
            Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isTesting) stringResource(R.string.playing) else stringResource(R.string.test_voice), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AlarmSectionHeader(title: String, subtitle: String, icon: ImageVector, iconColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = iconColor.copy(alpha = 0.14f),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = iconColor)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(horizontal = 20.dp),
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun PercentageSliderRow(
    label: String,
    description: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Int) -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.38f
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Text(description, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
            Text("$value%", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier.padding(start = 12.dp))
        }
        Slider(
            value         = value.toFloat(),
            onValueChange = { if (enabled) onValue(it.toInt()) },
            valueRange    = range,
            steps         = steps,
            enabled       = enabled,
            modifier      = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun VolumeSliderRow(value: Int, onValue: (Int) -> Unit, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.38f
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.alarm_volume), fontWeight = FontWeight.Medium, fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        Text(stringResource(R.string.independent_system_volume), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            @Suppress("DEPRECATION")
            Icon(Icons.Default.VolumeDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(20.dp))
            Slider(
                value         = value.toFloat(),
                onValueChange = { if (enabled) onValue(it.toInt()) },
                // 99 steps gives 100 distinct values: 0, 1, 2, … 100
                valueRange = 0f..100f,
                steps      = 99,
                enabled    = enabled,
                modifier   = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            @Suppress("DEPRECATION")
            Icon(Icons.Default.VolumeUp, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                modifier = Modifier.size(20.dp))
            Text("$value%", fontWeight = FontWeight.Bold,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun VibrationRow(checked: Boolean, onChanged: (Boolean) -> Unit, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.vibration), fontWeight = FontWeight.Medium, fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(stringResource(R.string.vibrate_when_alarm_triggers), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Switch(
            checked         = checked,
            onCheckedChange = { if (enabled) onChanged(it) },
            enabled         = enabled
        )
    }
}

private fun buildRingtoneIntent(existingUri: String?) =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        if (!existingUri.isNullOrEmpty()) {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(existingUri))
        }
    }
