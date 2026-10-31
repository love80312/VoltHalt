package com.im_atp.volthalt.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark theme — pitch-black OLED background with a periwinkle blue accent.
private val PitchBlackColorScheme = darkColorScheme(
    primary            = Color(0xFFA5C0FF),
    onPrimary          = Color(0xFF0F172A),
    primaryContainer   = Color(0xFF2B334A),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary          = Color(0xFF4ADE80),
    onSecondary        = Color(0xFF102016),
    tertiary           = Color(0xFFFB923C),
    onTertiary         = Color(0xFF241307),
    background         = Color(0xFF000000),
    surface            = Color(0xFF1E2028),
    surfaceVariant     = Color(0xFF272932),
    onBackground       = Color(0xFFFFFFFF),
    onSurface          = Color(0xFFFFFFFF),
    onSurfaceVariant   = Color(0xFFC4C6D0),
    outline            = Color(0xFF33353F),
    outlineVariant     = Color(0xFF22242D)
)

// Light theme — clean off-white background with a rich royal blue accent.
private val ProfessionalLightColorScheme = lightColorScheme(
    primary            = Color(0xFF2563EB),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondary          = Color(0xFF15803D),
    onSecondary        = Color(0xFFFFFFFF),
    tertiary           = Color(0xFFC2410C),
    onTertiary         = Color(0xFFFFFFFF),
    background         = Color(0xFFFAFAFA),
    surface            = Color(0xFFFFFFFF),
    surfaceVariant     = Color(0xFFF1F5F9),
    onBackground       = Color(0xFF0F172A),
    onSurface          = Color(0xFF0F172A),
    onSurfaceVariant   = Color(0xFF64748B),
    outline            = Color(0xFFE2E8F0),
    outlineVariant     = Color(0xFFCBD5E1)
)

// Comfort theme — medium-bright warm neutral surfaces with muted green/orange accents.
private val ComfortColorScheme = lightColorScheme(
    primary            = Color(0xFF526B50),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFD5E0D1),
    onPrimaryContainer = Color(0xFF263B27),
    secondary          = Color(0xFF5D7958),
    onSecondary        = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE7D6),
    onSecondaryContainer = Color(0xFF263D25),
    tertiary           = Color(0xFFA96535),
    onTertiary         = Color(0xFFFFFFFF),
    tertiaryContainer  = Color(0xFFF1DDCB),
    onTertiaryContainer = Color(0xFF42230F),
    background         = Color(0xFFDFE1D6),
    surface            = Color(0xFFE9EAE0),
    surfaceVariant     = Color(0xFFD5D8CD),
    onBackground       = Color(0xFF30382F),
    onSurface          = Color(0xFF30382F),
    onSurfaceVariant   = Color(0xFF596254),
    outline            = Color(0xFFAEB4A6),
    outlineVariant     = Color(0xFFC7CCBF)
)

@Composable
fun VoltHaltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    comfortTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        comfortTheme -> ComfortColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> PitchBlackColorScheme
        else      -> ProfessionalLightColorScheme
    }

    // Comfort mode is a light surface even when the device itself is in dark mode.
    val useDarkSystemBars = darkTheme && !comfortTheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val backgroundColor = colorScheme.background.toArgb()
            window.statusBarColor = backgroundColor
            window.navigationBarColor = backgroundColor
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDarkSystemBars
            insetsController.isAppearanceLightNavigationBars = !useDarkSystemBars
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content     = content
    )
}
