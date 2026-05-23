plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp")
}

nmcp {
    publishAllPublications {
        username = (findProperty("sonatypeUsername") as String?) ?: System.getenv("SONATYPE_USERNAME") ?: ""
        password = (findProperty("sonatypePassword") as String?) ?: System.getenv("SONATYPE_PASSWORD") ?: ""
        publicationType = "USER_MANAGED"
    }
}

android {
    namespace = "io.quickauth.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "consumer-rules.pro")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Google Play Services — SMS Retriever / User Consent
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.0.1")
    implementation("com.google.android.gms:play-services-base:18.3.0")

    // Install Referrer (Play Store)
    implementation("com.android.installreferrer:installreferrer:2.2")

    // HTTP + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Publishing — Maven Central group in.quickauth, artifact sdk, version 0.1.0.
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "in.quickauth"
                artifactId = "sdk"
                version = "1.0.0"
                pom {
                    name.set("QuickAuth Android SDK")
                    description.set("Phone OTP auth + WhatsApp marketing attribution SDK for Android.")
                    url.set("https://quickauth.in")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("quickauth")
                            name.set("QuickAuth")
                            email.set("contact@quickauth.in")
                            organization.set("QuickAuth")
                            organizationUrl.set("https://quickauth.in")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/quickauthin/quickauth-sdk-android.git")
                        developerConnection.set("scm:git:ssh://github.com/quickauthin/quickauth-sdk-android.git")
                        url.set("https://github.com/quickauthin/quickauth-sdk-android")
                    }
                }
            }
        }
    }

    signing {
        val signingKey = (findProperty("signingKey") as String?) ?: System.getenv("SIGNING_KEY")
        val signingPassword = (findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD")
        if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }
}

// Gradle task that prints the SHA-256 SMS Retriever app-hash for the configured signing config.
// The 11-char Base64-truncated hash must be embedded into your OTP message body so
// SmsRetrieverClient can match + auto-deliver the message without permissions.
tasks.register("computeAppHash") {
    group = "quickauth"
    description = "Prints the 11-char SMS Retriever app-hash for the release keystore."
    doLast {
        println("================ QuickAuth SMS Retriever app-hash ================")
        println("This is a placeholder helper. Run on a device once via")
        println("  QuickAuth.smsRetrieverAppHash(context)")
        println("OR feed your release keystore SHA-256 + applicationId through")
        println("  io.quickauth.sdk.auth.SmsRetriever.computeAppHash(...)")
        println("==================================================================")
    }
}
