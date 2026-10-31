// Network facade: API-instance registry (Piped / Invidious / homelab), health-aware selection with
// failover, and HTTP client providers. Common code talks to Ktor's HttpClient; the OkHttp engine
// lives in `jvmCommon`, the source set shared by the Android and JVM targets.
//
// Versions come from upstream's `libs` catalog (this module adds no library of its own).

@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    android {
        namespace = "ultra.core.network"
        compileSdk = 37
        minSdk = 26

        withHostTest { }
    }
    jvm()

    // Android and JVM targets share JVM-only code (OkHttp) through one intermediate source set.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                // withAndroidTarget() only matches the legacy Android plugin; the AGP KMP library target
                // has to be selected by platform type, or this group silently excludes Android.
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
                withJvm()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ultra-core-resilience"))
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(ultraLibs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("jvmCommonMain").dependencies {
            api(libs.ktor.client.okhttp)
        }
    }
}
