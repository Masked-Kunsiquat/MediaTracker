package com.hub.media.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Builds a throwaway in-memory [AppDatabase] wired with the bundled SQLite driver, for the tests
 * in this source set.
 *
 * ### Why this lives in `jvmTest` and not `commonTest`
 * It used to be an `expect` in `commonTest` with two `actual`s. The JVM one is the code below. The
 * android one could not work at all -- Room's android builder needs a real `android.content.Context`,
 * which `androidUnitTest` cannot supply, since it runs on the host JVM against the stub
 * `android.jar` with no Robolectric -- so it was a function whose entire body was `error(...)`,
 * existing only to make the source set compile.
 *
 * Keeping it visible from `commonTest` therefore meant any Room-backed test could be *written* in a
 * place where it could never *run*, and the only thing stopping that was a hand-maintained list of
 * 19 `excludeTestsMatching(...)` entries in `shared/build.gradle.kts` that every author had to
 * remember to extend. That list had already drifted: it excluded by package, which swept up
 * `SqliteHeaderTest` and `ResolveWorkToEditionsUseCaseTest` -- both pure Kotlin, neither touching
 * Room, and the former's own KDoc claimed it "runs on every test variant" while it silently did not.
 *
 * Moving this function here makes the rule structural instead (#81 §3). `commonTest` cannot see it,
 * so a Room-backed test placed there does not compile, and the exclusion list is gone entirely.
 */
internal fun testAppDatabase(coroutineContext: CoroutineContext = Dispatchers.Default): AppDatabase =
    Room
        .inMemoryDatabaseBuilder<AppDatabase>(factory = AppDatabaseConstructor::initialize)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(coroutineContext)
        .build()
