plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    androidTarget()
    jvm()

    sourceSets.all {
        // kotlin.time.Instant and kotlin.uuid.Uuid are still marked experimental in Kotlin 2.2.x
        // but are the standard KMP-safe APIs recommended for timestamps / UUID primary keys
        // (see AGENTS.md §3.1 and the Room KMP migration notes). Opting in module-wide avoids
        // sprinkling @OptIn across every entity/DAO/converter file that touches them.
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}

android {
    namespace = "com.hub.media.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

// The Room KMP DAO tests under com.hub.media.core.database build an in-memory AppDatabase.
// On the JVM target (:shared:jvmTest) that works via Room.inMemoryDatabaseBuilder() + the
// bundled SQLite driver with no platform handle required — that's the authoritative test run.
// On the android unit test target the equivalent android actual needs a real
// android.content.Context (Room reads it during build() for journal-mode resolution and
// multi-instance invalidation), which local androidUnitTest tasks cannot supply since they run
// on the host JVM against Android's stub android.jar with no Robolectric wired into this
// project (see TestDatabaseBuilder.android.kt). Excluding the package here — rather than
// weakening the tests themselves — keeps `testDebugUnitTest`/`testReleaseUnitTest` green while
// `:shared:jvmTest` remains the real gate for this DAO layer.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        filter {
            excludeTestsMatching("com.hub.media.core.database.*")
            isFailOnNoMatchingTests = false
        }
    }
}
