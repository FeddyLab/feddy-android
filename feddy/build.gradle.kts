import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

/** Reported as `sdk_version` in every message's context. */
val sdkVersion = "0.1.1"

android {
    namespace = "app.feddy.sdk"
    // Deliberately 35: the AAR metadata check then refuses any dependency
    // that would force host apps onto a newer compileSdk.
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    explicitApi()
    // The stdlib the POM asks for, not the compiler's own (2.4.x), which a
    // host on Kotlin 2.0-2.2 could not read. 2.1.20 is what Compose UI
    // already requires, so this raises nothing.
    coreLibrariesVersion = "2.1.20"
    compilerOptions {
        // Host apps (Flutter ones especially) often build with an older
        // Kotlin. Metadata written for 2.1 stays readable from 2.0 up.
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
    }
}

// Credentials and the signing key live in ~/.gradle/gradle.properties
// (mavenCentralUsername, mavenCentralPassword, signingInMemoryKey...), never
// in this repository. Release: ./gradlew :feddy:publishAndReleaseToMavenCentral
mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    coordinates("app.feddy", "feddy", sdkVersion)
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("Feddy")
        description.set("In-app support for Android apps: users write in, you reply from the Feddy dashboard, the reply reaches them in your app.")
        inceptionYear.set("2026")
        url.set("https://github.com/FeddyLab/feddy-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("feddylab")
                name.set("Feddy")
                email.set("admin@feddy.app")
                url.set("https://feddy.app")
            }
        }
        scm {
            url.set("https://github.com/FeddyLab/feddy-android")
            connection.set("scm:git:https://github.com/FeddyLab/feddy-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/FeddyLab/feddy-android.git")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.coroutines.test)
}
