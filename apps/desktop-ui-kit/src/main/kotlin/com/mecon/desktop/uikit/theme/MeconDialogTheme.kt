package com.mecon.desktop.uikit.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material3 fallback colors for desktop dialogs. Most Mecon dialog surfaces specify semantic roles
 * directly; this provider also catches menus, fields, ripples and buttons that rely on defaults so
 * they never fall back to Material's purple palette.
 */
@Composable
fun MeconDialogTheme(content: @Composable () -> Unit) {
    val common = if (MeconColors.themeMode == ThemeMode.DARK) {
        darkColorScheme(
            primary = MeconColors.Primary,
            onPrimary = MeconColors.White,
            primaryContainer = MeconColors.SelectedSurface,
            onPrimaryContainer = MeconColors.SelectedIconOnSurface,
            secondary = MeconColors.PrimaryLight,
            onSecondary = MeconColors.SurfaceDark,
            background = MeconColors.DialogBackground,
            onBackground = MeconColors.TextPrimary,
            surface = MeconColors.DialogPanel,
            onSurface = MeconColors.TextPrimary,
            surfaceVariant = MeconColors.DialogPanelInset,
            onSurfaceVariant = MeconColors.TextMuted,
            outline = MeconColors.Border,
            outlineVariant = MeconColors.BorderLight,
            error = MeconColors.Danger,
            onError = MeconColors.White,
            errorContainer = MeconColors.ErrorSurface,
            onErrorContainer = MeconColors.OnErrorSurface,
            scrim = MeconColors.SurfaceDark,
        )
    } else {
        lightColorScheme(
            primary = MeconColors.Primary,
            onPrimary = MeconColors.White,
            primaryContainer = MeconColors.SelectedSurface,
            onPrimaryContainer = MeconColors.SelectedIconOnSurface,
            secondary = MeconColors.PrimaryLight,
            onSecondary = MeconColors.SurfaceDark,
            background = MeconColors.DialogBackground,
            onBackground = MeconColors.TextPrimary,
            surface = MeconColors.DialogPanel,
            onSurface = MeconColors.TextPrimary,
            surfaceVariant = MeconColors.DialogPanelInset,
            onSurfaceVariant = MeconColors.TextMuted,
            outline = MeconColors.Border,
            outlineVariant = MeconColors.BorderLight,
            error = MeconColors.Danger,
            onError = MeconColors.White,
            errorContainer = MeconColors.ErrorSurface,
            onErrorContainer = MeconColors.OnErrorSurface,
            scrim = MeconColors.SurfaceDark,
        )
    }
    MaterialTheme(colorScheme = common, content = content)
}
