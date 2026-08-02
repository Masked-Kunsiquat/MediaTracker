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
// SettingsRepositoryTest (ROADMAP Task 7 Phase A) is likewise Room-backed and excluded by package
// (com.hub.media.features.settings.*, mirroring com.hub.media.features.stats.*/
// com.hub.media.features.books.data.* above).
// SettingsViewModelTest (ROADMAP Task 7 Phase B) is excluded by exact class name for the same
// reason as StatsViewModelTest/EditBookViewModelTest above.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        filter {
            excludeTestsMatching("com.hub.media.core.database.*")
            excludeTestsMatching("com.hub.media.features.books.data.*")
            excludeTestsMatching("com.hub.media.features.books.domain.*")
            excludeTestsMatching("com.hub.media.features.stats.*")
            excludeTestsMatching("com.hub.media.features.settings.*")
            excludeTestsMatching("com.hub.media.ui.LibraryViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.BookDetailViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.StatsViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.EditBookViewModelTest*")
            excludeTestsMatching("com.hub.media.ui.SettingsViewModelTest*")
            isFailOnNoMatchingTests = false
        }
    }
}
