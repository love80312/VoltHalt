package com.im_atp.volthalt

import android.Manifest
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.im_atp.volthalt.ui.screens.MainScreen
import com.im_atp.volthalt.ui.screens.SettingsScreen
import com.im_atp.volthalt.ui.screens.SetupScreen
import com.im_atp.volthalt.ui.theme.VoltHaltTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager

    // Launched when the user responds to the notification permission dialog.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        lifecycleScope.launch {
            val setupDone = preferencesManager.setupCompletedFlow.first()
            if (setupDone) {
                checkBatteryOptimization()
                if (isGranted) startBatteryService()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(applicationContext)

        setContent {
            // Read theme preference reactively — changes in Settings apply immediately.
            val themeMode by preferencesManager.themeModeFlow.collectAsState(initial = "system")
            val systemDark = isSystemInDarkTheme()
            val useComfort = themeMode == "comfort"
            val useDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                "comfort" -> false
                else -> systemDark
            }

            // Dynamic color is intentionally disabled to keep the curated VoltHalt palette.
            VoltHaltTheme(
                darkTheme = useDark,
                comfortTheme = useComfort,
                dynamicColor = false
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Determine the start destination asynchronously so we never block the
                    // main thread with runBlocking. A null value means we are still loading.
                    var startDestination by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        preferencesManager.initializeMonitoringEnabledIfNeeded()
                        val setupDone = preferencesManager.setupCompletedFlow.first()
                        if (setupDone) checkPermissionsAndStartService()
                        startDestination = if (setupDone) "main" else "setup"
                    }

                    if (startDestination == null) {
                        // Show a centered spinner while DataStore loads (typically < 100 ms).
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        BatteryAlarmApp(
                            preferencesManager = preferencesManager,
                            startDestination = startDestination!!,
                            onRequestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onRequestBattery = { checkBatteryOptimization() },
                            onRequestTile = { promptAddQuickTile() },
                            onRequestFullScreenIntent = { promptFullScreenIntent() }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                checkBatteryOptimization()
                startBatteryService()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            checkBatteryOptimization()
            startBatteryService()
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun promptAddQuickTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, AlarmTileService::class.java),
                getString(R.string.app_name),
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_icon),
                mainExecutor,
                { /* result callback — nothing to do here */ }
            )
        }
    }

    private fun promptFullScreenIntent() {
        // Android 14+ requires the user to explicitly grant USE_FULL_SCREEN_INTENT.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun startBatteryService() {
        lifecycleScope.launch {
            preferencesManager.initializeMonitoringEnabledIfNeeded()
            // The app must not restart monitoring when the tile's master switch is off.
            if (preferencesManager.monitoringEnabledFlow.first()) {
                val intent = Intent(this@MainActivity, BatteryService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
    }
}

@Composable
fun BatteryAlarmApp(
    preferencesManager: PreferencesManager,
    startDestination: String,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestTile: () -> Unit,
    onRequestFullScreenIntent: () -> Unit = {}
) {
    val navController = rememberNavController()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(200))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        }
    ) {
        composable("setup") {
            SetupScreen(
                onFinish = {
                    coroutineScope.launch {
                        preferencesManager.setSetupCompleted(true)
                        navController.navigate("main") {
                            popUpTo("setup") { inclusive = true }
                        }
                    }
                },
                onRequestNotifications = onRequestNotifications,
                onRequestBattery = onRequestBattery,
                onRequestTile = onRequestTile,
                onRequestFullScreenIntent = onRequestFullScreenIntent
            )
        }
        composable("main") {
            MainScreen(
                preferencesManager = preferencesManager,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToSetup = { navController.navigate("setup") }
            )
        }
        composable("settings") {
            SettingsScreen(
                preferencesManager = preferencesManager,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSetup = { navController.navigate("setup") }
            )
        }
    }
}
