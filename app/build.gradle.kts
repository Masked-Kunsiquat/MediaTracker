import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.support.serviceOf
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.roborazzi)
}

// Lint only hand-written sources -- see the same block in shared/build.gradle.kts for why this is
// a path match rather than an include/exclude pattern.
ktlint {
    filter {
        exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
    }
}

android {
    namespace = "com.github.maskedkunisquat.mediatracker"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = "com.github.maskedkunisquat.mediatracker"
        minSdk = 28
        targetSdk = 36

        // Version comes from [versions] app in gradle/libs.versions.toml (AGENTS.md §8).
        // versionCode must only ever increase across installs, so it is derived from
        // SemVer rather than hand-edited: major*10000 + minor*100 + patch.
        val (vMajor, vMinor, vPatch) =
            libs.versions.app
                .get()
                .split(".")
                .map { it.toInt() }
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = libs.versions.app.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Debug and release are separate installed apps, not two builds of one.
            //
            // Android identifies an app by applicationId, so without this suffix the debug and
            // release builds are the same app -- signed with different keys. Installing either over
            // the other forces an uninstall first, which wipes the database and every downloaded
            // cover. That is not hypothetical: it happened during development, taking a real
            // library with it, and it would happen again the first time `installDebug` ran against
            // a release build installed as the daily driver.
            //
            // With the suffix they coexist: separate applicationId, separate data directories,
            // separate icons. `installDebug` and `connectedDebugAndroidTest` then physically cannot
            // reach the release app's data whatever keys are involved -- which also makes running
            // instrumented tests on a real phone safe, since those execute against the real
            // installed app.
            //
            // Note this narrows the signing hazard rather than removing it: re-signing the
            // *release* app with a different key still forces a wipe (ROADMAP Task 16).
            applicationIdSuffix = ".debug"
            // The label is overridden in src/debug/res rather than with resValue(), which would
            // collide with the app_name already declared in src/main/res.
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // ROADMAP Task 15: MediaTrackerApplication.onCreate reads BuildConfig.DEBUG to pick the
        // logging verbosity threshold (shared/ cannot see BuildConfig itself -- see AppLogger's
        // KDoc). AGP 8+ requires this explicit opt-in; it was not previously needed by anything else
        // in this module.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric reads the *merged* manifest and the merged resources, so without this it
            // starts against an empty package: no theme, no strings, and no `ComponentActivity`
            // entry -- which `createComposeRule()` needs to launch a host for the composition. That
            // entry arrives from `debugImplementation(ui-test-manifest)` below, already present for
            // the instrumented suite; this flag is what lets the unit-test lane see it too.
            isIncludeAndroidResources = true
        }
    }
}

