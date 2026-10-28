plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    `maven-publish`
}

group = "me.jerryokafor.kadent"
version = "0.1.0"

kotlin {
    jvmToolchain(21)

    android {
        namespace = "me.jerryokafor.kadent.ai"
        compileSdk = 36
        // Kadent's floor. Lower would compile against APIs the app never runs on; higher would
        // refuse to link into it.
        minSdk = 31
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            // api: AiClient takes the app's HttpClient, so the app sees Ktor's types. The library
            // names no engine; the app already has one per platform.
            api(libs.ktor.client.core)
            api(libs.serialization.json)
            implementation(libs.coroutines.core)
        }
        // The test library's virtual clock (`currentTime`, `advanceTimeBy`) is experimental. Opted in
        // once for the test source set, as Kadent does, rather than on each file.
        jvmTest { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

publishing {
    repositories { mavenLocal() }
    // Published as kadent-ai-client (and -jvm, -android, ...), not as the folder's bare name.
    publications.withType<MavenPublication>().configureEach { artifactId = "kadent-ai-$artifactId" }
}
