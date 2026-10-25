pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories {
        google()
        mavenCentral()
        maven { url = uri("https://xdcobra.github.io/maven/") }
    } }
rootProject.name = "VoiceNotesAndroid"
include(":app")