// Make the committed goldens an input of the unit-test task (#102).
//
// Without this the screenshot gate silently does not gate. Roborazzi reads
// `src/test/screenshots/` at runtime, so Gradle knows nothing about those PNGs: replace one with a
// completely different image, run `verifyRoborazziDebug`, and both it and `testDebugUnitTest`
// report UP-TO-DATE and the build succeeds. Verified by doing exactly that -- a stats golden
// overwritten with the library screenshot passed in 10 seconds, and failed only under
// `--rerun-tasks`.
//
// That matters most in the place it is hardest to notice. CI restores a Gradle cache, so a PR whose
// only change is a re-recorded golden is precisely the change that would find the task up to date
// and verify nothing -- and re-recording by reflex is the failure mode #102 exists to prevent.
//
// Declared as an input rather than an output even though `recordRoborazziDebug` writes here: after a
// recording the task is simply out of date and reruns, which is correct and cheap.
tasks.withType<Test>().configureEach {
    inputs
        .dir(layout.projectDirectory.dir("src/test/screenshots"))
        .withPropertyName("roborazziGoldens")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * ROADMAP Task 15 Phase B2b: copies the root CHANGELOG.md into the app's assets so the in-app
 * "What's new" viewer can read it via `context.assets`.
 *
 * A build-time copy rather than a second checked-in file: the root CHANGELOG.md stays the single
 * source of truth (AGENTS.md section 8's changelog discipline governs exactly one file), and
 * because the destination is gitignored, a stale duplicate cannot be committed even by accident.
 * No new dependency -- this is a plain Copy task.
 *
 * Wired into preBuild rather than a specific variant's asset-merge task so it runs before any
 * merge, for every variant, without naming variant-specific task names that change between AGP
 * versions.
 */
val copyChangelogToAssets by tasks.registering(Copy::class) {
    from(rootProject.file("CHANGELOG.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(copyChangelogToAssets)
}

/**
 * Copies the sample-data CSVs into the *androidTest* assets so `SampleDataSeedTest` can import them
 * into the debug app (ROADMAP Task 14 Phase B).
 *
 * A build-time copy for the same reason the changelog is one: `docs/sample-data/` stays the single
 * source of truth, the copy is gitignored, and a stale duplicate cannot be committed. Also means
 * the fixture is verified by `SampleLibraryCsvTest` against the real parser in one place rather
 * than drifting between two.
 */
val copySampleDataToTestAssets by tasks.registering(Sync::class) {
    from(rootProject.file("docs/sample-data")) {
        include("*.csv")
    }
    into(layout.projectDirectory.dir("src/androidTest/assets"))
}

tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach {
    dependsOn(copySampleDataToTestAssets)
}

/**
 * Installs the debug app and its test APK, then seeds the device with the sample library.
 *
 * Exists because `connectedDebugAndroidTest` **uninstalls the app when it finishes**, so every run
 * of the instrumented suite leaves you with no app to look at -- which is precisely when you most
 * want one, to check by hand whatever the tests just claimed. Driving the seed test directly
 * against already-installed APKs skips Gradle's teardown; this wraps the two commands that does.
 *
 * Idempotent: the import uses SKIP, so re-running tops the library back up rather than duplicating
 * it.
 */
val seedDebugDevice by tasks.registering {
    group = "install"
    description =
        "Installs the debug app and seeds it with docs/sample-data (run after connectedDebugAndroidTest)."
    dependsOn("installDebug", "installDebugAndroidTest")

    val adbPath =
        android.sdkDirectory
            .resolve(
                if (System.getProperty("os.name").startsWith("Windows")) {
                    "platform-tools/adb.exe"
                } else {
                    "platform-tools/adb"
                },
            ).absolutePath
    // Derived rather than written out, so renaming the applicationId or changing the debug suffix
    // cannot leave this pointing at a package that no longer exists.
    val testPackage = "${android.defaultConfig.applicationId}.debug.test"
    val execOps = project.serviceOf<org.gradle.process.ExecOperations>()

    doLast {
        val output = ByteArrayOutputStream()
        execOps.exec {
            commandLine(
                adbPath,
                "shell",
                "am",
                "instrument",
                "-w",
                "-e",
                "class",
                "com.github.maskedkunisquat.mediatracker.SampleDataSeedTest",
                "$testPackage/androidx.test.runner.AndroidJUnitRunner",
            )
            standardOutput = output
            errorOutput = output
        }
        val text = output.toString()
        println(text)
        // `am instrument` exits 0 even when the test fails, so the exit code proves nothing -- this
        // task would silently claim success on an empty library without checking the output itself.
        //
        // The count matters as well as the "OK": a filter matching no test at all reports
        // `OK (0 tests)`, which passes a naive substring check. That is the likeliest failure here,
        // since the class name is a string that a rename would silently invalidate -- and the task
        // would then cheerfully announce a seeded device having run nothing.
        val ranCount =
            Regex("""OK \((\d+) test""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 0
        check(ranCount > 0 && "FAILURES!!!" !in text) {
            "Seeding failed -- the device was not populated (tests run: $ranCount). Output above."
        }
        println("Seeded. Open \"MediaTracker Debug\" to see the sample library.")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // The gated Compose lane (see AGENTS.md section 7). Robolectric gives `:app` unit tests a real
    // view hierarchy, which is what makes layout assertions -- not just semantics ones -- possible
    // off-device and therefore possible in CI.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Screenshot goldens (#102), on the same Robolectric runtime as the lane above so they share
    // robolectric.properties rather than defining a second, drifting notion of "the test device".
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
