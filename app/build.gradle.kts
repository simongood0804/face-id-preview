plugins {
    id("com.android.application")
}

android {
    namespace = "com.skyworth.faceid"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

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

    // 由于 EvsSDK 依赖 AOSP 框架类，需要添加系统 API
    useLibrary("android.car")

    // 单元测试配置：允许使用 Android 类
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
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

