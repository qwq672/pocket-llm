package com.pocketllm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.content.ContextWrapper

private val LightColors = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    tertiary = md_light_tertiary,
    background = md_light_background,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    outline = md_light_outline,
    surfaceVariant = md_light_surfaceVariant,
)

private val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    tertiary = md_dark_tertiary,
    background = md_dark_background,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    outline = md_dark_outline,
    surfaceVariant = md_dark_surfaceVariant,
)

@Composable
fun PocketLLMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,   // 默认跟随系统壁纸取色（Material You）
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // view.context 不一定是 Activity（可能是 ContextThemeWrapper 等包装类），
            // 直接 as Activity 在某些场景会抛 ClassCastException，导致启动闪退。
            var ctx = view.context
            while (ctx is ContextWrapper) {
                if (ctx is android.app.Activity) break
                ctx = ctx.baseContext
            }
            val activity = ctx as? android.app.Activity ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PocketTypography,
        content = content
    )
}
