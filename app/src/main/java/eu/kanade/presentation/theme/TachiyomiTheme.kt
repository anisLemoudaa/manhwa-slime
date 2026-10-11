package eu.kanade.presentation.theme

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.CatppuccinColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.LavenderColorScheme
import eu.kanade.presentation.theme.colorscheme.MidnightDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.MonochromeColorScheme
import eu.kanade.presentation.theme.colorscheme.NordColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import eu.kanade.presentation.theme.colorscheme.TakoColorScheme
import eu.kanade.presentation.theme.colorscheme.TealTurqoiseColorScheme
import eu.kanade.presentation.theme.colorscheme.TidalWaveColorScheme
import eu.kanade.presentation.theme.colorscheme.TokyoNightColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import eu.kanade.presentation.theme.colorscheme.YotsubaColorScheme
import eu.kanade.tachiyomi.mslime.MslDisplayFont
import eu.kanade.tachiyomi.mslime.MslUiFont
import eu.kanade.tachiyomi.mslime.withFonts
import mihon.app.di.appGraph

@Composable
fun TachiyomiTheme(
    appTheme: AppTheme? = null,
    amoled: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val uiPreferences = remember { context.appGraph.uiPreferences }
    BaseTachiyomiTheme(
        appTheme = appTheme ?: uiPreferences.appTheme.get(),
        isAmoled = amoled ?: uiPreferences.themeDarkAmoled.get(),
        content = content,
    )
}

@Composable
fun TachiyomiPreviewTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(appTheme, isAmoled, content)

@Composable
private fun BaseTachiyomiTheme(
    appTheme: AppTheme,
    isAmoled: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val typography = remember { Typography().withFonts(MslUiFont, MslDisplayFont) }
    val shapes = remember {
        Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
        )
    }
    val targetColorScheme = remember(appTheme, isDark, isAmoled) {
        getThemeColorScheme(
            context = context,
            appTheme = appTheme,
            isDark = isDark,
            isAmoled = isAmoled,
        )
    }
    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme(targetColorScheme),
        typography = typography,
        shapes = shapes,
        content = content,
    )
}

@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val primary by animateColorAsState(target.primary, tween(320), label = "themePrimary")
    val onPrimary by animateColorAsState(target.onPrimary, tween(320), label = "themeOnPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, tween(320), label = "themePrimaryContainer")
    val onPrimaryContainer by animateColorAsState(
        target.onPrimaryContainer,
        tween(320),
        label = "themeOnPrimaryContainer",
    )
    val secondary by animateColorAsState(target.secondary, tween(320), label = "themeSecondary")
    val onSecondary by animateColorAsState(target.onSecondary, tween(320), label = "themeOnSecondary")
    val tertiary by animateColorAsState(target.tertiary, tween(320), label = "themeTertiary")
    val onTertiary by animateColorAsState(target.onTertiary, tween(320), label = "themeOnTertiary")
    val background by animateColorAsState(target.background, tween(320), label = "themeBackground")
    val onBackground by animateColorAsState(target.onBackground, tween(320), label = "themeOnBackground")
    val surface by animateColorAsState(target.surface, tween(320), label = "themeSurface")
    val onSurface by animateColorAsState(target.onSurface, tween(320), label = "themeOnSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, tween(320), label = "themeSurfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, tween(320), label = "themeOnSurfaceVariant")
    val error by animateColorAsState(target.error, tween(320), label = "themeError")
    val onError by animateColorAsState(target.onError, tween(320), label = "themeOnError")
    val errorContainer by animateColorAsState(target.errorContainer, tween(320), label = "themeErrorContainer")
    val onErrorContainer by animateColorAsState(target.onErrorContainer, tween(320), label = "themeOnErrorContainer")
    val outline by animateColorAsState(target.outline, tween(320), label = "themeOutline")
    val outlineVariant by animateColorAsState(target.outlineVariant, tween(320), label = "themeOutlineVariant")
    val surfaceTint by animateColorAsState(target.surfaceTint, tween(320), label = "themeSurfaceTint")
    val surfaceDim by animateColorAsState(target.surfaceDim, tween(320), label = "themeSurfaceDim")
    val surfaceBright by animateColorAsState(target.surfaceBright, tween(320), label = "themeSurfaceBright")
    val surfaceContainerLowest by animateColorAsState(
        target.surfaceContainerLowest,
        tween(320),
        label = "themeSurfaceContainerLowest",
    )
    val surfaceContainerLow by animateColorAsState(
        target.surfaceContainerLow,
        tween(320),
        label = "themeSurfaceContainerLow",
    )
    val surfaceContainer by animateColorAsState(target.surfaceContainer, tween(320), label = "themeSurfaceContainer")
    val surfaceContainerHigh by animateColorAsState(
        target.surfaceContainerHigh,
        tween(320),
        label = "themeSurfaceContainerHigh",
    )
    val surfaceContainerHighest by animateColorAsState(
        target.surfaceContainerHighest,
        tween(320),
        label = "themeSurfaceContainerHighest",
    )

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        tertiary = tertiary,
        onTertiary = onTertiary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceTint = surfaceTint,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}

private fun getThemeColorScheme(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val colorScheme = if (appTheme == AppTheme.MONET) {
        MonetColorScheme(context)
    } else {
        colorSchemes.getOrDefault(appTheme, TachiyomiColorScheme)
    }
    return colorScheme.getColorScheme(
        isDark = isDark,
        isAmoled = isAmoled,
        overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
    )
}

private val colorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
    AppTheme.DEFAULT to TachiyomiColorScheme,
    AppTheme.CATPPUCCIN to CatppuccinColorScheme,
    AppTheme.TOKYONIGHT to TokyoNightColorScheme,
    AppTheme.GREEN_APPLE to GreenAppleColorScheme,
    AppTheme.LAVENDER to LavenderColorScheme,
    AppTheme.MIDNIGHT_DUSK to MidnightDuskColorScheme,
    AppTheme.MONOCHROME to MonochromeColorScheme,
    AppTheme.NORD to NordColorScheme,
    AppTheme.STRAWBERRY_DAIQUIRI to StrawberryColorScheme,
    AppTheme.TAKO to TakoColorScheme,
    AppTheme.TEALTURQUOISE to TealTurqoiseColorScheme,
    AppTheme.TIDAL_WAVE to TidalWaveColorScheme,
    AppTheme.YINYANG to YinYangColorScheme,
    AppTheme.YOTSUBA to YotsubaColorScheme,
)
