// Pure-Kotlin resilience primitives (backoff, retry, circuit breaker, bulkhead, health registry).
// No Android, no DI annotations, no HTTP: everything is injected through constructors, and time and
// randomness are parameters so tests are deterministic.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    // The breaker's lock-free state machine uses kotlin.concurrent.atomics. Opted in once here,
    // and none of its types appear in this module's public API.
    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    android {
        namespace = "ultra.core.resilience"
        compileSdk = 37
        minSdk = 26

        withHostTest { }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(ultraLibs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(ultraLibs.coroutines.test)
        }
    }
}
