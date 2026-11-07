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
    // The generated UniFFI binding (core/ffi) compiles against JNA; :app supplies the Android
    // JNA (aar, with libjnidispatch.so), tests the desktop jar.
    compileOnly(libs.jna)
    testImplementation(libs.jna)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Optional host JNI smoke, built from app/src/main/cpp (no Android device or weights).
    System.getenv("BALLISTIC_LLAMA_LIBRARY_DIR")?.let {
        systemProperty("java.library.path", it)
        systemProperty("ballistic.llama.test", "true")
    }
    System.getenv("BALLISTIC_LLAMA_TEST_MODEL")?.let { systemProperty("ballistic.llama.model", it) }
    System.getenv("BALLISTIC_LLAMA_TEST_MODEL_SHA256")?.let { systemProperty("ballistic.llama.model.sha256", it) }
    // Tests read recorded screens from core/tests/screens so the fixtures have one copy.
    systemProperty("ballistic.repo", rootDir.resolve("../..").canonicalPath)
    // UniffiCoreEngineTest runs the real core when a host build exists:
    //   (cd core && cargo build --release --features uniffi --lib), or scripts/build_core.sh.
    // BALLISTIC_HOST_CORE points at it when cargo's target directory is elsewhere.
    val hostCore = System.getenv("BALLISTIC_HOST_CORE")?.let(::File)
        ?: rootDir.resolve("../../core/target/release/libballistic_core.so")
    if (hostCore.exists()) systemProperty("uniffi.component.ballistic_core.libraryOverride", hostCore.canonicalPath)
}
