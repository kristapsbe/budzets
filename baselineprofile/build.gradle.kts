plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

/**
 * Generates the app's Baseline Profile and measures its start-up. Runs on a connected device or
 * emulator (API 33+, or rooted): `./gradlew :app:generateBaselineProfile` writes the profile
 * into `app/src/release/generated/baselineProfiles/`; see README.md.
 */
android {
    namespace = "lv.kristapsbe.budzets.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulator timings are only good for comparing runs with each other.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
