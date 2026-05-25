package io.quickauth.sdk.ui.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import io.quickauth.sdk.OtpChannel
import io.quickauth.sdk.QuickAuth
import io.quickauth.sdk.ui.compose.QuickAuthColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * View-based mirror of [io.quickauth.sdk.ui.compose.QuickAuthLoginButton] for apps that haven't
 * (or can't) adopt Compose yet.
 *
 * Usage:
 * ```xml
 * <io.quickauth.sdk.ui.view.QuickAuthLoginButtonView
 *     android:id="@+id/qaButton"
 *     android:layout_width="match_parent"
 *     android:layout_height="48dp" />
 * ```
 *
 * Then in code:
 * ```kotlin
 * qaButton.phone = "+919876543210"
 * qaButton.onSessionStarted = { sessionId -> /* pair with QuickAuthOtpField */ }
 * qaButton.onError = { err -> /* handle */ }
 * ```
 */
class QuickAuthLoginButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), CoroutineScope {

    private val supervisor = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.Main + supervisor

    private val badge: TextView
    private val label: TextView
    private val spinner: ProgressBar

    var phone: String = ""
    var channel: OtpChannel = OtpChannel.AUTO
    var onInitiated: () -> Unit = {}
    var onError: (Throwable) -> Unit = {}
    var labelText: String = "Continue with QuickAuth"
        set(v) { field = v; label.text = v }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)

        background = GradientDrawable().apply {
            cornerRadius = 8 * resources.displayMetrics.density
            setColor(QuickAuthColors.Ink.toArgb())
        }

        badge = AppCompatTextView(context).apply {
            text = "Q"
            setTextColor(QuickAuthColors.AccentDeep.toArgb())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val sz = (22 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(sz, sz).apply { rightMargin = (10 * resources.displayMetrics.density).toInt() }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 4 * resources.displayMetrics.density
                setColor(QuickAuthColors.AccentSoft.toArgb())
            }
        }
        addView(badge)

        spinner = ProgressBar(context).apply {
            val sz = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(sz, sz).apply { rightMargin = (10 * resources.displayMetrics.density).toInt() }
            visibility = GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        addView(spinner)

        label = AppCompatTextView(context).apply {
            text = labelText
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        addView(label)

        isClickable = true
        isFocusable = true
        setOnClickListener { startFlow() }
    }

    private fun startFlow() {
        if (phone.isBlank()) {
            onError(IllegalArgumentException("phone is empty"))
            return
        }
        spinner.visibility = VISIBLE
        isEnabled = false
        launch {
            try {
                // Kicks off the state machine; outcomes (OtpSent / Verified /
                // OtpFailed / Error) arrive via Config.onAuthEvent.
                QuickAuth.auth.initiate(phone, channel)
                onInitiated()
            } catch (t: Throwable) {
                onError(t)
            } finally {
                spinner.visibility = GONE
                isEnabled = true
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        supervisor.cancel()
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255f).toInt(),
        (red * 255f).toInt(),
        (green * 255f).toInt(),
        (blue * 255f).toInt(),
    )
