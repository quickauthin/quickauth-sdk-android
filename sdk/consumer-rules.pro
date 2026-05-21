# QuickAuth SDK consumer ProGuard rules.
# These rules are applied to apps that depend on the SDK; they preserve the public surface
# and the data classes serialised on the wire so reflective JSON (de)serialisation keeps working.

-keep class io.quickauth.sdk.QuickAuth { *; }
-keep class io.quickauth.sdk.QuickAuth$* { *; }
-keep class io.quickauth.sdk.auth.** { *; }
-keep class io.quickauth.sdk.attribution.** { *; }
-keep class io.quickauth.sdk.core.** { *; }

# Wire models (Gson uses reflection on field names).
-keepclassmembers class io.quickauth.sdk.** {
    <fields>;
}

# Keep SMS Retriever broadcast receiver registration intact.
-keep class com.google.android.gms.auth.api.phone.** { *; }

# Keep Play Install Referrer client.
-keep class com.android.installreferrer.** { *; }
