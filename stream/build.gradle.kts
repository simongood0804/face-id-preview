plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    // 注意：**不要**改这个 namespace。MediaRecordNative 的 JNI 符号在
    // media_record_jni.cpp 里硬绑定为 `Java_com_skyworth_faceid_media_MediaRecordNative_*`，
    // 因此 Kotlin 包名必须保持 `com.skyworth.faceid.media`（物理移动、包名不变）。
    namespace = "com.skyworth.faceid.stream"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    ndkVersion = "25.2.9519653"

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        // media_record 预编译 so 仅含 arm64，固定 ABI 避免误加其他架构导致缺 so 崩溃
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 推流 JNI（media_record_jni）+ 宿主侧 EGL 渲染器（encoder_surface_renderer）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.18.1"
        }
    }

    // media_record 预编译库（stream/libs/arm64-v8a/*.so）通过 jniLibs 打包。
    // CMake 侧改用 link_directories + `-l` 链接（不用 IMPORTED target）：
    //   1) IMPORTED 会让 AGP 也去打包，导致 mergeNativeLibs 同路径冲突；
    //   2) `-l` 形式让 linker 按文件名写入 DT_NEEDED，避免无 SONAME 的 core 库
    //      被写成构建机绝对路径（真机 dlopen 会 not found）。
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    packagingOptions {
        // 推流 so 与其它模块均链接 libc++_shared，取一份即可
        jniLibs.pickFirsts.add("**/libc++_shared.so")
    }

    // 纯推流库：不依赖 EvsSDK / AOSP 系统类（无 useLibrary("android.car")、
    // 无 xbootclasspath），可脱离系统编译环境独立发布 AAR。取流（EvsSDK）不在此模块。
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    // 推流模块仅依赖 Android SDK + JDK + org.json（Android 内置）。无第三方依赖。
    // ========== 测试依赖 ==========
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
