import groovy.lang.GroovyObject
import pizzk.gradle.plugin.index.MavenRepoApi

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = uri("https://jitpack.io"))
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.7.20")
        classpath("io.github.oxsource:maven-repo-plugin:1.0.3")
    }
}

allprojects {
    repositories {
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
        google()
        mavenCentral()
        maven(url = uri("https://jitpack.io"))
    }
}

val compileSdkVersion: Int by extra(31)
val minSdkVersion: Int by extra(29)
val targetSdkVersion: Int by extra(29)
val aosp_car_lib: String by extra("com.android.car:lib")
val aosp_car_lib_version: String by extra("1.0.6")
val aosp_framework: String by extra("com.android:framework")
val aosp_framework_version: String by extra("1.0.0")
val aosp_evs_lib: String by extra("com.android.car:evs")
val aosp_evs_lib_version: String by extra("1.0.7")
val repo_product_group: String by extra("coolwell")
val repo_product_name: String by extra("coolwell:van233_snap")
val repo_common_name: String by extra("coolwell:common")
val repo_common_jars: String by extra("jars.gradle")
val repo_common_jars_node: String by extra("script://common/compile/jars")

apply(plugin = "pizzk.gradle.maven.repo")
with(extensions["mavenrepo"] as pizzk.gradle.plugin.index.MavenRepoConfig) {
    changing(false)
    manifests {
        manifestGitee(false)
    }
    namespace {
        include(listOf(repo_product_name, repo_common_name), listOf("*"))
    }
}

afterEvaluate {
    val api = pizzk.gradle.plugin.comm.GlobalContext.value<MavenRepoApi>()
    val script = api?.script(project, repo_common_name)
    val obj = script?.load(repo_common_jars, repo_common_jars_node, repo_product_name)
    val handle = obj as? GroovyObject ?: return@afterEvaluate
    val fwk = listOf(aosp_framework, aosp_framework_version)
    val path = handle.invokeMethod("jar", fwk) as? String ?: ""
    handle.invokeMethod("setXbootclasspath", listOf(project, listOf(path)))
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

