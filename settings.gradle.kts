pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "deltalist"

include(":deltalist-core")
include(":deltalist-android-recyclerview")
include(":deltalist-android-compose")
include(":demo-core")
include(":demo-android")
