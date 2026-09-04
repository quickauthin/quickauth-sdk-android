# QuickAuth Android SDK

Phone OTP authentication + WhatsApp marketing attribution for Android, in a single Kotlin library.

`in.quickauth:quickauth-android:1.2.0` — minSdk 21, Compose-first with View-based fallback, zero permissions.

---

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("in.quickauth:quickauth-android:1.2.0")
}
```

> **Renamed in 1.2.0.** The artifact used to be `in.quickauth:sdk`. Only the Maven coordinate
> changed — the Kotlin package is still `io.quickauth.sdk`, so no import statement moves. Update
> the dependency line and rebuild.

## Initialise

The SDK uses **ephemeral session JWTs** minted by *your* backend. No long-lived secret ever ships in the APK. This matches the Twilio Verify pattern used by our web + iOS SDKs.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        QuickAuth.init(this) {
            // Called on first request and ~30s before each token expires.
            // Hit YOUR backend, which in turn calls QuickAuth's POST /v1/sdk/session
            // with its server-side client_id + client_secret.  Return the 10-minute JWT.
            val res = myApi.fetch("/api/quickauth-token")
            res.sessionToken
        }
    }
}
```

### Customer backend — minting the session token

Your backend exposes a single endpoint (e.g. `GET /api/quickauth-token`) that proxies to QuickAuth's `POST /v1/sdk/session`:

**Spring Boot (Kotlin)**

```kotlin
@RestController
class QuickAuthTokenController(
    @Value("\${quickauth.client.id}") private val clientId: String,
    @Value("\${quickauth.client.secret}") private val clientSecret: String,
    private val rest: RestTemplate,
) {
    @GetMapping("/api/quickauth-token")
    fun mint(): Map<String, Any> {
        val headers = HttpHeaders().apply {
            set("X-QuickAuth-Client-Id", clientId)
            set("X-QuickAuth-Client-Secret", clientSecret)
            contentType = MediaType.APPLICATION_JSON
        }
        val resp = rest.exchange(
            "https://api.quickauth.in/v1/sdk/session",
            HttpMethod.POST,
            HttpEntity(mapOf<String, Any>(), headers),
            Map::class.java,
        )
        return resp.body!! as Map<String, Any>   // { sessionToken, expiresIn }
    }
}
```

**Ktor (Kotlin)**

```kotlin
fun Application.quickAuthTokenRoute(client: HttpClient) {
    routing {
        get("/api/quickauth-token") {
            val resp = client.post("https://api.quickauth.in/v1/sdk/session") {
                header("X-QuickAuth-Client-Id", System.getenv("QUICKAUTH_CLIENT_ID"))
                header("X-QuickAuth-Client-Secret", System.getenv("QUICKAUTH_CLIENT_SECRET"))
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            call.respondText(resp.bodyAsText(), ContentType.Application.Json)
        }
    }
}
```

> **Important:** never expose `client_secret` to the Android app — keep it server-side. The SDK only ever sees the 10-minute JWT.

### Unsafe escape hatch (trusted-enterprise only)

If you absolutely cannot run a backend (e.g. internal-only enterprise build), you can ship the credentials in the APK. The SDK will log a loud warning and call `/v1/sdk/session` directly:

```kotlin
QuickAuth.init(
    this,
    Config(
        onTokenExpiry = { error("unused in unsafe mode") },
        unsafeDirectClientId = BuildConfig.QUICKAUTH_CLIENT_ID,
        unsafeDirectClientSecret = BuildConfig.QUICKAUTH_CLIENT_SECRET,
    ),
)
```

## Quick start — headless

Every outcome arrives on one typed event handler. You do not subscribe to anything else.

