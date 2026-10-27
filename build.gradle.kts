// 顶层构建文件。版本统一由 gradle/libs.versions.toml 管理，避免各模块漂移。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// 统一清理任务，CI 里用于排除增量缓存干扰
tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory.get().asFile)
    subprojects.forEach { sub ->
        delete(sub.layout.buildDirectory.get().asFile)
    }
}
