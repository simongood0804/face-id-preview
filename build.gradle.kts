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

// ===== atlas face-sdk 仓库自动同步 =====
// 配置项（gradle.properties，均可覆盖）：
//   atlasMavenEnabled  启用开关，默认 true
//   atlasMavenRepoUrl  仓库地址，默认 GitLab atlas_maven
//   atlasMavenLocalDir 本地 checkout 目录，默认 ~/.m2/atlas_maven
val atlasMavenEnabled: Boolean =
    (project.findProperty("atlasMavenEnabled") ?: "true").toString().toBoolean()
val atlasMavenRepoUrl: String =
    (project.findProperty("atlasMavenRepoUrl")
        ?: "ssh://git@10.14.101.201:9005/algo/atlas_maven.git").toString()
val atlasMavenLocalDir: String =
    (project.findProperty("atlasMavenLocalDir")
        ?: "${System.getProperty("user.home")}/.m2/repository/atlas_maven").toString()

// 非交互执行 git 命令：BatchMode 防止无 SSH key 时挂起等待密码
fun runGit(workDir: File?, vararg args: String) {
    val pb = ProcessBuilder(listOf("git") + args)
    if (workDir != null) pb.directory(workDir)
    pb.environment()["GIT_SSH_COMMAND"] = "ssh -o BatchMode=yes -o ConnectTimeout=10"
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().readText()
    val exit = proc.waitFor()
    if (exit != 0) error("git ${args.joinToString(" ")} 失败(exit=$exit):\n$output")
}

// sync 时同步仓库：无则 clone，有则 pull（ff-only 不产生 merge）
fun syncAtlasMaven() {
    val dir = File(atlasMavenLocalDir)
    try {
        when {
            !dir.exists() -> {
                dir.parentFile?.mkdirs()
                runGit(null, "clone", "--depth", "1", atlasMavenRepoUrl, dir.absolutePath)
                logger.lifecycle("atlas maven: 已 clone ${atlasMavenRepoUrl} -> ${dir.absolutePath}")
            }
            File(dir, ".git").exists() -> {
                runGit(dir, "pull", "--ff-only")
                logger.lifecycle("atlas maven: 已 pull ${dir.absolutePath}")
            }
            else -> logger.warn("atlas maven: ${dir.absolutePath} 已存在但非 git 仓库，跳过同步")
        }
    } catch (e: Exception) {
        logger.warn("atlas maven: 自动同步失败，使用本地已有缓存继续: ${e.message}")
    }
}

if (atlasMavenEnabled && !gradle.startParameter.isOffline) {
    syncAtlasMaven()
}

allprojects {
    repositories {
        // face-sdk 发布在 atlas_maven 仓库（atlas.sdk.face:face-sdk），sync 时自动 clone/pull
        if (atlasMavenEnabled && File(atlasMavenLocalDir).exists()) {
            maven { url = uri(atlasMavenLocalDir) }
        }
        // 兜底：本机 ~/.m2 本地仓库
        mavenLocal()
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
// face-sdk 本地 maven 仓库坐标版本（发布在 ~/.m2，坐标 atlas.sdk.face:face-sdk）
val faceSdkVersion: String by extra("1.0.1")
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
    changing(true)
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

