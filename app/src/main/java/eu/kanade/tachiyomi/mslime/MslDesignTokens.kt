package eu.kanade.tachiyomi.mslime

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Central design tokens for the Manhwa Slime interface. */
object MslDesignTokens {
    val background = Color(0xFFF3F5F8)
    val backgroundRaised = Color(0xFFEAF2FE)
    val surface = Color(0xFFFFFFFF)
    val surfaceRaised = Color(0xFFFFFFFF)
    val surfaceHighest = Color(0xFFE8F0FD)
    val border = Color(0xFFE1E8EC)
    val shadow = Color(0x0F253142) // rgba(37, 49, 66, 0.06)

    val accent = Color(0xFF1F63C8)
    val accentBright = Color(0xFF1F63C8)
    val accentBlue = Color(0xFF2B7BEA)
    val cyan = Color(0xFFBFD8FA)
    val rose = Color(0xFFEF7777)
    val roseContainer = Color(0xFFFBD3D3)
    val gold = Color(0xFFDAA643)
    val success = Color(0xFF2E8B66)
    val warning = Color(0xFFD18A20)
    val danger = Color(0xFFEF7777)

    val textPrimary = Color(0xFF253142)
    val textSecondary = Color(0xFF5B6878)
    val textMuted = Color(0xFF738092)

    val accentGradient = Brush.horizontalGradient(listOf(accent, accentBlue))
    val heroGradient = Brush.verticalGradient(
        listOf(Color.Transparent, Color(0x80253142)),
    )
    val cardGradient = Brush.linearGradient(listOf(surface, Color(0xFFE1ECFB)))

    val cardShape = RoundedCornerShape(18.dp)
    val compactCardShape = RoundedCornerShape(16.dp)
    val buttonShape = RoundedCornerShape(14.dp)
    val dialogShape = RoundedCornerShape(24.dp)
    val pillShape = RoundedCornerShape(50)
}
