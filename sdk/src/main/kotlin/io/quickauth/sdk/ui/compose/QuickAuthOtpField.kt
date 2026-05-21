package io.quickauth.sdk.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import io.quickauth.sdk.QuickAuth
import kotlinx.coroutines.flow.collect

/**
 * Six-box OTP entry field, with auto-fill from [QuickAuth.auth.observeOTP] when the SDK is
 * initialised.  Falls back to a plain text field when SMS Retriever isn't available.
 *
 * Pure UI — does NOT call the verify endpoint.  When the user types the last digit,
 * [onCodeFilled] fires; the host app decides what to do next (typically calls
 * [QuickAuth.auth.verifyOTP]).
 */
@Composable
fun QuickAuthOtpField(
    value: String,
    onValueChange: (String) -> Unit,
    onCodeFilled: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    digitCount: Int = 6,
    autoListenForSms: Boolean = true,
) {
    val colors = LocalQuickAuthColors.current
    val focusManager = LocalFocusManager.current

    // Listen to SMS Retriever in the background and auto-fill the field if a code is detected.
    if (autoListenForSms && QuickAuth.isInitialized) {
        LaunchedEffect(Unit) {
            runCatching {
                QuickAuth.auth.observeOTP().collect { code ->
                    val trimmed = code.take(digitCount)
                    onValueChange(trimmed)
                    if (trimmed.length == digitCount) onCodeFilled?.invoke(trimmed)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // The visible boxes are render-only; the BasicTextField is invisible but receives input.
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(digitCount)
                onValueChange(digits)
                if (digits.length == digitCount) {
                    onCodeFilled?.invoke(digits)
                    focusManager.clearFocus()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            cursorBrush = SolidColor(colors.AccentDeep),
            textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
            decorationBox = { _ ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(digitCount) { i ->
                        val ch = value.getOrNull(i)?.toString() ?: ""
                        val isFilled = ch.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 56.dp)
                                .background(
                                    color = if (isFilled) colors.AccentSoft else colors.BgCard,
                                    shape = QuickAuthShapes.Md,
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = if (isFilled) colors.AccentDeep else colors.Line,
                                    shape = QuickAuthShapes.Md,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ch,
                                color = colors.Ink,
                                fontSize = QuickAuthTypography.OtpDigitSize,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            },
        )
    }
}
