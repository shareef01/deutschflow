plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.aus.deutschflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aus.deutschflow"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
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

    // ML Kit
    implementation(libs.mlkit.translate)
    implementation(libs.kotlinx.coroutines.play.services)

    // Gemini AI
    implementation(libs.google.generativeai)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
