// Pure DSP math: decibel helpers, biquad filters (RBJ cookbook), windows, radix-2 FFT, and ITU-R
// BS.1770-4 K-weighting + EBU R128 loudness measurement. No dependencies besides the Kotlin stdlib:
// no Android, no coroutines, no allocation on the per-sample paths.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "ultra.core.dsp"
        compileSdk = 37
        minSdk = 26

        withHostTest { }
    }
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
