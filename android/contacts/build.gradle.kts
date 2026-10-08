// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.fullstackstudio.fsvoip.contacts"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.withType<Test>().configureEach {
    systemProperty("fsvoip.fixtures", rootProject.file("../shared/fixtures").absolutePath)
}

dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
