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
    val background = Color(0xFF000000)
    val backgroundRaised = Color(0xFF0B1124)
    val surface = Color(0xFF0F1B3D)
    val surfaceRaised = Color(0xFF15204A)
    val surfaceHighest = Color(0xFF15204A)
    val border = Color(0xFF26335F)

    val accent = Color(0xFF6F42F5)
    val accentBright = Color(0xFFA78BFA)
    val accentBlue = Color(0xFF526FFF)
    val cyan = Color(0xFF38BDF8)
    val rose = Color(0xFFFF5D8F)
    val gold = Color(0xFFFBBF24)
    val success = Color(0xFF51DDAF)
    val warning = Color(0xFFFFC55C)
    val danger = Color(0xFFFF6484)

    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFAEB3C7)
    val textMuted = Color(0xFF8E96B8)

    val accentGradient = Brush.horizontalGradient(listOf(accentBlue, accent, rose, accentBright))
    val heroGradient = Brush.verticalGradient(
        listOf(Color.Transparent, background.copy(alpha = 0.12f), background),
    )
    val cardGradient = Brush.linearGradient(listOf(surfaceRaised, surface))

    val cardShape = RoundedCornerShape(20.dp)
    val compactCardShape = RoundedCornerShape(16.dp)
    val pillShape = RoundedCornerShape(50)
}
