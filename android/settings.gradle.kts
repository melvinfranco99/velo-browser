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
        // GeckoView: el motor de Firefox (el mismo que usan Tor Browser y Mullvad Browser en Android)
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
        // tor-android (Tor oficial compilado para Android por The Guardian Project, autores de Orbot)
        maven("https://raw.githubusercontent.com/guardianproject/gpmaven/master") {
            content { includeGroup("info.guardianproject") }
        }
    }
}
rootProject.name = "Velo"
include(":app")
