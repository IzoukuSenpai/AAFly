// AAFly — plugin Paper de vol temporisé. Developped by AzurHOSTS.com and powered by AzurWare.fr.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        maven("https://repo.extendedclip.com/releases/") {
            name = "extendedclip"
        }
    }
}

rootProject.name = "AAFly"
