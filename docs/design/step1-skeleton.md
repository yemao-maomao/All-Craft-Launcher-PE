# Step 1 设计：项目骨架（app 壳 + core-common + 模块占位）

> 状态：已实现，待用户确认。构建/测试结果见 §7（构建完成后回填）。
> 对应交付：目录树 + 关键 build.gradle 片段 + 空壳运行说明。

---

## 1. 目标与范围

- Gradle 多模块结构（app + core-common + 7 个 core-*），Compose Multiplatform 导航骨架、主题；
- 权限：INTERNET 常驻；MANAGE_EXTERNAL_STORAGE 仅声明、仅导入导出时按需引导授予；
- 统一 Result/错误类型、日志门面（Timber + 文件双写 + token 脱敏）、崩溃捕获开关（默认关）；
- 应用图标：用户提供的 Canva 源图 → 自适应图标（本步新增需求）；
- arm64 only 双重防线（APK ABI 过滤 + 运行时守卫）。

## 2. 版本选型与取舍

| 组件 | 版本 | 取舍依据 |
|---|---|---|
| Gradle | 8.14.4 | 与 AGP 8.13 组合在本机验证过（ACL-PE fork 同款）；wrapper 走腾讯镜像（国内直连快），海外可改回官方 |
| AGP | 8.13.2 | fork 验证版；支持 compileSdk 36 |
| Kotlin | 2.4.10 | fork 验证版；官方文档确认「最新 CMP 与最新 Kotlin 兼容」，CMP 1.12.x 最低要求 Kotlin 2.1+ |
| Compose Multiplatform | 1.12.2 | 技术栈指定 CMP；**当前仅 Android 单目标**（KMP 多目标需引入 KMP 插件，Shell 现阶段依赖 Android API，无收益——将来要桌面端时再加 androidTarget 之外的 target，模块边界不受影响） |
| R8 | 9.4.17（classpath 覆盖） | AGP 8.13 捆绑 R8 8.13.19 只支持 Kotlin ≤2.3 元数据；Kotlin 2.4 产物需 R8 9.1.29+（官方 D8/R8-Kotlin 兼容表） |
| compileSdk / targetSdk / minSdk | 36 / 35 / 29 | 需求定 35/29；compileSdk 36 因本机仅装 platform 36，且 androidx 最新库（activity 1.13、datastore 1.3）要求 36 |
| JDK | 本机 JDK 21（`C:\Users\vshuf\Documents\ai\tools\jdk21`） | AGP 8.13 要求 17+；jvmTarget 17（与游戏 JRE17/21 无关——那是对端进程） |

**镜像源**：pluginManagement 里阿里云 central 镜像放最前（kotlin 系插件 marker 发布在 Maven Central，阿里云 gradle-plugin 镜像同步滞后，实测只有 1.6.x）；官方源兜底。详见 `settings.gradle.kts` 注释。

**为什么不建「共享 base 模块的公共 build 脚本」**：模块少（9 个），公共配置只有 10 行，复制优于抽象；模块数再翻倍时再抽 `build-logic` 或约定插件（已记录，避免过早抽象）。

## 3. 目录树

```
D:\mc1\
├── .gitignore / .gitattributes    # gitattributes：本机 autocrlf=true，显式声明文本/二进制（历史教训）
├── LICENSE                        # GPL-3.0（与 ACL 一致）
├── README.md
├── build.gradle.kts               # 根：插件版本声明 + R8 9.4.17 classpath 覆盖
├── settings.gradle.kts            # 模块清单 + 镜像源
├── gradle.properties              # 4g 堆、并行、缓存
├── gradle/libs.versions.toml      # 版本目录（含后续模块预声明 room/serialization）
├── gradle/wrapper/                # 8.14.4（腾讯镜像 URL）
├── gradlew / gradlew.bat
├── local.properties               # 本机 SDK 路径（不提交）
├── scripts/gen_icon.py            # 图标生成脚本（可复现，见 §6）
├── docs/design/step0-core-env.md  # Step 0 设计（已评审）
├── docs/design/step1-skeleton.md  # 本文
├── docs/assets/icon-preview.png   # 图标预览（合成效果）
├── app/                           # Shell UI
│   ├── build.gradle.kts           # abiFilters arm64-v8a、CMP、buildConfig
│   └── src/main/
│       ├── AndroidManifest.xml    # INTERNET + MANAGE_EXTERNAL_STORAGE(仅声明)
│       ├── java/org/aclpe/aclpe/
│       │   ├── ACLApplication.kt  # 日志初始化 + 崩溃捕获安装
│       │   ├── MainActivity.kt    # ABI 守卫 + 主题 + 导航宿主
│       │   ├── core/AbiGuard.kt   # 32 位设备提示退出
│       │   ├── core/AppSettings.kt# DataStore（主题/崩溃开关）
│       │   ├── core/CrashCatcher.kt # 崩溃捕获（默认关）
│       │   └── ui/
│       │       ├── theme/         # 品牌绿 Material3 双主题
│       │       ├── nav/           # Destinations + AppNavHost（底部导航 4 页）
│       │       └── screens/       # Home/Versions/Settings/About 占位页
│       └── res/
│           ├── values/{strings,colors,themes}.xml
│           └── mipmap-*/          # 自适应图标 foreground（5 密度）+ anydpi-v26 XML
├── core-common/                   # 全项目共享（唯一有代码的 core 模块）
│   ├── build.gradle.kts           # api 暴露 timber；coroutines
│   └── src/
│       ├── main/.../result/AppResult.kt   # 统一 Result/错误类型
│       ├── main/.../platform/OsSpec.kt    # 平台常量单一来源（Step2/3 共用）
│       ├── main/.../log/{LogRedactor,FileLoggingTree}.kt
│       └── test/.../              # 16 个单测（脱敏 8 + 轮转 2 + Result 5 + OsSpec 1）
└── core-{env,download,launch,account,settings,mods,profile}/  # 占位壳（build.gradle + manifest + README，无源码）
```

