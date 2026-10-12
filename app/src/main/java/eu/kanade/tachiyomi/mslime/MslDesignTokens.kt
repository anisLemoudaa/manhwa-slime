package eu.kanade.tachiyomi.mslime

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Visual tokens for the ManhwaSlime reskin. Keep these independent from feature data so
 * every screen can share the same palette without introducing placeholder content.
 */
object MslDesignTokens {
    val background = Color(0xFF030714)
    val backgroundRaised = Color(0xFF080F24)
    val surface = Color(0xFF0B1530)
    val surfaceRaised = Color(0xFF111F40)
    val surfaceHighest = Color(0xFF1A2B55)
    val border = Color(0xFF373A80)

    val accent = Color(0xFF8652FF)
    val accentBright = Color(0xFFBE7BFF)
    val accentBlue = Color(0xFF526FFF)
    val cyan = Color(0xFF3CCBFF)
    val rose = Color(0xFFF278D4)
    val gold = Color(0xFFFFC857)
    val success = Color(0xFF51DDAF)
    val warning = Color(0xFFFFC55C)
    val danger = Color(0xFFFF6484)

    val textPrimary = Color(0xFFF5F6FF)
    val textSecondary = Color(0xFFB6C2DE)
    val textMuted = Color(0xFF8393B7)

    val accentGradient = Brush.horizontalGradient(listOf(accentBlue, accent, rose, accentBright))
    val heroGradient = Brush.verticalGradient(
        listOf(Color.Transparent, background.copy(alpha = 0.12f), background),
    )
    val cardGradient = Brush.linearGradient(listOf(surfaceRaised, surface))

    val cardShape = RoundedCornerShape(20.dp)
    val compactCardShape = RoundedCornerShape(16.dp)
    val pillShape = RoundedCornerShape(50)
}
