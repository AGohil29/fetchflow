pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fetchflow"

// Core & Modular Libraries
include(":fetchflow-core")
include(":fetchflow-filesystem")
include(":fetchflow-storage")
include(":fetchflow-network")
include(":fetchflow-runtime")
include(":samples:sample-desktop")
include(":samples:sample-android")
include(":samples:sample-common")