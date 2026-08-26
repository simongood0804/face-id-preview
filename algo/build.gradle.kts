plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skyworth.faceid.algo"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        // face-sdk native so 仅含 arm64（与 app 保持一致）
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // algo 为纯算法/逻辑库：不依赖 EvsSDK / AOSP 系统类（无 useLibrary("android.car")、
    // 无 xbootclasspath），可脱离系统编译环境独立发布 AAR。
    // 测试用纯 JUnit（无 Android 类），无需 Robolectric。
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

// 方案 B（FACEP-014）：face-sdk 由**最终消费方自行提供**，algo 不打包、不传递它。
// face-sdk 用 compileOnly（仅编译期需要其类定义），产物 AAR 不含 face-sdk；
// 原因：AGP 禁止 library 用 api/implementation 依赖 aar 再打包 AAR（产物破损，
// face-sdk 的 classes 不会进 AAR）。坐标来自本地 maven 仓库（~/.m2）。
dependencies {
    // 唯一算法依赖：face-sdk（compileOnly，仅编译期；运行时由消费方实现/打包）
    compileOnly("atlas.sdk.face:face-sdk:${rootProject.extra["faceSdkVersion"]}")

    // ========== 测试依赖 ==========
    testImplementation("junit:junit:4.13.2")
    // org.json 的 JVM 实现：让纯 JVM 单测可解析 JSON（Android 内置 org.json 与之一致）。
    // 用于 FatigueRuleLoader 解析正确性测试（隐患 E 修复，FACEP-015）。
    testImplementation("org.json:json:20231013")
}
