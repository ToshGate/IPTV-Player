buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 has built-in Kotlin support and pulls in Kotlin Gradle plugin 2.2.10 by default.
        // Declaring a newer one here is the documented way to use the latest stable Kotlin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    // No org.jetbrains.kotlin.android plugin: AGP 9 compiles Kotlin itself (built-in Kotlin).
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
