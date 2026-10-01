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
    }
}
rootProject.name = "RegistratoreAI"
include(":core")
// Le build desktop (macOS/Windows) possono saltare il modulo Android
if (System.getenv("SKIP_ANDROID") == null) include(":app")
include(":desktop")
