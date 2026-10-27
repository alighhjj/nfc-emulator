plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.nfcemu"
    // 用 34 而不是 35：GitHub runner 的 SDK 镜像一定带 android-34，
    // 35 要看镜像版本，缺了会让 AGP 走自动下载，多一处可能在 CI 上失败的地方。
    // 本项目没有任何 API 35 的诉求，targetSdk 也停在 34。
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.nfcemu"
        minSdk = 24
        // targetSdk 34 而不是 35：35 起前台服务类型强制校验 + 部分 OEM 的 NfcService
        // 在 35 上限制了 /vendor/etc 的读取。等 M5 再评估升级。
        targetSdk = 34

        // CI 用 -PversionCode=.. -PversionName=.. 覆盖
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // ------------------------------------------------------------------
            // M0 阶段刻意关闭 minify：
            //   本仓库的第一次构建必须一次性变绿（没有本地环境可反复试错）。
            //   R8 + Compose + kotlinx.serialization 的 keep 规则等 M2 拿到第一份
            //   真机日志之后再打开，届时用 -printusage 检查有无误删。
            // ------------------------------------------------------------------
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 注意：这里**不配置** signingConfig。
            //   AGP 的 signingConfig 只能同时给出 v1/v2/v3，无法给出 v4 (idsig)，
            //   而且 keystore 得进 Gradle 配置。CI 里改用 apksigner 手动签，
            //   一次拿齐 v1+v2+v3+v4，见 .github/workflows/build.yml。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    lint {
        // ------------------------------------------------------------------
        // M0 阶段**不**阻断构建。
        // 原因很现实：本机没有 Android SDK，lint 的第一次运行结果只能在 CI 里看到，
        // 而 CI 每轮都要人推一次代码。先让流水线变绿、把报告看一遍、逐个收敛，
        // 再把 abortOnError 改回 true 当成质量闸门。
        // ------------------------------------------------------------------
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf(
            // 我们确实需要读取 /system/etc 与 /vendor/etc 下的 NFC 配置
            "ProtectedPermissions",
            "DiscouragedPrivateApi",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 统一用 toolchain 决定 JVM 目标：
//   jvmToolchain(17) 会同时作用于 Java 与 Kotlin 编译，避免出现
//   "Inconsistent JVM-target compatibility (javac 17 vs kotlinc 1.8)" 这类构建失败。
// 刻意不用 kotlin { compilerOptions { } } 这个新 DSL —— 它在 KGP 2.0 的 Android 扩展上
// 是否可用我没法在本机验证，而 jvmToolchain 是各版本都稳的那一个。
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-nfcc"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
