package io.quickauth.sdk.ui.view

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatEditText
import io.quickauth.sdk.QuickAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * A simple `EditText` subclass that:
 *  * accepts only digits, max [digitCount] long
 *  * uses monospaced font + center alignment to look like an OTP field
 *  * subscribes to [QuickAuth.auth.observeOTP] (when initialised) and auto-fills inbound codes
 *  * fires [onCodeFilled] when the user reaches [digitCount] digits
 *
 * Compose users should prefer [io.quickauth.sdk.ui.compose.QuickAuthOtpField] which renders
 * separate boxes per digit; this single-field view is a no-fuss fallback for older XML stacks.
 */
class QuickAuthOtpFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr), CoroutineScope {

    private val supervisor = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.Main + supervisor

    /** Number of digits expected.  Default 6 — change via attr or code. */
    var digitCount: Int = 6
        set(v) { field = v; filters = arrayOf(InputFilter.LengthFilter(v)) }

    /** Fired once when the user types/auto-fills the final digit. */
    var onCodeFilled: ((String) -> Unit)? = null

    init {
        inputType = InputType.TYPE_CLASS_NUMBER
        keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789")
        filters = arrayOf(InputFilter.LengthFilter(digitCount))
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        textSize = 22f
        letterSpacing = 0.4f

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString().orEmpty()
                if (digits.length == digitCount) onCodeFilled?.invoke(digits)
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (QuickAuth.isInitialized) {
            launch {
                runCatching {
                    QuickAuth.auth.observeOTP().collect { code ->
                        setText(code.take(digitCount))
                        setSelection(text?.length ?: 0)
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        supervisor.cancel()
    }
}
