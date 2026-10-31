// :ultra-feature-dsp - Smart DJ transition engine, plugged into seam S6 (AudioProcessorsContributor).
//
// What it is: a per-sink AudioProcessor that, while upstream's two-player crossfade overlaps two
// tracks, shapes each side of the overlap (bass swap, echo-out) in Float32 and keeps the result
// below a soft ceiling. Outside an overlap it is a bit-exact pass-through.
// What it is not: it does not start crossfades (upstream's scheduler does) and it does not change the
// audio sink's output encoding; see DjDeckAudioProcessor for why.
//
// Dependency rule (same as every ultra module): never depend on :app. The one thing it needs from
// upstream (the crossfade settings) is the CrossfadePort interface, implemented by the :app glue and
// bound with @BindsOptionalOf so this module stays independent.
plugins {
    id("com.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ultra.feature.dsp"
    compileSdk = 37

    // Resources share the app's merged namespace (android.nonTransitiveRClass=false).
    resourcePrefix = "ultra_dj_"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // android.util.Log / AudioDeviceInfo constants are touched by pure logic; stub them on the JVM.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":ultra-hooks"))
    // Biquads. Pure Kotlin (KMP), no natives: adds nothing to the APK but its own classes.
    implementation(project(":ultra-core-dsp"))

    implementation(ultraLibs.androidx.annotation)
    implementation(ultraLibs.coroutines.core)
    // Same artifact and version as :app. A *separate file* (ultra_dj.preferences_pb) is used, so
    // upstream's "settings" store is never touched.
    implementation(libs.datastore)
    implementation(libs.media3)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.material3)

    testImplementation(libs.junit)
    testImplementation(ultraLibs.coroutines.test)
    // media3-exoplayer (same version as :app). media3-common is declared too because the processor test
    // reproduces the sink's chain, whose last stage (SonicAudioProcessor) lives in -common; exoplayer
    // pulls it in at runtime but not onto the compile classpath.
    testImplementation(libs.media3)
    testImplementation("androidx.media3:media3-common:${libs.versions.media3.get()}")
}
