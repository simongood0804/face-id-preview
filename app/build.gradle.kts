plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skyworth.faceid"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    ndkVersion = "25.2.9519653"

    defaultConfig {
        // 包名可用 `-PappId=xxx`（或环境变量 ORG_GRADLE_PROJECT_appId=xxx）覆盖，默认本项目包名。
        // 用途：**顶替车机预装的第三方系统应用**（例如标定软件）——需要占用它的包名与安装位置
        // 才能走预装通道，见 Makefile 的 PACKAGE_NAME / SYSTEM_APP_DIR / APK_NAME 说明。
        // 注意：只改 applicationId，`namespace`（类名与 R 资源包）保持 com.skyworth.faceid 不变，
        // 避免全项目重命名；用 am start 启动时要用**完整类名**（Makefile 里已改成完整类名）。
        applicationId = (project.findProperty("appId") as String?) ?: "com.skyworth.faceid"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        // ⚠️ 不要随意降到 1：顶替车机预装的 `com.mediapipe.avm`（标定软件）时它的
        // versionCode 是 2，比它低容易被 PackageManager 当降级处理。留出余量。
        versionCode = 3
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // face-sdk native so 仅含 arm64，固定 ABI 避免误加其他架构导致缺 so 崩溃
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

    // media_record 预编译库（app/libs/arm64-v8a/*.so）通过 jniLibs 打包进 APK。
    // CMake 侧改用 link_directories + `-l` 链接（不用 IMPORTED target），
    // 避免"IMPORTED 与 jniLibs 重复打包"冲突，也避免 release 下 IMPORTED 库未被稳定打包。
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    packagingOptions {
        // hardware_buffer_reader 与 face-sdk 均链接 libc++_shared，取一份即可
        jniLibs.pickFirsts.add("**/libc++_shared.so")
        // 排除 aar/jar 中冗余的 META-INF 元数据，避免打包合并冲突
        excludes.addAll(
            setOf(
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        )
    }
}

dependencies {
    // FACEP-014：算法/总线/逻辑下沉到 :algo 库模块
    implementation(project(":algo"))

    // 方案 B（FACEP-014）：face-sdk 由消费方（本 app）自行提供。
    // :algo 以 compileOnly 引用 face-sdk（仅编译期，不打包/不传递），因此这里必须
    // implementation 它，否则运行时报 NoClassDefFoundError。app 是 application 模块，
    // 用 maven 坐标依赖本地仓库（~/.m2）的 aar 合法。
    implementation("atlas.sdk.face:face-sdk:${rootProject.extra["faceSdkVersion"]}")

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

