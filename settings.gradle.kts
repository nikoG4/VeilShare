rootProject.name = "veilshare"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

include(":shared:core-model", ":shared:core-crypto", ":shared:core-vault", ":shared:core-identity")
include(":shared:core-transfer", ":shared:core-contacts", ":shared:core-platform")
include(":shared:ui-design", ":shared:ui-features", ":shared:app")
include(":desktopApp", ":server:signaling")
