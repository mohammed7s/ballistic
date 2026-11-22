// Compile-only stubs for the Computer Control extension library, the agent-facing API of
// Android 17's Computer Control (shared library `com.android.extensions.computercontrol`,
// /system_ext/framework/com.android.extensions.computercontrol.jar). It is not in the public
// android.jar and not an SDK add-on: AOSP publishes its API as
// frameworks/base/libs/computercontrol/api_extension/current.txt (android17-release). The
// headers derive from GrapheneOS tag 2026110200's public signature (see docs/PLATFORM.md).
// This is a fallback when the actual Soong public jar is unavailable. :app can instead use
// -PcomputerControlStubs=/path/to/computercontrol.extension.stubs.public.jar. Either dependency
// is compileOnly: the OS shared library supplies every runtime implementation.
//
// The stubs reference android.* types (Context, AccessibilityWindowInfo, ...), so they compile
// against the platform android.jar of the SDK that builds :app.
import java.util.Properties

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/** The SDK :app builds with: local.properties sdk.dir, else ANDROID_HOME / ANDROID_SDK_ROOT. */
fun androidSdkDir(): File? {
    val props = rootDir.resolve("local.properties")
    if (props.isFile) {
        val p = Properties().apply { props.inputStream().use { load(it) } }
        p.getProperty("sdk.dir")?.let { return File(it) }
    }
    return (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let(::File)
}

/** Platform 37 (Android 17). Newer SDK managers name the directory android-37.0. */
fun androidJar(): File {
    val sdk = androidSdkDir() ?: throw GradleException("computercontrol-stubs: no Android SDK (local.properties sdk.dir or ANDROID_HOME)")
    return listOf("android-37.0", "android-37").map { sdk.resolve("platforms/$it/android.jar") }.firstOrNull { it.isFile }
        ?: throw GradleException("computercontrol-stubs: platform 37 android.jar not found under $sdk/platforms")
}

dependencies {
    // Resolved only when these stubs compile, so `:core-api:test` still needs no Android SDK.
    compileOnly(files(provider { androidJar() }))
}
