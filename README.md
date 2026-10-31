# ACL-PE

ACL-PE 是 [All Craft Launcher](https://github.com/yemao-maomao/All-Craft-Launcher-ACL-) 的 Android 手机版：一个**自研 Shell UI** 的 Minecraft Java 版第三方启动器（交互与工程经验参考 FCL / HMCL-PE / PCL2，代码从零实现）。

- 包名：`org.aclpe.aclpe`；minSdk 29（Android 10）/ targetSdk 35（Android 15）；仅 arm64-v8a。
- Shell UI：Compose Multiplatform（当前仅 Android 目标）+ Kotlin（协程/Flow，作用域由 ViewModel/Service 注入，禁 GlobalScope）。
- 运行时组件（JVM、GLFW stub、图形翻译层）合法复用成熟开源实现，运行时按需下载到应用私有目录；**绝不打包/分发 Minecraft 游戏本体**（jar/assets/libraries 全部由用户设备从官方或镜像源获取）。
- 许可证：GPL-3.0（见 LICENSE）；第三方组件的 LICENSE/NOTICE 在 About 页列出（硬性约束）。

## 模块

| 模块 | 职责 | 状态 |
|---|---|---|
| app | Shell UI：主题/导航/环境卡片/设置/关于 | Step 1 骨架 ✅ |
| core-common | Result/错误类型、日志门面（脱敏+文件双写）、OsSpec 平台常量 | Step 1 ✅ |
| core-env | 环境探测与自动补齐（EnvProbe/EnvBootstrap/状态机/JRE 映射） | Step 0 设计中 |
| core-download | 版本/资源下载、rules 求值、镜像源、进度 Flow | Step 2 待开工 |
| core-launch | 启动命令拼装与进程拉起 | Step 3 待开工 |
| core-account | 账号契约 + Mock（MS OAuth2+PKCE 预留） | Step 4 待开工 |
| core-settings | 游戏外设置编辑器（options.txt 双向解析/校验/备份） | Step 5 待开工 |
| core-mods | 社区资源（Modrinth/mrpack/依赖解算） | M6 占位 |
| core-profile | 启动配置 Profile（版本/账号/游戏目录隔离） | 占位 |

## 构建

```bash
# 要求：JDK 17+（JAVA_HOME）、Android SDK（local.properties 或 ANDROID_HOME）
gradlew.bat :app:assembleDebug            # Windows（产物 app/build/outputs/apk/debug/）
gradlew.bat :core-common:testDebugUnitTest # 单元测试
```

- 依赖镜像：`settings.gradle.kts` 默认阿里云镜像（国内直连快），海外环境可切回官方 google()/mavenCentral()。
- Gradle 发行版走腾讯镜像（`gradle/wrapper/gradle-wrapper.properties`），可改回 services.gradle.org。

## 文档

- 设计文档：`docs/design/`（每个模块一份：取舍说明 + 验证方式 + 已知问题）。
- 提交规范：Conventional Commits，按模块拆分。
