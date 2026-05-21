package io.quickauth.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.quickauth.sdk.OtpChannel
import io.quickauth.sdk.QuickAuth
import io.quickauth.sdk.ui.compose.QuickAuthLoginButton
import io.quickauth.sdk.ui.compose.QuickAuthOtpField
import io.quickauth.sdk.ui.compose.QuickAuthTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Tiny sample activity that exercises BOTH usage modes:
 *  1. Headless — top half wires startOTP/verifyOTP manually
 *  2. Component — bottom half drops in [QuickAuthLoginButton] + [QuickAuthOtpField]
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickAuth.init(applicationContext) {
            // Replace with a real call to YOUR backend's session-mint endpoint.
            // Your backend in turn calls QuickAuth's POST /v1/sdk/session with its
            // server-side client_id + client_secret and returns the 10-minute JWT.
            "REPLACE_WITH_SESSION_TOKEN_FROM_YOUR_BACKEND"
        }
        QuickAuth.consent.set(true)

        // Capture launch for attribution. Best-effort.
        lifecycleScope.launch {
            runCatching { QuickAuth.attribution.captureLaunch(intent) }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QuickAuthTheme { SampleScreen() }
                }
            }
        }
    }
}

@Composable
private fun SampleScreen() {
    var phone by remember { mutableStateOf("+919876543210") }
    var session by remember { mutableStateOf<String?>(null) }
    var otp by remember { mutableStateOf("") }
    var jwt by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Headless mode", style = MaterialTheme.typography.titleMedium)
        BasicTextField(value = phone, onValueChange = { phone = it })
        Button(onClick = {
            scope.launch {
                runCatching {
                    val s = QuickAuth.auth.startOTP(phone, OtpChannel.AUTO)
                    session = s.sessionId
                }
            }
        }) { Text("Send OTP (headless)") }

        QuickAuthOtpField(value = otp, onValueChange = { otp = it }, onCodeFilled = { code ->
            session?.let { sid ->
                scope.launch {
                    runCatching {
                        jwt = QuickAuth.auth.verifyOTP(sid, code).jwt
                    }
                }
            }
        })

        Spacer(Modifier.height(24.dp))
        Text("Component mode", style = MaterialTheme.typography.titleMedium)
        QuickAuthLoginButton(
            phone = phone,
            onSuccess = { jwt = it },
            onError = { /* show snackbar */ },
        )
        Text("JWT: ${jwt ?: "—"}")
    }
}
