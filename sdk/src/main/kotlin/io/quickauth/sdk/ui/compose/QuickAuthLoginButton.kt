package io.quickauth.sdk.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.quickauth.sdk.OtpChannel
import io.quickauth.sdk.QuickAuth
import kotlinx.coroutines.launch

/** Visual variant for [QuickAuthLoginButton]. */
enum class ButtonStyle { PRIMARY, GHOST, WHATSAPP }

/**
 * Drop-in "Continue with QuickAuth" button.
 *
 * Component-mode entry point: handles the full OTP flow internally — calls
 * [QuickAuth.auth.startOTP] in the background, shows a small inline OTP entry, then
 * invokes [onSessionStarted] with the sessionId once the OTP has been dispatched.
 * Pair with a [QuickAuthOtpField] subscribed to `QuickAuth.auth.observeOTP()`
 * to complete the verification — your backend then confirms server-to-server
 * and mints its own session JWT (QuickAuth is verification-only).
 *
 * For more control use the headless API: [QuickAuth.auth] directly.
 *
 * Visual language matches `quickauth-website/tokens.css`:
 *  * `[Q]` JetBrains-Mono badge in accent green (`#00C637`)
 *  * black ink button background, accent on hover/press
 */
@Composable
fun QuickAuthLoginButton(
    phone: String,
    onInitiated: () -> Unit,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    text: String = "Continue with QuickAuth",
    style: ButtonStyle = ButtonStyle.PRIMARY,
    channel: OtpChannel = OtpChannel.AUTO,
) {
    val colors = LocalQuickAuthColors.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    val (bg, fg, border) = when (style) {
        ButtonStyle.PRIMARY -> Triple(colors.Ink, Color.White, null)
        ButtonStyle.GHOST -> Triple(colors.BgCard, colors.Ink, BorderStroke(0.5.dp, colors.Line))
        ButtonStyle.WHATSAPP -> Triple(colors.Wa, Color.White, null)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(QuickAuthShapes.Md)
            .background(bg)
            .let { if (border != null) it.border(border, QuickAuthShapes.Md) else it }
            .clickable(enabled = !loading) {
                loading = true
                scope.launch {
                    try {
                        // Kicks off the state machine; outcomes (OtpSent / Verified /
                        // OtpFailed / Error) arrive via Config.onAuthEvent. This button
                        // is a thin trigger — the host app subscribes to AuthEvent to
                        // route the actual UI transitions.
                        QuickAuth.auth.initiate(phone, channel)
                        onInitiated()
                    } catch (t: Throwable) {
                        onError(t)
                    } finally {
                        loading = false
                    }
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        QBadge(accent = if (style == ButtonStyle.PRIMARY) colors.Accent else colors.AccentDeep)
        Spacer(Modifier.width(10.dp))
        if (loading) {
            CircularProgressIndicator(
                color = fg,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = QuickAuthTypography.ButtonFontSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The little `[Q]` JetBrains-Mono badge that appears in the wordmark / nav across the brand. */
@Composable
internal fun QBadge(accent: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = 0.15f))
            .border(0.5.dp, accent, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Q",
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

