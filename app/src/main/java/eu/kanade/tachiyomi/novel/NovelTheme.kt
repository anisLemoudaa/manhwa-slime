package eu.kanade.tachiyomi.novel

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

/** Design tokens for the redesigned novels area. Keep feature styling scoped to this theme. */
internal object NovelColors {
    val background = Color(0xFFFBF8F3)
    val backgroundRaised = Color(0xFFFFFEFC)
    val surface = Color(0xFFFFFEFC)
    val surfaceRaised = Color(0xFFF5F1EA)
    val surfaceHighest = Color(0xFFEDE8DF)
    val textPrimary = Color(0xFF1D1B2E)
    val textSecondary = Color(0xFF6B6880)
    val textMuted = Color(0xFF888497)
    val accent = Color(0xFF5B3DF5)
    val accentBright = Color(0xFF745EF7)
    val divider = Color(0xFFE9E4DA)
    val border = divider
    val star = Color(0xFFF0A81C)
    val success = Color(0xFF37845E)
    val error = Color(0xFFB3261E)
    val danger = error
    val coverFallback = Brush.linearGradient(
        colors = listOf(Color(0xFFECE6FF), Color(0xFFF7E9DC), Color(0xFFE3E9FF)),
    )
    val heroGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF33216F), Color(0xFF6246D8), Color(0xFFBAA8FF)),
    )
    val skeleton = Color(0xFFE9E4DA)
    val cardShape = RoundedCornerShape(20.dp)
    val compactCardShape = RoundedCornerShape(16.dp)
    val pillShape = RoundedCornerShape(50)
}

/** Shared spacing, corner and minimum touch-target values for novels UI. */
internal object NovelDimensions {
    val touchTarget = 44.dp
    val screenHorizontal = 20.dp
    val spacingXSmall = 4.dp
    val spacingSmall = 8.dp
    val spacingMedium = 12.dp
    val spacingLarge = 16.dp
    val spacingXLarge = 24.dp
    val coverCornerRadius = 16.dp
    val cardCornerRadius = 18.dp
    val bottomSheetTopRadius = 28.dp
}

internal val NovelFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
)

private val novelTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = NovelFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = NovelFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = NovelFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = NovelFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = NovelFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = NovelFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = NovelFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = NovelFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = NovelFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = NovelFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = NovelFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = NovelFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = NovelFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = NovelFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = NovelFontFamily),
    )
}

private val novelColorScheme = lightColorScheme(
    primary = NovelColors.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE4FF),
    onPrimaryContainer = NovelColors.textPrimary,
    secondary = NovelColors.textSecondary,
    onSecondary = Color.White,
    tertiary = NovelColors.star,
    onTertiary = NovelColors.textPrimary,
    background = NovelColors.background,
    onBackground = NovelColors.textPrimary,
    surface = NovelColors.surface,
    onSurface = NovelColors.textPrimary,
    surfaceVariant = NovelColors.surfaceRaised,
    onSurfaceVariant = NovelColors.textSecondary,
    outline = NovelColors.divider,
    outlineVariant = NovelColors.divider,
    error = NovelColors.error,
    onError = Color.White,
)

private val novelShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(NovelDimensions.coverCornerRadius),
    large = RoundedCornerShape(NovelDimensions.cardCornerRadius),
    extraLarge = RoundedCornerShape(
        topStart = NovelDimensions.bottomSheetTopRadius,
        topEnd = NovelDimensions.bottomSheetTopRadius,
    ),
)

/** Applies the novel-specific Material 3 palette, typography and RTL direction. */
@Composable
internal fun NovelTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = novelColorScheme,
            typography = novelTypography,
            shapes = novelShapes,
            content = content,
        )
    }
}
