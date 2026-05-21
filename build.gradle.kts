// Root build file for the QuickAuth Android SDK.
// Plugin versions are declared once here using the `apply false` pattern so subprojects can simply
// reference them by id.

plugins {
    id("com.android.library") version "8.2.2" apply false
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("maven-publish")
    id("signing") apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
