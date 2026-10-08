// SPDX-License-Identifier: AGPL-3.0-or-later
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Belledonne's official Maven repository of linphone-sdk (only `org.linphone*` is taken from it).
        maven("https://download.linphone.org/maven_repository") {
            content { includeGroupByRegex("org\\.linphone.*") }
        }
    }
}

rootProject.name = "FSVoip"

include(":core", ":sipengine", ":linphoneengine", ":pairing", ":callcontroller", ":contacts", ":app")
