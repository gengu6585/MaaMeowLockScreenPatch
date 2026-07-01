pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LSPosed 官方仓库
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "MaaMeowLockScreenPatch"
include(":app")
