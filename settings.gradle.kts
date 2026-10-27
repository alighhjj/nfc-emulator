pluginManagement {
    // 刻意不给 google() 加 content{} 白名单：
    // 一旦漏掉某个 group，CI 上会以"找不到插件"的形式失败，而本地又复现不了。
    // 依赖解析性能的收益远小于这个风险。
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

rootProject.name = "nfc-emulator"
include(":app")
include(":core-nfcc")
