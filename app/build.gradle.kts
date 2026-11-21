plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ackstorm.walky"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.ackstorm.walky"
        minSdk = 31
        targetSdk = 36
        // The release workflow sets both from the tag and the run number.
        versionCode = providers.gradleProperty("versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("versionName").getOrElse("0.0.0-dev")
    }
    // The release workflow provides the password, so every release has the same signature and
    // installs over the previous one. Without it, builds use the local debug key.
    val keystorePassword = providers.environmentVariable("WALKY_KEYSTORE_PASSWORD").orNull
    if (keystorePassword != null) {
        val walky = signingConfigs.create("walky") {
            storeFile = rootProject.file("release.keystore")
            storePassword = keystorePassword
            keyAlias = "walky"
            keyPassword = keystorePassword
        }
        buildTypes.getByName("debug").signingConfig = walky
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
