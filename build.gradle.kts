plugins {
    alias(libs.plugins.android.application) apply false
    // Supplies Kotlin Gradle Plugin 2.4.0 on the root build classpath for AGP 9 built-in Kotlin
    // so Kotlin 2.4 metadata in litertlm-android is supported; NOT applied in :app.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
