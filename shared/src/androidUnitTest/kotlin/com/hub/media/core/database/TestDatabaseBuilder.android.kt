package com.hub.media.core.database

import androidx.room.RoomDatabase

/**
 * Room's android `Room.inMemoryDatabaseBuilder` requires a real `android.content.Context`
 * (it's read during `build()` for journal-mode resolution and multi-instance invalidation
 * wiring). `androidUnitTest` runs on the plain host JVM against Android's stub `android.jar`
 * (no Robolectric is wired into this project — see AGENTS.md §5 "No Unnecessary
 * Dependencies" and the project's Sub-Task B scope, which forbids adding new third-party
 * deps), so no real `Context` is obtainable here and this actual can never be exercised.
 *
 * The DAO tests that call [testAppDatabase] are excluded from the `testDebugUnitTest` /
 * `testReleaseUnitTest` Gradle tasks (see the `tasks.withType<Test>` filter in
 * shared/build.gradle.kts) precisely so this function is never invoked. It still needs to
 * exist and type-check so the androidUnitTest source set compiles at all — the `expect`
 * declaration in commonTest requires an `actual` for every compiled target.
 *
 * The authoritative DAO test run is `:shared:jvmTest`, which uses a real in-memory database
 * via the bundled SQLite driver with no platform Context requirement.
 */
internal actual fun inMemoryAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    error(
        "androidUnitTest cannot build a real Room database: android.content.Context is " +
            "unavailable on the host JVM without Robolectric. These DAO tests are excluded " +
            "from the android unit test task — run `:shared:jvmTest` instead.",
    )
}
