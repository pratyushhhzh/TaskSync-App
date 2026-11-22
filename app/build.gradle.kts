plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlinSerialization)
    id("com.google.devtools.ksp")
}

val workerBaseUrl = providers.gradleProperty("tasksync.workerBaseUrl").orNull
    ?: System.getenv("TASKSYNC_WORKER_BASE_URL")
    ?: "https://cloudflare-worker.work-spaceeeee2.workers.dev/"
val escapedWorkerBaseUrl = workerBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")
if (!workerBaseUrl.startsWith("https://", ignoreCase = true)) {
    throw GradleException("tasksync.workerBaseUrl must use HTTPS")
}

android {
    namespace = "com.example.tasksync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.tasksync"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "INTERPRETATION_BASE_URL", "\"$escapedWorkerBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "INTERPRETATION_BASE_URL", "\"$escapedWorkerBaseUrl\"")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
