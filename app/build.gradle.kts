import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlinSerialization)
    id("com.google.devtools.ksp")
}

val localTaskSyncProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val debugBackendToken = localTaskSyncProperties.getProperty("tasksync.backendToken", "").trim()
val productionBackendUrl = providers.gradleProperty("tasksync.productionBackendUrl").orNull
    ?: System.getenv("TASKSYNC_PRODUCTION_BACKEND_URL")
    ?: localTaskSyncProperties.getProperty("tasksync.productionBackendUrl", "")
val escapedProductionBackendUrl = productionBackendUrl.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedDebugBackendToken = debugBackendToken.replace("\\", "\\\\").replace("\"", "\\\"")
if (productionBackendUrl.isNotBlank() && !productionBackendUrl.startsWith("https://", ignoreCase = true)) {
    throw GradleException("tasksync.productionBackendUrl must use HTTPS")
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
            buildConfigField("String", "INTERPRETATION_BASE_URL", "\"http://10.42.138.1:8000/\"")
            buildConfigField("String", "BACKEND_AUTH_TOKEN", "\"$escapedDebugBackendToken\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "INTERPRETATION_BASE_URL", "\"$escapedProductionBackendUrl\"")
            buildConfigField("String", "BACKEND_AUTH_TOKEN", "\"\"")
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
