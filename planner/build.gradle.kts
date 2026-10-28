plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    `maven-publish`
}

group = "me.jerryokafor.kadent"
version = "0.2.0"

kotlin {
    jvmToolchain(21)

    android {
        namespace = "me.jerryokafor.kadent.ai.planner"
        compileSdk = 36
        minSdk = 31
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            // api: TripPlanner takes an AiClient.
            api(project(":kadent-ai-client"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // The live planning test only: a real engine to reach Meta.
            implementation(libs.ktor.client.cio)
        }
    }
}

publishing {
    repositories { mavenLocal() }
}
