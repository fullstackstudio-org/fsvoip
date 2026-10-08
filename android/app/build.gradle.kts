// SPDX-License-Identifier: AGPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firebase is optional at build time. Without `app/google-services.json` (git-ignored) the app builds and runs without
// push: incoming calls then only ring while the app is open. With the file, the Google Services plugin turns it into
// resources and FCM works. See README.md.
val googleServicesFile = file("google-services.json")
val firebaseConfigured = googleServicesFile.exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
}

// Base URL of the FSVoip API. Production by default; a developer can point a debug build at a local mock with
// `-Pfsvoip.apiBaseUrl=http://127.0.0.1:8787/api/voip-app/v1` plus `adb reverse tcp:8787 tcp:8787` (never pair against
// production while testing; Android 17 blocks apps from the emulator host alias 10.0.2.2, see README.md).
val apiBaseUrl = (findProperty("fsvoip.apiBaseUrl") as String?) ?: "https://fullstackstudio.nl/api/voip-app/v1"

android {
    namespace = "nl.fullstackstudio.fsvoip"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "nl.fullstackstudio.fsvoip"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
    }

    // Release signing comes from a keystore that is never committed (see README.md). Without it a release build is
    // unsigned; debug builds use the standard debug key.
    val keystoreProperties = rootProject.file("keystore.properties")
    if (keystoreProperties.exists()) {
        val properties = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.exists()) {
                signingConfig = signingConfigs.getByName("release")
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

    packaging {
        jniLibs {
            // FSVoip never ships AMR or OpenH264 (patent/licence terms, see NOTICE). The audio-only linphone-sdk
            // variant does not contain them; this keeps it that way if a dependency ever brings them in.
            // CI checks the APK as well (.github/workflows/android.yml).
            excludes += listOf("**/libmsamr.so", "**/libmsopenh264.so", "**/libopenh264.so")
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sipengine"))
    implementation(project(":linphoneengine"))
    implementation(project(":pairing"))
    implementation(project(":callcontroller"))
    implementation(project(":contacts"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    // FCM. Always compiled in; only active when google-services.json was present at build time.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // QR scanner: ZXing (Apache-2.0), no Google ML Kit.
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded) { isTransitive = false }

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
