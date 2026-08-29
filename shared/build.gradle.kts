plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
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

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmTest.dependencies {
            // Room KMP's MigrationTestHelper (test-only, androidx toolchain) for MigrationTest —
            // validates the Migration_1_2 table rebuild against real exported schemas. See
            // shared/src/jvmTest/.../core/database/MigrationTest.kt.
            implementation(libs.androidx.room.testing)
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

// Lint only hand-written sources. KSP registers its output directories into the Kotlin source sets,
// so without this ktlint also reads build/generated -- the Room DAO and database implementations --
// and reports tens of thousands of violations in machine output nobody can fix by editing.
//
// Matched against the absolute path rather than with an `include`/`exclude` pattern pair: those
// patterns are resolved relative to each source-set root (`src/commonMain/kotlin`, and separately
// `build/generated/ksp/...`), so a project-relative pattern like `src/**` matches nothing at all
// and silently filters out either everything or nothing.
ktlint {
    filter {
        exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

// Room-backed tests live in `shared/src/jvmTest/`, and that is now a structural rule rather than
// a convention (#81 §3).
//
// The reason has not changed: those tests build an in-memory AppDatabase, which works on the JVM
// target via Room.inMemoryDatabaseBuilder() plus the bundled SQLite driver with no platform handle
// required, and cannot work on the android unit-test target, where Room's builder needs a real
// android.content.Context that a host-JVM run against the stub android.jar has no way to supply.
//
// What changed is how that is enforced. This file used to carry 19 hand-maintained
// excludeTestsMatching(...) entries, so a Room-backed test could be *written* in commonTest -- where
// it would never run -- and the only thing catching it was whether the author remembered to come
// here and register it. Three were added during one phase alone, and an agent had to be told about
// the requirement explicitly or it would have shipped a red build.
//
// The list had also silently drifted, which is what a convention enforced by memory does. It
// excluded by package, so it swept up SqliteHeaderTest and ResolveWorkToEditionsUseCaseTest -- both
// pure Kotlin, neither touching Room. SqliteHeaderTest's own KDoc said it "runs on every test
// variant". It did not, and nothing reported that.
//
// Now testAppDatabase() is declared in jvmTest (see TestDatabaseBuilder.kt there) and is simply not
// visible from commonTest, so a Room-backed test in the wrong source set is a compile error instead
// of a green build that skipped it. There is nothing left here to keep in sync.
