pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Dream Linux"
include(":app")
include(":lorie")
include(":shell-loader")
include(":shell-loader:stub")
project(":lorie").projectDir = file("third_party/termux-x11/lorie")
project(":shell-loader").projectDir = file("third_party/empty-shell-loader")
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")
