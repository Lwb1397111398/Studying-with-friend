// 阿里云镜像只给本机（国内网络加速）用；GitHub CI 在海外，访问阿里云会 502，直连官方源
val onCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"

pluginManagement {
    repositories {
        if (!onCi) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!onCi) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "studying-with-friend"
include(":app")
