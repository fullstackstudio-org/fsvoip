// SPDX-License-Identifier: AGPL-3.0-or-later
// The phone: accounts → SIP engine, every call through the system call framework (Telecom, self-managed). Compiles
// without the SIP stack: it only knows the `SipEngine` interface.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "nl.fullstackstudio.fsvoip.callcontroller"
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
    api(project(":sipengine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
