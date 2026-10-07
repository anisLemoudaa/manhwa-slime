package eu.kanade.tachiyomi.mslime

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.tachiyomi.R

val MslUiFont = FontFamily(Font(R.font.msl_font_regular, FontWeight.Normal), Font(R.font.msl_font_bold, FontWeight.Bold))
val MslDisplayFont = FontFamily(Font(R.font.msl_font_display, FontWeight.Normal), Font(R.font.msl_font_display, FontWeight.Bold))

fun Typography.withFonts(body: FontFamily, display: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = display),
    displayMedium = displayMedium.copy(fontFamily = display),
    displaySmall = displaySmall.copy(fontFamily = display),
    headlineLarge = headlineLarge.copy(fontFamily = display),
    headlineMedium = headlineMedium.copy(fontFamily = display),
    headlineSmall = headlineSmall.copy(fontFamily = display),
    titleLarge = titleLarge.copy(fontFamily = display),
    titleMedium = titleMedium.copy(fontFamily = display),
    titleSmall = titleSmall.copy(fontFamily = display),
    bodyLarge = bodyLarge.copy(fontFamily = body),
    bodyMedium = bodyMedium.copy(fontFamily = body),
    bodySmall = bodySmall.copy(fontFamily = body),
    labelLarge = labelLarge.copy(fontFamily = body),
    labelMedium = labelMedium.copy(fontFamily = body),
    labelSmall = labelSmall.copy(fontFamily = body),
)

@Composable
fun MslThemed(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography.withFonts(MslUiFont, MslDisplayFont),
        content = content,
    )
}
