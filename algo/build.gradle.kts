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
// 原因：AGP 禁止 library 用 api/implementation 依赖本地 .aar 再打包 AAR（产物破损，
// face-sdk 的 classes 不会进 AAR），且若 api() 坐标依赖则必须发布到可复现仓库——
// 当前 face-sdk 授权/分发未定，故走"消费方自供"路径。
dependencies {
    // 唯一算法依赖：face-sdk（compileOnly，仅编译期；运行时由消费方实现/打包）
    compileOnly(files("libs/face-sdk-v1.1.4.aar"))

    // ========== 测试依赖 ==========
    testImplementation("junit:junit:4.13.2")
}
