plugins {
    alias(libs.plugins.android.application) apply false
    // Hebt das von AGP mitgebrachte Kotlin-Gradle-Plugin auf die Version der übrigen Kotlin-Plugins.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
