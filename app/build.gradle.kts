import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Release signing is opt-in and never committed.
 *
 * Drop a keystore.properties next to settings.gradle.kts with storeFile,
 * storePassword, keyAlias and keyPassword; both it and *.jks are gitignored. When it
 * is absent - a fresh clone, or a CI job that only needs to prove the build compiles
 * - the release build stays unsigned rather than failing.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.aus.deutschflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aus.deutschflow"
        minSdk = 31
        // Play requires new apps and updates to target API 36 from 31 August 2026.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null when there is no keystore.properties, which leaves the output
            // unsigned instead of breaking the build for anyone without the key.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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
        buildConfig = true
    }

    sourceSets {
        // MigrationTestHelper reads the exported schemas from the test APK's assets.
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

ksp {
    // Export Room schemas so future versions can be migrated instead of dropped,
    // and so migrations can be tested against the real historical schema.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AGP resolves the androidTest classpath consistently with the app's runtime
    // classpath, so anything the app pins is forced onto Espresso too. These two
    // pins are what let instrumented tests resolve at all.
    constraints {
        // Glance drags concurrent-futures up to a 1.2.0 alpha; Espresso needs stable 1.2.0.
        implementation(libs.androidx.concurrent.futures)
        implementation(libs.androidx.concurrent.futures.ktx)
        // The app otherwise holds tracing at 1.0.0; every androidx.test release needs 1.1.0.
        implementation(libs.androidx.tracing)
        // Dropping kotlinx-coroutines-play-services along with ML Kit let the app's
        // runtime coroutines fall back to a strict 1.7.3, which then collides with the
        // 1.8.1 that coroutines-test needs. Hold the app where it already sat.
        implementation(libs.kotlinx.coroutines.android)
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.m3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Supplies androidx.lifecycle.compose.LocalLifecycleOwner, which the recording
    // screens use to release the microphone when they are backgrounded.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Window size classes: drives the compact/expanded navigation switch
    implementation(libs.androidx.compose.material3.window.size)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Glance Widget
    implementation(libs.glance.appwidget)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager: what actually makes the daily word daily
    implementation(libs.androidx.work.runtime.ktx)

    // Gemini AI
    implementation(libs.google.generativeai)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // MigrationTestHelper, which reads the schemas exported to app/schemas.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
