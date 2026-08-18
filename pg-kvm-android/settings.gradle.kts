pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        jcenter()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        jcenter()
        maven(url = "https://jitpack.io")
        maven(url = "https://plugins.gradle.org/m2/")
    }
}
rootProject.name = "webrtc-sample-compose"
include(":app")
include(":libausbc")
include(":libuvc")
include(":libnative")
