plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "xyz.yuuki.novafin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "xyz.yuuki.novafin"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Media3's offline/cache/cast APIs are all marked unstable; we use them deliberately.
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.cast)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.navigation3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; the real one runs the parsers.
    testImplementation(libs.json)
}
