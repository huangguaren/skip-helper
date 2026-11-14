pluginManagement {
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // repo.maven.apache.org 在本机不通，显式走可达镜像
        maven("https://maven.aliyun.com/repository/central")
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
    }
}
rootProject.name = "SkipHelper"
include(":app")
