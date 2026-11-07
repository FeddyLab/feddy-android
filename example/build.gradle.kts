import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional per-developer overrides; the committed defaults are placeholders.
val local = Properties().apply {
    val file = project.file("feddy.local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.feddy.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.feddy.example"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "FEDDY_PROJECT_ID", "\"${local.getProperty("projectId", "fd_replace_me")}\"")
        buildConfigField("String", "FEDDY_API_URL", "\"${local.getProperty("apiUrl", "https://core.feddy.app")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":feddy"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}