```kotlin
QuickAuth.setAuthEventHandler { event ->
    when (event) {
        is AuthEvent.OtpSent     -> showOtpInput(event.expiresIn)
        is AuthEvent.OtpAutoRead -> prefill(event.code)          // SMS *and* WhatsApp
        is AuthEvent.Verified    -> sendToMyBackend(event.requestId)
        is AuthEvent.OtpFailed   -> showError(event.message)     // still retry-able
        is AuthEvent.Error       -> showError(event.message)     // final for this attempt
    }
}

// autoSubmit = true lets the SDK verify a code it read itself. Off by default.
QuickAuth.auth.initiate("+919876543210", OtpChannel.AUTO, autoSubmit = true)

// …user types it instead:
QuickAuth.auth.submitOtp("483920")

// …or never got the message:
QuickAuth.auth.resendOtp()
```

Forward `AuthEvent.Verified.requestId` to YOUR backend, which confirms it with QuickAuth via
`GET /v1/auth/status?requestId=…` (X-Client-Id / X-Client-Secret) and mints its own session JWT
against its own user table. See <https://quickauth.in/docs/backend>.

`Verified` also covers silent OneTap re-auth, where no OTP was ever sent.

### resendOtp() takes no arguments

It replays the number the current attempt is already for, carrying that attempt's channel and
`autoSubmit` setting, and re-sends the WhatsApp handshake (Meta expires it after ten minutes).
Asking for the phone number again is an opportunity to pass a different one by accident, which
starts a second transaction and leaves the user holding two codes, only one of which works.

It throws `IllegalStateException` when there is no live attempt — a resend button should only
exist once a code has been sent. `QuickAuth.auth.reset()` ends the attempt, so a resend after it
throws too.

### autoSubmit and the one-shot latch

`autoSubmit` is per attempt and latched: the first auto-read code submits, and nothing else does
until the next `initiate()` / `resendOtp()`. A merchant on `OtpChannel.AUTO` can receive the same
code over both SMS and WhatsApp, and submitting the second would verify a code the server has
already consumed — which surfaces to the user as a failure arriving right after a success.

Both copies still reach you as `AuthEvent.OtpAutoRead`; only the submit is latched.

### Facade surface

| Member | What it is |
|---|---|
| `QuickAuth.isInitialized` | whether `init` has run |
| `QuickAuth.config` | the active `Config` |
| `QuickAuth.tokenManager` | bearer-token cache |
| `QuickAuth.consent` | DPDP / GDPR gate |
| `QuickAuth.auth` | the OTP state machine |
| `QuickAuth.attribution` | click attribution + conversions |
| `QuickAuth.whatsapp` | "login with WhatsApp" deep-link helper |
| `QuickAuth.setAuthEventHandler(handler)` | swap the event handler after `init` (pass `null` to detach) |
| `QuickAuth.reset()` | process-level teardown; use `QuickAuth.auth.reset(forgetDevice = true)` to end a login and drop OneTap trust |

## Quick start — components (5 lines)

```kotlin
QuickAuthLoginButton(
    phone = "+919876543210",
    onSuccess = { jwt -> sendToMyBackend(jwt) },
    onError   = { showError(it) },
)
```

Or in XML:

```xml
<io.quickauth.sdk.ui.view.QuickAuthLoginButtonView
    android:id="@+id/qaButton"
    android:layout_width="match_parent"
    android:layout_height="48dp" />
```

---

## Auto-read (zero permissions)

`initiate()` arms auto-read itself. You do **not** have to subscribe to anything for
`AuthEvent.OtpAutoRead` and `autoSubmit` to work — `observeOTP()` exists for callers who want the
raw stream as well, and deliberately does not re-emit the event.

Two delivery mechanisms feed it, merged, because a merchant on `OtpChannel.AUTO` should not get
auto-read for some users and not others:

