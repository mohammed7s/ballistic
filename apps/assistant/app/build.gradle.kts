// The Android shell. AGP 9 compiles Kotlin itself (built-in Kotlin), so only the Compose and
// serialization compiler plugins are applied here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.ballistic.assistant"
    // Android 17 (API 37): the release the gates target (docs/PLATFORM.md) and the one
    // GrapheneOS and the test phone run. minSdk 33 so the APK installs on the AndroidWorld
    // emulator (API 33) as well as on an Android 14+ phone for the unprivileged step (PLAN step 3).
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" }
    }

    defaultConfig {
        applicationId = "app.ballistic.assistant"
        minSdk = 33
        targetSdk = 37
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake {
            arguments += "-DANDROID_STL=c++_shared"
            providers.gradleProperty("llamaArchive").orNull?.let { arguments += "-DBALLISTIC_LLAMA_ARCHIVE=$it" }
        } }
        versionCode = 1
        versionName = "0.1.0"
        // Shown in Local AI's technical details; the catalogue is the single source.
        buildConfigField("String", "LITERTLM_VERSION", "\"${libs.versions.litertlm.get()}\"")
        buildConfigField("String", "LITERT_NPU_GOOGLE_TENSOR_VERSION", "\"${libs.versions.litert.npu.google.tensor.get()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // LiteRT-LM loads the Google Tensor dispatch library from nativeLibraryDir, which is
        // populated only when native libraries are extracted at install (official TPU sample).
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-api"))
    // JNA for the UniFFI binding: the aar carries libjnidispatch.so for each ABI.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(libs.kotlinx.coroutines.android)
    // LiteRT-LM (CPU/GPU/NPU engine; arm64-v8a and x86_64) and the Google Tensor NPU dispatch
    // runtime (arm64-v8a only; its manifest declares the optional vendor edgetpu libraries).
    implementation(libs.litertlm.android)
    implementation(libs.litert.npu.google.tensor)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
