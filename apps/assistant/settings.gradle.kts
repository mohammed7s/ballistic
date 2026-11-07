// Ballistic assistant app: the Kotlin shell around the Rust core (SPEC.md §4.1, docs/ANDROID_APP.md).
// :core-api is pure Kotlin (ports, data shapes, engine interface) and builds without an Android SDK.
// :app is the Android application.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ballistic-assistant"

include(":core-api")
include(":app")
