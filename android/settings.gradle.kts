// 阿里云镜像只给本机（国内网络加速）用；GitHub CI 在海外，访问阿里云会 502，直连官方源。
// 条件必须内联：pluginManagement 块先于脚本体执行，读不到文件级变量
pluginManagement {
    repositories {
        if (System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
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
        if (System.getenv("CI") != "true" && System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "studying-with-friend"
include(":app")
