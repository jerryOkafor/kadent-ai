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

// Each project is named as it is published, kadent-ai-<folder>. Renaming the publications instead
// misses the iOS and Web ones, which Kotlin names after that rename has run.
include(":kadent-ai-client")
project(":kadent-ai-client").projectDir = file("client")
include(":kadent-ai-receipt")
project(":kadent-ai-receipt").projectDir = file("receipt")
