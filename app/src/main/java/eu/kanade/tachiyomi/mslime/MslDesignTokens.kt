package eu.kanade.tachiyomi.mslime

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared light-first Manhwa Slime palette. Keep design tokens separate from feature data so
 * screens can adopt the reference design without introducing placeholder content.
 */
object MslDesignTokens {
    val background = Color(0xFFF3F5F8)
    val backgroundRaised = Color(0xFFE9EDF2)
    val surface = Color(0xFFFFFFFF)
    val surfaceRaised = Color(0xFFFFFFFF)
    val surfaceHighest = Color(0xFFFFFFFF)
    val border = Color(0xFFE8EDEF)

    val accent = Color(0xFF287BEA)
    val accentBright = Color(0xFF287BEA)
    val accentBlue = Color(0xFF287BEA)
    val cyan = Color(0xFF8DBBFA)
    val rose = Color(0xFFFF777E)
    val gold = Color(0xFFD6A43D)
    val success = Color(0xFF2E8B66)
    val warning = Color(0xFFD18A20)
    val danger = Color(0xFFFF777E)

    val textPrimary = Color(0xFF253142)
    val textSecondary = Color(0xFF5B6878)
    val textMuted = Color(0xFF738092)

    val accentGradient = Brush.horizontalGradient(listOf(accentBlue, Color(0xFF8DBBFA)))
    val heroGradient = Brush.verticalGradient(
        listOf(Color.Transparent, Color(0x33253142), Color(0xF3253142)),
    )
    val cardGradient = Brush.linearGradient(listOf(surface, surfaceRaised))

    val cardShape = RoundedCornerShape(20.dp)
    val compactCardShape = RoundedCornerShape(16.dp)
    val pillShape = RoundedCornerShape(50)
}
