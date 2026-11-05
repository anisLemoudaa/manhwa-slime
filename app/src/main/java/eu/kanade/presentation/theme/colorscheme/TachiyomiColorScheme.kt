package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Default MANHWA SLIME brand palette (Update 1.2).
 * Light mode follows the official identity:
 * - Background: #F3F5F8
 * - Surface / cards: #FFFFFF
 * - Primary blue: #287BEA
 * - Light blue: #8DBBFA
 * - Limited red accents via tertiary
 * Purple is fully removed.
 */
internal object TachiyomiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF8DBBFA),
        onPrimary = Color(0xFF102A50),
        primaryContainer = Color(0xFF1F63C8),
        onPrimaryContainer = Color(0xFFEAF2FE),
        inversePrimary = Color(0xFF1F63C8),
        secondary = Color(0xFFBFD8FA),
        onSecondary = Color(0xFF253142),
        secondaryContainer = Color(0xFF253B5B),
        onSecondaryContainer = Color(0xFFEAF2FE),
        tertiary = Color(0xFFEF777E),
        onTertiary = Color(0xFF40191B),
        tertiaryContainer = Color(0xFF5C292D),
        onTertiaryContainer = Color(0xFFFBD3D3),
        background = Color(0xFF171D26),
        onBackground = Color(0xFFE8EDF4),
        surface = Color(0xFF202833),
        onSurface = Color(0xFFE8EDF4),
        surfaceVariant = Color(0xFF2A3440),
        onSurfaceVariant = Color(0xFFC1CAD5),
        surfaceTint = Color(0xFF8DBBFA),
        inverseSurface = Color(0xFFE8EDF4),
        inverseOnSurface = Color(0xFF253142),
        error = Color(0xFFFF9A9F),
        onError = Color(0xFF40191B),
        errorContainer = Color(0xFF5C292D),
        onErrorContainer = Color(0xFFFBD3D3),
        outline = Color(0xFF8794A5),
        outlineVariant = Color(0xFF3B4654),
        surfaceContainerLowest = Color(0xFF171D26),
        surfaceContainerLow = Color(0xFF202833),
        surfaceContainer = Color(0xFF252F3A),
        surfaceContainerHigh = Color(0xFF2A3440),
        surfaceContainerHighest = Color(0xFF35404D),
    )

    override val lightScheme = lightColorScheme(
        // Official 1.2 primary blue
        primary = Color(0xFF287BEA),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE1ECFB),
        onPrimaryContainer = Color(0xFF0D2B5C),
        inversePrimary = Color(0xFF8DBBFA),

        // Secondary uses the same blue family for consistency
        secondary = Color(0xFF287BEA),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8F0FD),
        onSecondaryContainer = Color(0xFF0D2B5C),

        // Limited red accent (for ranking / highlights)
        tertiary = Color(0xFFE57373),
        onTertiary = Color(0xFF253142),
        tertiaryContainer = Color(0xFFFBD3D3),
        onTertiaryContainer = Color(0xFF253142),

        // Exact background and surface from the 1.2 document
        background = Color(0xFFF3F5F8),
        onBackground = Color(0xFF253142),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF253142),
        surfaceVariant = Color(0xFFE8F0FD),
        onSurfaceVariant = Color(0xFF5B6878),
        surfaceTint = Color(0xFF287BEA),

        inverseSurface = Color(0xFF253142),
        inverseOnSurface = Color(0xFFF3F5F8),

        error = Color(0xFFCC565E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFBD3D3),
        onErrorContainer = Color(0xFF253142),

        outline = Color(0xFF8A97A7),
        outlineVariant = Color(0xFFE1E8EC),

        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8FAFC),
        surfaceContainer = Color(0xFFF3F5F8),
        surfaceContainerHigh = Color(0xFFEAF2FE),
        surfaceContainerHighest = Color(0xFFE1E8EC),
    )
}
