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

// The Room KMP tests (DAO tests and Repository integration tests) build an in-memory AppDatabase.
// On the JVM target (:shared:jvmTest) that works via Room.inMemoryDatabaseBuilder() + the
// bundled SQLite driver with no platform handle required — that's the authoritative test run.
// On the android unit test target the equivalent android actual needs a real
// android.content.Context (Room reads it during build() for journal-mode resolution and
// multi-instance invalidation), which local androidUnitTest tasks cannot supply since they run
// on the host JVM against Android's stub android.jar with no Robolectric wired into this
// project (see TestDatabaseBuilder.android.kt). Excluding these packages here — rather than
// weakening the tests themselves — keeps `testDebugUnitTest`/`testReleaseUnitTest` green while
// `:shared:jvmTest` remains the real gate for the data layer.
// LibraryViewModelTest and BookDetailViewModelTest build a real AppDatabase via testAppDatabase()
// (see above) to verify the Flow -> StateFlow wiring against actual DAO behavior, so they hit the
// same android.content.Context gap as the DAO/repository tests and are excluded here by exact
// class name rather than by package: AddBookViewModelTest lives in the same com.hub.media.ui
// package but only depends on a hand-rolled BookIngestionUseCase fake (no Room), so it stays
// runnable on the android unit-test variant instead of being swept up by a package-wide exclusion.
// StatsRepositoryTest (ROADMAP Task 5) is likewise Room-backed and excluded by package
// (com.hub.media.features.stats.*, mirroring com.hub.media.features.books.data.*); StatsDaoTest
// lives under com.hub.media.core.database and is already covered by that package's exclusion.
// StatsViewModelTest and EditBookViewModelTest (ROADMAP Task 6 Phase A) are excluded by exact
// class name for the same reason as LibraryViewModelTest/BookDetailViewModelTest above.
// BackfillViewModelTest (ROADMAP Task 14 Phase A PR review) is likewise Room-backed (a real
// AppDatabase, to exercise BackfillViewModel/BulkBackfillUseCase races against genuine async DB
// reads rather than a fake) and excluded by exact class name for the same reason.
// SettingsRepositoryTest (ROADMAP Task 7 Phase A) is likewise Room-backed and excluded by package
// (com.hub.media.features.settings.*, mirroring com.hub.media.features.stats.*/
// com.hub.media.features.books.data.* above).
// SettingsViewModelTest (ROADMAP Task 7 Phase B) is excluded by exact class name for the same
// reason as StatsViewModelTest/EditBookViewModelTest above.
// ExportDataUseCaseTest (ROADMAP Task 8 Phase A) is likewise Room-backed (builds a real
// AppDatabase to exercise BookRepository/ReadingSessionRepository end to end) and excluded by
// package (com.hub.media.features.portability.domain.*, mirroring
// com.hub.media.features.books.domain.* above). CsvUtilTest/LibraryCsvExporterTest/
// ReadingLogCsvExporterTest live under com.hub.media.features.portability.csv -- pure Kotlin, no
// Room, so that package is deliberately NOT excluded and keeps running on every variant.
// MovieRepositoryTest (ROADMAP Task 13 Phase B) is Room-backed for the same reason
// BookRepositoryTest is -- it builds a real AppDatabase via testAppDatabase() -- so
// com.hub.media.features.movies.data.* is excluded by package, mirroring
// com.hub.media.features.books.data.* above. Without this the android unit-test variants would
// try to run it against the stub android.jar with no Robolectric and fail.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        filter {
            excludeTestsMatching("com.hub.media.core.database.*")
            excludeTestsMatching("com.hub.media.features.books.data.*")
            excludeTestsMatching("com.hub.media.features.movies.data.*")
            excludeTestsMatching("com.hub.media.features.books.domain.*")
            excludeTestsMatching("com.hub.media.features.stats.*")
            excludeTestsMatching("com.hub.media.features.settings.*")
            excludeTestsMatching("com.hub.media.features.portability.domain.*")
            excludeTestsMatching("com.hub.media.features.media.domain.DeleteMediaUseCaseTest*")
            excludeTestsMatching("com.hub.media.ui.LibraryViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.BookDetailViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.BackfillViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.StatsViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.EditBookViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.SettingsViewModelTest*")
            isFailOnNoMatchingTests = false
        }
    }
}
