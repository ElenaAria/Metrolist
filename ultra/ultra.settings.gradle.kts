/*
 * Ultra layer: settings seam (S1).
 * Applied from the last line of the root settings.gradle.kts.
 *
 * Register each module below by its path under ultra/. The project name is "ultra-" plus the
 * path with "/" replaced by "-" (ultra/core/model -> :ultra-core-model), so a name can never
 * collide with an upstream module.
 *
 * The list is explicit on purpose. A directory scan is invisible to the configuration cache
 * (enabled in gradle.properties), so a newly added module would be silently ignored by
 * command-line builds until the cache happened to be invalidated.
 */

val ultraModules = listOf(
    "hooks",   // seam contracts + UltraHooks registry (no-op defaults); depends on nothing ultra
    "wiring",  // Hilt multibindings + androidx.startup initializer that fill UltraHooks
    "core/model",       // KMP: domain types + ports (PlaybackEnginePort, EqPort, LibraryPort)
    "core/resilience",  // KMP: backoff + jitter, retry, circuit breaker, bulkhead, health registry
    "core/dsp",         // KMP: dB math, biquads, windows, FFT, K-weighting, BS.1770 loudness meter
    "core/network",     // KMP: instance registry + failover over resilience, HttpProfile/HttpClientProvider (OkHttp in jvmCommon)
    "core/data",        // KMP: Room ultra.db (bundled SQLite) + DataStore ultra.preferences_pb, isolated from upstream's
    "feature/dsp",      // Android: Smart DJ transition engine + device-aware profiles, plugged into seam S6 (and S8/S9 for its screen)
    // later: "feature/oled", ...
)

ultraModules.forEach { path ->
    val moduleDir = settingsDir.resolve("ultra/$path")
    require(moduleDir.resolve("build.gradle.kts").isFile) {
        "Ultra module '$path' is registered in ultra.settings.gradle.kts but $moduleDir has no build.gradle.kts"
    }
    val projectName = "ultra-" + path.replace('/', '-')
    include(":$projectName")
    project(":$projectName").projectDir = moduleDir
}

// One command for the tests of every core module: ./gradlew ultraCoreTest
// KMP modules run on both the JVM and the Android host target (`allTests`). :ultra-core-data only has
// JVM tests: on Android, Room needs a real Context, which host tests don't have.
gradle.rootProject {
    tasks.register("ultraCoreTest") {
        group = "verification"
        description = "Runs the tests of every ultra core module."
        dependsOn(
            ":ultra-core-model:allTests",
            ":ultra-core-resilience:allTests",
            ":ultra-core-dsp:allTests",
            ":ultra-core-network:allTests",
            ":ultra-core-data:jvmTest",
        )
    }

    // The feature modules are Android libraries, so their tests are plain JVM unit tests.
    tasks.register("ultraFeatureTest") {
        group = "verification"
        description = "Runs the tests of every ultra feature module."
        dependsOn(":ultra-feature-dsp:testDebugUnitTest")
    }
}

// Ultra-only version catalog (accessor `ultraLibs`), kept apart from upstream's `libs`.
dependencyResolutionManagement {
    versionCatalogs {
        create("ultraLibs") {
            // files() in an applied script resolves against the script's directory, so anchor explicitly.
            from(files(settingsDir.resolve("ultra/gradle/ultra.versions.toml")))
        }
    }
}
