plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skyworth.faceid"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.skyworth.faceid"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("platform") {
            val keyStorePath = rootProject.projectDir.resolve("keystore/skytv/platform.keystore")
            storeFile = file(keyStorePath)
            keyAlias = "platform"
            keyPassword = "android"
            storePassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("platform")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("platform")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 由于 EvsSDK 依赖 AOSP 框架类，需要添加系统 API
    useLibrary("android.car")

    // 单元测试配置：允许使用 Android 类
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // 仅编译 HardwareBuffer 读取器（极小 JNI，不依赖算法库）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.18.1"
        }
    }

    defaultConfig {
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // AHardwareBuffer_fromHardwareBuffer 需要 API 26+
                arguments("-DANDROID_PLATFORM=android-29")
            }
        }
    }

    packagingOptions {
        jniLibs.pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
    }
}

dependencies {
    // FACEP-014：算法/总线/逻辑下沉到 :algo 库模块
    implementation(project(":algo"))

    // 方案 B（FACEP-014）：face-sdk 由消费方（本 app）自行提供。
    // :algo 以 compileOnly 引用 face-sdk（仅编译期，不打包/不传递），因此这里必须
    // implementation 它，否则运行时报 NoClassDefFoundError。app 是 application 模块，
    // 本地 aar 文件依赖合法（不受 library"禁止本地 aar 打包"限制）。
    implementation(files("libs/face-sdk-v1.1.4.aar"))

    // EvsSDK AOSP 依赖（通过 maven-repo-plugin 加载）
    implementation("${rootProject.extra["aosp_evs_lib"]}:${rootProject.extra["aosp_evs_lib_version"]}")
    implementation("${rootProject.extra["aosp_car_lib"]}:${rootProject.extra["aosp_car_lib_version"]}")

    // AndroidX 支持库
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle（EvsFrameRate 依赖）
    implementation("androidx.lifecycle:lifecycle-livedata:2.3.1")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")

    // ========== 测试依赖 ==========

    // JUnit 4
    testImplementation("junit:junit:4.13.2")

    // Robolectric：在 JUnit 中加载 Android 类
    testImplementation("org.robolectric:robolectric:4.10.3")

    // AndroidX Test（用于 Activity 及生命周期测试）
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
}

