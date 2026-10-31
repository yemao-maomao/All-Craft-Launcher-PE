// 根构建脚本：只声明插件版本，不在根做具体配置。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// AGP 8.13 捆绑的 D8/R8 8.13.19 只支持到 Kotlin 2.3 元数据；Kotlin 2.4 产物需 R8 9.1.29+。
// 通过 buildscript classpath 覆盖捆绑版本（ACL-PE fork 已验证该组合可在本机构建）。
// r8 同时发布在 Google Maven 与 Maven Central；显式声明仓库并走阿里云镜像（国内直连快）。
buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools:r8:9.4.17")
    }
}
