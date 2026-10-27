plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ---------------------------------------------------------------------------
// core-nfcc 刻意做成「纯 Kotlin / 纯 JVM」模块：
//   1) 不依赖 Android SDK —— 因此可以在任何只有 JDK 17 的机器上跑单元测试；
//   2) 所有 NCI / NXP_CORE_CONF / UID 编解码逻辑都放这里，用 JUnit 死磕；
//   3) app 模块只做「拿结果去写文件 / 起服务」，不含算法。
//
// 这是本项目最重要的一条架构约束：算法必须可离线验证，不能只靠真机试。
// ---------------------------------------------------------------------------

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // 测试结果直出到 stdout，CI 日志里能直接看到哪条挂了
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
