// AGP 9 compiles Kotlin itself (built-in Kotlin). Putting the Kotlin Gradle plugin on the
// buildscript classpath pins the Kotlin compiler version instead of AGP's bundled default.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
