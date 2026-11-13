pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // spake2-java (dipendenza di Kadb) è pubblicata solo su JitPack
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Flyfish233") }
        }
    }
}

rootProject.name = "GestoreFile"
include(":app")
include(":shared")
include(":wear")