## 4. 关键 build.gradle 片段

```kotlin
// settings.gradle.kts —— 镜像源（kotlin 系 marker 在 central，阿里云 gradle-plugin 镜像同步滞后）
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/central")      // kotlin 插件 marker
        maven("https://maven.aliyun.com/repository/gradle-plugin") // AGP 等
        gradlePluginPortal(); google(); mavenCentral()
    }
}

// 根 build.gradle.kts —— Kotlin 2.4 元数据需要新 R8（fork 验证组合）
buildscript { dependencies { classpath("com.android.tools:r8:9.4.17") } }

// app/build.gradle.kts —— arm64 only（硬性约束）
defaultConfig {
    applicationId = "org.aclpe.aclpe"
    minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.1.0"
    ndk { abiFilters += "arm64-v8a" }
}
```

## 5. 关键设计决策

1. **统一错误/结果**：`AppResult<T>`（Ok/Err）+ `AppError`（Network/Io/Env/Parse/Cancelled/Unknown，带 code/message/cause）放在 core-common；`resultOf` 保留协程取消语义（重抛 CancellationException）。各模块业务返回一律此类型。
2. **OsSpec 单一常量源**：os.name="linux"、os.arch="arm64"、ABI="aarch64"、分隔符 ':'。Step 2 rules 引擎与 Step 3 启动参数共用（防宣称值≠实际值），取值由 Step 2 golden 测试锁定，现有取值已用单测锁死。
3. **日志**：Timber 门面（core-common api 暴露）；`FileLoggingTree` 恒开写 `files/logs/launcher.log`（2MB×3 轮转、写失败静默）；`LogRedactor` 三种形态（JWT/敏感键值/Bearer）落盘前统一脱敏；debug 下 DebugTree 同样脱敏。日志树不引用 android.util.Log（优先级用稳定常量 2..7），保证纯 JVM 单测。
4. **崩溃捕获默认关**：`CrashCatcher` 安装 handler 但开关由 DataStore 控制（默认 false）；启动时异步读取开关（读取完成前保持关=安全默认）；开启后写脱敏 crash 文件再交给系统默认处理器，不吞崩溃。
5. **ABI 双重防线**：`abiFilters += "arm64-v8a"`（安装期拒绝 32 位）+ `AbiGuard` 运行时兜底（split 安装等边角），弹「设备不受支持」。
6. **权限**：INTERNET 常驻；MANAGE_EXTERNAL_STORAGE 只声明、运行时绝不默认申请（导入/导出功能实现时走系统引导页）。
7. **DataStore 设置**（主题三档 + 崩溃开关）：suspend/Flow API，主线程零 I/O；写操作用 rememberCoroutineScope（随组合取消），无 GlobalScope。
8. **主题**：品牌绿 Material3 双主题；不用 dynamicColor（品牌稳定优先）。
9. **单 Activity**：singleTask + configChanges 防旋转/分屏重建（后续游戏进程拉起时 Activity 重建会打断生命周期）。
10. **allowBackup=false**：游戏数据 GB 级，禁止系统备份；迁移功能后续自研导出。

## 6. 应用图标（本轮新增需求）

