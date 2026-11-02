import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "org.aclpe.aclpe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.aclpe.aclpe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.0.2"

        // 硬性约束：arm64 only。32 位设备在安装期即被拒绝（APK ABI 过滤），另有运行时 AbiGuard 兜底。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 内置 JRE 资产（app/src/main/assets/runtimes/*.tar.xz）原样存储不二次压缩：
    // 体积与流式读取友好（参考 FCL：运行时打进 APK，环境补齐不走网络）。
    androidResources {
        noCompress += "xz"
    }

    buildTypes {
        debug {
            // 调试构建不做混淆
        }
        release {
            // 骨架期关闭混淆；M7 性能阶段开启并附基线对照数据（硬性约束：无数据不优化）
            isMinifyEnabled = false
        }
    }

    signingConfigs {
        // 本机沙箱环境：java 子进程无法写 ~/.android（debug.keystore.lock 被拒），
        // 通过环境变量 ACLPE_DEBUG_KEYSTORE 覆盖 debug 签名文件位置（见 scripts/mirror-build.cmd）。
        // 未设置时保持 AGP 默认行为（~/.android/debug.keystore），对普通开发机无影响。
        val debugKeystoreOverride: String? = System.getenv("ACLPE_DEBUG_KEYSTORE")
        if (debugKeystoreOverride != null) {
            getByName("debug") {
                storeFile = file(debugKeystoreOverride)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-env"))
    implementation(project(":core-download"))
    implementation(project(":core-launch"))
    implementation(project(":core-account"))
    implementation(project(":core-settings"))
    implementation(project(":core-mods"))

    // Compose Multiplatform（当前仅 Android 目标；模块边界见 docs/design/step1-skeleton.md）
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.material3)
    debugImplementation(compose.uiTooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)   // EnvDependencies 显式构造 OkHttpClient

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
