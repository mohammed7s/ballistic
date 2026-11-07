// Pure Kotlin/JVM: the data shapes and ports shared with the Rust core, the engine interface and
// the shell logic that needs no Android API (prompt session, policy file, action log). Compiles
// and tests on any JDK, so JSON compatibility with core is checked without an Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Bytecode 17 so :app (Java 17 target) can consume it; built with whatever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Tests read recorded screens from core/tests/screens so the fixtures have one copy.
    systemProperty("ballistic.repo", rootDir.resolve("../..").canonicalPath)
}
