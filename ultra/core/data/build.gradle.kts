// Ultra's own persistence, kept completely apart from upstream's:
//   * ultra.db        - a Room KMP database (bundled SQLite driver) for analysis results and a TTL cache;
//                       it never touches upstream's Room schema, so upstream migrations cannot collide with ours.
//   * ultra.preferences_pb - a DataStore<Preferences> for ultra settings, separate from upstream's settings store.
// Room, Ktor and DataStore versions follow upstream's catalog (`libs`) or `ultraLibs` (see ultra.versions.toml).

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.google.devtools.ksp")
}

ksp {
    // Exported schema JSON is what future AutoMigrations and migration tests are written against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        // Room's database constructor is an `expect object`; Kotlin still flags that as beta. Room's docs
        // tell KMP users to enable this flag.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "ultra.core.data"
        compileSdk = 37
        minSdk = 26

        withHostTest { }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.room.runtime)
            api(ultraLibs.datastore.preferences.core)
            api(ultraLibs.coroutines.core)
            implementation(ultraLibs.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(ultraLibs.coroutines.test)
        }
    }
}

dependencies {
    // Room's processor runs once per compilation (KMP has no single "ksp" configuration).
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
