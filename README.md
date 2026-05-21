# QuickAuth Android SDK

Phone OTP authentication + WhatsApp marketing attribution for Android, in a single Kotlin library.

`io.quickauth:sdk:0.1.0` — minSdk 21, Compose-first with View-based fallback, zero permissions.

---

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.quickauth:sdk:0.1.0")
}
```

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

## Quick start — headless (5 lines)

```kotlin
val session = QuickAuth.auth.startOTP("+919876543210", channel = OtpChannel.AUTO)
QuickAuth.auth.observeOTP { code ->
    val result = QuickAuth.auth.verifyOTP(session.sessionId, code)
    sendToMyBackend(result.jwt)        // short-lived, validate via /v1/sdk/auth/introspect
}
```

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

## SMS auto-read (zero permissions)

QuickAuth uses [Google Play SMS Retriever](https://developers.google.com/identity/sms-retriever/overview) — no permissions required, no privacy banner. Your OTP message body must end with the 11-character app-hash for your release keystore.

Print it during development:

```kotlin
val hash = QuickAuth.smsRetrieverAppHash(applicationContext)
android.util.Log.d("QA", "Embed this in templates: $hash")
```

Or via Gradle:

```bash
./gradlew :sdk:computeAppHash
```

If your customer's SMS sender isn't ours, the SDK silently falls back to the **SMS User Consent API** (one-tap dialog).

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

The SDK manifest is **empty**. Your app's existing `INTERNET` permission (declared by AGP automatically) is sufficient.

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
./gradlew :sdk:assembleRelease   # build the AAR
./gradlew :sdk:test              # run unit tests (JUnit + Mockk + Robolectric)
./gradlew :sdk:computeAppHash    # helper task — see above
```

The repo ships a Gradle wrapper pointing at Gradle 8.5; if `gradle/wrapper/gradle-wrapper.jar` is missing in your checkout, regenerate it once with:

```bash
gradle wrapper --gradle-version 8.5
```

---

## License

MIT — see [LICENSE](./LICENSE).