- 源图：用户提供的 Canva 图（2048×2048 JPG，白底）。
- `scripts/gen_icon.py`（Pillow）：四角采样判定纯色底 → contain 模式（画面缩至安全区 72%，背景白）→ 生成 5 密度 foreground PNG + 预览图 `docs/assets/icon-preview.png`。
- 自适应图标：白底背景色 + foreground + monochrome（API 33+ 主题图标复用 foreground）。
- **待用户确认**：图标是「盲处理」（模型无法直接查看图片），请打开预览图确认效果；需要调整（出血比例/背景色/直接裁切）随时改脚本重跑。
- 已知局限：源图为 JPG（无透明通道），若后续提供透明底 PNG 可去掉白底。

## 7. 验证（全部通过 ✅）

| 验证项 | 手段 | 结果 |
|---|---|---|
| core-common 单元测试 | Gradle `:core-common:testDebugUnitTest`（Kotlin 2.4.10 真实编译） | ✅ **16/16 全绿**（脱敏 8 + 文件日志 2 + Result 5 + OsSpec 1，XML 0 failures/0 errors） |
| app 构建 | Gradle `:app:assembleDebug`（CMP 1.9.0 / AGP 8.13.2 / compileSdk 36） | ✅ BUILD SUCCESSFUL |
| APK 信息 | aapt2 dump badging | ✅ 包名 org.aclpe.aclpe、versionName 0.1.0、minSdk 29 / targetSdk 35、**native-code 仅 arm64-v8a**、launchable MainActivity |
| APK 签名 | apksigner verify | ✅ CN=Android Debug（本机允许区专用 keystore，见已知问题 7） |
| APK 摘要 | SHA256 | `CCE9B5B2D8E6E7D6533268360F4E6B38EB6B170A4762192E405751C45055D66E`（30,526,337 B） |

验证过程**抓到并修复 3 个真实缺陷**（这正是验证的价值）：
1. `LogRedactor` 规则顺序缺陷：JWT 标记会被键值规则二次改写 → 重排为「键值 → JWT → Bearer」。
2. `LogRedactorTest` 的 JWT 断言与实现语义不符 → 拆成「独立 JWT」「键值携带 JWT」两个用例。
3. `FileLoggingTreeTest` 直接调用受保护方法会静默落到 Timber 的 vararg 重载（tag/message 错位）→ 改为子类桥接 `TestableTree`，`FileLoggingTree` 声明 `open`（仅测试用途，已注明）。

**构建场地说明**：本会话 DSH 沙箱限制 java 系子进程只能写旧工作区 `C:\Users\vshuf\Documents\ai`，因此构建走镜像方案（用户已批准）：`scripts\mirror-build.cmd` 将源码 robocopy 同步到允许区镜像内构建、APK 回拷 `dist\`；git 仓库与源码始终在 `D:\mc1`。沙箱解除后可直接 `scripts\build-verify.cmd` 原地构建（两脚本内容等价，仅场地不同）。构建环境配方已写入本机记忆。

## 8. 已知问题

1. CMP 仅 Android 目标：将来要桌面端需引入 KMP 插件并评估 Shell 对 Android API 的依赖面（预留，非缺陷）。
2. 启动过渡：platform 主题黑色窗口背景，未接 SplashScreen API（后续 polish，不影响功能）。
3. release 构建未配签名/混淆：签名沿用自有 keystore（fork 已删证书换自有），发布流程后续步骤统一处理。
4. 崩溃开关为异步读取：启动瞬间（读取完成前）的崩溃不落盘 —— 默认关语义下的可接受行为。
5. 图标盲处理风险：见 §6，等用户视觉确认。
6. 日志等级用硬编码 2..7 而非 android.util.Log 常量：Android 优先级常量历史稳定，换取 JVM 单测能力（已注释说明）。
7. 本机沙箱下 debug keystore 放在允许区（`ACLPE_DEBUG_KEYSTORE` 环境变量覆盖，见 scripts/mirror-build.cmd；app/build.gradle.kts 仅在该变量存在时生效）；普通开发机仍用默认 `~/.android/debug.keystore`，不受影响。
8. CMP 版本钉在 1.9.0、nav 2.9.8、lifecycle 2.10.0：更高的版本要求 compileSdk 37/AGP 9.1，超出本机工具链（AGP 8.13 上限 36）；升级工具链时可随动解钉（libs.versions.toml 有注释）。

## 9. 下一步

Step 0 core-env（EnvProbe/EnvBootstrap/状态机/JRE 映射 + 单测），下载侧以 DownloadScheduler 接口占位，Step 2 实现真实下载后回填。
