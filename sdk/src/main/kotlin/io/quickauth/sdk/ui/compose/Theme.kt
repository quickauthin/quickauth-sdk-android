package io.quickauth.sdk.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * QuickAuth brand colours, sourced from
 * `quickauth-website/src/styles/tokens.css`.  Kept in a tiny manual palette object rather than
 * Material's [androidx.compose.material3.ColorScheme] so the SDK doesn't impose Material on host
 * apps that aren't using it.
 */
object QuickAuthColors {
    val Bg = Color(0xFFFAFAF7)
    val BgCard = Color(0xFFFFFFFF)
    val BgDark = Color(0xFF0A0A0A)
    val Ink = Color(0xFF0A0A0A)
    val InkSoft = Color(0xFF555555)
    val InkMute = Color(0xFF888888)
    val Line = Color(0x14000000) // rgba(0,0,0,0.08)
    val Accent = Color(0xFF00E640)
    val AccentDeep = Color(0xFF00C637)   // main brand accent used on the [Q] badge
    val AccentDarker = Color(0xFF00772B)
    val AccentSoft = Color(0xFFE6F9EC)
    val Wa = Color(0xFF25D366)
    val Danger = Color(0xFFB94C4C)
}

object QuickAuthShapes {
    val Sm = RoundedCornerShape(6.dp)
    val Md = RoundedCornerShape(8.dp)
    val Lg = RoundedCornerShape(12.dp)
    val Xl = RoundedCornerShape(16.dp)
}

object QuickAuthTypography {
    val ButtonFontSize = 15.sp
    val OtpDigitSize = 22.sp
}

/** Optional override hook so customer apps can swap colours without forking the SDK. */
val LocalQuickAuthColors = staticCompositionLocalOf { QuickAuthColors }

@Composable
fun QuickAuthTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalQuickAuthColors provides QuickAuthColors) {
        content()
    }
}
