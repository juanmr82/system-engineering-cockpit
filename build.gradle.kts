/
// Root build. Module wiring only — plugin application and dependencies live in each
// subproject's own build.gradle.kts (backend/build.gradle.kts).

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
