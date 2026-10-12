// SPDX-License-Identifier: AGPL-3.0-or-later
// 🚨 The ONLY module that may depend on linphone-sdk (`org.linphone.*`), enforced by scripts/check-imports.sh.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.fullstackstudio.fsvoip.linphoneengine"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":sipengine"))
    // linphone-sdk, audio-only variant (AGPL-3.0, see NOTICE). It ships no AMR or OpenH264 plugin; the app module
    // additionally refuses to package them.
    implementation(libs.linphone)
    testImplementation(libs.junit)
}
