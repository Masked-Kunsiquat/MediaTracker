plugins {
    alias(libs.plugins.android.application)
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
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}