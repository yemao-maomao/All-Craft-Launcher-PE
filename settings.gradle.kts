// ACL-PE：Android 平台 Minecraft Java 版第三方启动器（自研 Shell UI）。
// 镜像源取舍见 docs/design/step1-skeleton.md：国内直连优先阿里云镜像，官方源兜底。
pluginManagement {
    repositories {
        // Kotlin 插件 marker（含 compose 编译器插件 org.jetbrains.kotlin.plugin.compose）发布在
        // Maven Central；阿里云 gradle-plugin 镜像对 kotlin 系 marker 同步滞后，故 central 镜像放最前
        maven("https://maven.aliyun.com/repository/central")
        // AGP 等官方插件 marker 的镜像
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "ACL-PE"

// ---- 模块清单 ----
// app           : Shell UI（Compose Multiplatform，Android 单目标）
// core-common   : 全项目共享基础设施（Result/错误类型、日志门面、平台常量）
// core-env      : Step 0 环境探测与自动补齐
// core-download : Step 2 版本/资源下载
// core-launch   : Step 3 启动命令拼装与进程拉起
// core-account  : Step 4 账号契约与 Mock
// core-settings : Step 5 游戏外设置编辑器
// core-mods     : M6 社区资源（占位）
// core-profile  : 启动配置 Profile（占位）
include(":app")
include(":core-common")
include(":core-env")
include(":core-download")
include(":core-launch")
include(":core-account")
include(":core-settings")
include(":core-mods")
include(":core-profile")
