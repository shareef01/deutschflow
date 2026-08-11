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
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.aus.deutschflow"
    // 37 because androidx now requires it: core 1.19, lifecycle 2.11 and
    // hilt-navigation-compose 1.4 all refuse to be compiled against less.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aus.deutschflow"
        minSdk = 31
        // Deliberately behind compileSdk. Play requires new apps and updates to
        // target API 36 from 31 August 2026, and raising targetSdk opts the app into
        // behaviour changes that want testing on their own rather than arriving as a
        // side effect of a dependency bump. Lint's OldTargetApi warning is the price.
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
    // classpath, so whatever the app ends up with is forced onto the test one too.
    // Every constraint here exists because that forcing broke something.
    constraints {
        // Glance and androidx.test want different concurrent-futures and tracing
        // versions; hold both at the newer one.
        implementation(libs.androidx.concurrent.futures)
        implementation(libs.androidx.concurrent.futures.ktx)
        implementation(libs.androidx.tracing)
        // One coroutines version everywhere, so the app and coroutines-test agree.
        implementation(libs.kotlinx.coroutines.android)
        // lifecycle-viewmodel-savedstate pulls kotlinx-serialization 1.7.3, and
        // MigrationTestHelper in room-testing 2.8.4 throws AbstractMethodError on
        // FieldBundle$$serializer against it. Nothing in this app serialises anything;
        // the constraint exists purely so the schema files can be read back.
        implementation(libs.kotlinx.serialization.core)
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

    // No HTTP or JSON dependency for the AI client: HttpURLConnection and org.json
    // are both in the framework, so GroqHelper needs nothing that the deprecated
    // Gemini SDK used to drag in.

    testImplementation(libs.junit)
    // org.json is stubbed on the JVM unit test classpath and throws "not mocked",
    // so the real implementation is needed to test the response parsing.
    testImplementation(libs.org.json)
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
