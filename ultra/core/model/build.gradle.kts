// :ultra-core-model - pure Kotlin domain types and the ports that features depend on.
//
// Kotlin Multiplatform from day one: commonMain only, no android.*, no DI annotations, no upstream
// types. Targets are Android (for :app) and desktop JVM (for the Windows client, P10).
// Per the blueprint, KMP modules use com.android.kotlin.multiplatform.library on AGP 9, not
// com.android.library. Dependency rule: ultra modules never depend on :app.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "ultra.core.model"
        compileSdk = 37
        minSdk = 26

        // Runs commonTest on the Android target too (plain JVM host test, no device or emulator).
        withHostTest { }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            // Flow/StateFlow appear in the port signatures, so they are part of the public API.
            api(ultraLibs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
