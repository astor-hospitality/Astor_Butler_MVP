pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.2.1"
    }
}

// The Android plugin and the libraries of the application exist only in Google's repository (dl.google.com),
// and some networks cannot reach it. The rules module is plain Java from Maven Central, so with -PcoreOnly
// the application is left out and ./gradlew :core:test -PcoreOnly runs without that repository.
val coreOnly = providers.gradleProperty("coreOnly").isPresent

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!coreOnly) {
            google()
        }
        mavenCentral()
    }
}

rootProject.name = "AstorGlassesAndroid"
// :core — rules and the server protocol, plain Java, tested on any computer without a phone or glasses.
// :app  — the Android application around it.
include(":core")
if (!coreOnly) {
    include(":app")
}
