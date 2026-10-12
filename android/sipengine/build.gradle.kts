// SPDX-License-Identifier: AGPL-3.0-or-later
// The SIP engine boundary: our own types and the `SipEngine` interface. No dependencies at all (plan D3).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.fullstackstudio.fsvoip.sipengine"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation(libs.junit)
}
