// 顶层构建文件。版本统一由 gradle/libs.versions.toml 管理，避免各模块漂移。
//
// 刻意保持极简：本项目第一次构建只能在 CI 上跑，本机没有 Android SDK 无从验证。
// 每多一行写在配置阶段会执行的代码，就多一分"所有任务集体失败"的风险。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
