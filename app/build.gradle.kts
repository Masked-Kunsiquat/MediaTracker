plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.github.maskedkunisquat.mediatracker"
    compileSdk {
        version = release(36) {
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
        val (vMajor, vMinor, vPatch) = libs.versions.app.get().split(".").map { it.toInt() }
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
                "proguard-rules.pro"
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}