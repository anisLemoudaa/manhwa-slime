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
    val background = Color(0xFF050C1B)
    val backgroundRaised = Color(0xFF08142A)
    val surface = Color(0xFF0D1A35)
    val surfaceRaised = Color(0xFF122344)
    val surfaceHighest = Color(0xFF192B50)
    val border = Color(0xFF2B416B)

    val accent = Color(0xFF8652FF)
    val accentBright = Color(0xFFA65BFF)
    val accentBlue = Color(0xFF456EFF)
    val cyan = Color(0xFF35D7C6)
    val success = Color(0xFF51DDAF)
    val warning = Color(0xFFFFC55C)
    val danger = Color(0xFFFF6484)

    val textPrimary = Color(0xFFF5F6FF)
    val textSecondary = Color(0xFFB6C2DE)
    val textMuted = Color(0xFF8393B7)

    val accentGradient = Brush.horizontalGradient(listOf(accentBlue, accent, accentBright))
    val heroGradient = Brush.verticalGradient(
        listOf(Color.Transparent, background.copy(alpha = 0.12f), background),
    )
    val cardGradient = Brush.linearGradient(listOf(surfaceRaised, surface))

    val cardShape = RoundedCornerShape(20.dp)
    val compactCardShape = RoundedCornerShape(16.dp)
    val pillShape = RoundedCornerShape(50)
}
