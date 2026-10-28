// kadent-ai's Kotlin build. Kadent resolves what it publishes from ~/.m2, as it resolves its own
// database library, so the versions in gradle/libs.versions.toml follow Kadent's.
rootProject.name = "kadent-ai"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":client")