**SMS** — [Google Play SMS Retriever](https://developers.google.com/identity/sms-retriever/overview).
No permissions, no privacy banner. Your OTP message body must end with the 11-character app-hash
for the keystore that signed the build. Print it during development:

```kotlin
val hash = QuickAuth.smsRetrieverAppHash(applicationContext)
android.util.Log.d("QA", "Embed this in templates: $hash")

// Rotated your signing key? Every certificate has its own hash and all of them
// must be registered with the sender:
SmsRetriever.computeAppHashesForInstalledApp(applicationContext)
```

**WhatsApp zero-tap / one-tap** — WhatsApp does not send these over SMS. It broadcasts the code
directly to the app named in the approved template's `supported_apps`, matched on package name
and that same 11-character signing hash, so SMS Retriever never sees it.

Everything that needs declaring is in the SDK's own manifest and merges into your app: the
`OTP_RETRIEVED` receiver (declared statically, so a zero-tap code lands even when your app is
backgrounded or not running), and `<queries>` for `com.whatsapp` / `com.whatsapp.w4b` so Android
11+ delivers the handshake the SDK sends before each request. **You configure nothing.**

What you do need, on Meta's side: an approved authentication template with zero-tap or one-tap
enabled, listing your package name and app-hash under `supported_apps`. If those do not match,
WhatsApp shows the message and simply never broadcasts the code — no error, anywhere.

If your customer's SMS sender isn't ours, the SDK falls back to the **SMS User Consent API**
(one-tap dialog).

---

## WhatsApp login

```kotlin
QuickAuth.auth.startWhatsAppLogin(activity, businessNumber = "+919574980048")
```

The user is sent into WhatsApp, our backend marks the session verified, then deep-links back into your app via an Android App Link on `link.quickauth.in`. Add this intent filter to the activity that should handle the return:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="link.quickauth.in" />
</intent-filter>
```

---

## Attribution

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch {
        val attribution = QuickAuth.attribution.captureLaunch(intent)
        // attribution.matched, attribution.campaignId, …
    }
}

// later …
QuickAuth.attribution.trackConversion(event = "signup", value = 0.0, currency = "INR")
```

QuickAuth pulls:

| Source | Used for |
|---|---|
| Deep-link query string (`qa_clid`, `utm_*`) | direct match |
| Play Install Referrer (first launch only) | install attribution |
| SHA-256 device fingerprint | probabilistic match when `qa_clid` is missing |

We never read MAC, IMEI, or `ANDROID_ID` — Play Store policy compliant.

---

## Permissions

| Feature | Permission |
|---|---|
| OTP send/verify | none |
| SMS Retriever | none |
| SMS User Consent | none |
| Install Referrer | none |
| WhatsApp deep-link | none |
| WhatsApp OTP auto-read | none |

The SDK manifest declares **no permissions** — only the WhatsApp `OTP_RETRIEVED` receiver and the
`<queries>` entries that receiver's handshake needs. `RECEIVE_SMS` in particular is deliberately
absent: SMS Retriever does not need it, and it is in Play's restricted set, so declaring an unused
one would force every merchant into a Play Console declaration for nothing. Your app's existing `INTERNET` permission (declared by AGP automatically) is sufficient.

---

## DPDP / GDPR

QuickAuth ships with a built-in consent gate. Until you call `QuickAuth.consent.set(true)`, only auth-flow endpoints are reachable; all attribution and conversion calls are dropped client-side.

```kotlin
QuickAuth.consent.set(userTickedTheBox)   // persisted in SharedPreferences
val granted = QuickAuth.consent.get()
```

---

## Development

```bash
./gradlew :sdk:assembleRelease      # build the AAR
./gradlew :sdk:testDebugUnitTest    # run unit tests (JUnit + Mockk + Robolectric)
./gradlew :sdk:computeAppHash       # helper task — see above
```

The repo ships a Gradle wrapper pointing at Gradle 8.5; if `gradle/wrapper/gradle-wrapper.jar` is missing in your checkout, regenerate it once with:

```bash
gradle wrapper --gradle-version 8.5
```

---

## Versioning

The SDK version lives in exactly one place: `val quickauthSdkVersion` at the top of
`sdk/build.gradle.kts`. It becomes the Maven coordinate *and*, via
`BuildConfig.QUICKAUTH_SDK_VERSION`, the `Config.SDK_VERSION` reported in the `User-Agent` on
every request. Bump it there; there is no second copy to keep in sync.

---

## License

MIT — see [LICENSE](./LICENSE).
