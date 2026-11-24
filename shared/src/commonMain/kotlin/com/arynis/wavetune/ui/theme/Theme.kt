package com.arynis.wavetune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.arynis.wavetune.data.local.AccentMode
import com.arynis.wavetune.data.local.ThemeMode
import com.arynis.wavetune.data.repository.ThemeUiState
import com.arynis.wavetune.shared.platform.ApplySystemBars
import com.arynis.wavetune.shared.platform.rememberSystemDynamicScheme

/** Shared composition local indicating whether the active theme is dark mode. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Shared composition local indicating whether navigation tabs are hidden. */
val LocalHideNavTabs = staticCompositionLocalOf { false }

/**
 * Liquid-glass material switch, shared so both platforms' chrome agrees.
 * Android keeps the Backdrop-backed rendering (ui/theme/LiquidGlass.kt in
 * :app); desktop reads the same token for its tonal surfaces.
 */
val LocalLiquidGlass = staticCompositionLocalOf { false }

/** Material 3 (rather than glass) navigation bar switch. */
val LocalMd3NavBar = staticCompositionLocalOf { false }

/**
 * The single WaveTune theme used by Android and desktop alike.
 *
 * Android's existing Kyant0 Backdrop sources still wrap this at the app root;
 * the theme only decides schemes, typography, shapes and the shared
 * composition locals.
 */
@Composable
fun WaveTuneTheme(
    themeState: ThemeUiState,
    content: @Composable () -> Unit,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeState.themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    // Dynamic accent (Android 12+ wallpaper palette) only applies when nothing
    // more specific is active: now-playing artwork and the WaveTune signature
    // theme both win over the wallpaper.
    val useSystemDynamic = themeState.mode == AccentMode.DYNAMIC &&
        !themeState.isNowPlayingThemed &&
        !themeState.isWaveTuneTheme
    val systemDynamic = if (useSystemDynamic) rememberSystemDynamicScheme(isDark, themeState.amoled) else null

    val activeColorScheme = when {
        themeState.isWaveTuneTheme && isDark -> themeState.darkColorScheme
        themeState.isWaveTuneTheme && !isDark -> themeState.lightColorScheme
        systemDynamic != null -> systemDynamic
        isDark -> themeState.darkColorScheme
        else -> themeState.lightColorScheme
    }

    ApplySystemBars(isDark)

    MaterialTheme(
        colorScheme = activeColorScheme,
        typography = rememberWaveTuneTypography(
            customFont = themeState.useCustomFont,
            boldFont = themeState.useBoldFont,
        ),
        shapes = WaveTuneShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            CompositionLocalProvider(
                LocalLiquidGlass provides themeState.liquidGlass,
                LocalMd3NavBar provides themeState.md3NavBar,
                LocalHideNavTabs provides themeState.hideNavTabs,
                LocalIsDarkTheme provides isDark,
            ) {
                content()
            }
        }
    }
}
