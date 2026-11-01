# Step 2 设计：core-download（版本下载模块）

> 状态：已实现并验证（2026-11-01）。对应交付：模块代码 + rules 测试 + golden 交叉验证 + 手工步骤。

## 1. 目标与范围

- version_manifest_v2 / version json 解析（kotlinx-serialization，忽略未知键）；
- ★ rules 求值引擎（附件 A 未提供 → 按官方语义自拟用例集 + 6 个真实 version json golden 交叉验证）；
- 库/natives/assets 解析与地址生成、镜像源重写（官方/BMCLAPI/Gitee 预留）；
- DownloadManager：分片并发 + Range 续传 + 重试退避（1s/4s/16s）+ 镜像轮换 + SHA 校验 + 原子落地；
- core-env DownloadScheduler 真实实现（Room 持久化任务、进度 Flow、暂停/继续/取消、网络策略门禁）；
- JRE 安装器（tar.xz 解压含符号链接解析、zip-slip 防护、原子替换、.build 标记）。

## 2. 关键取舍（含偏差）

1. **rules 语义**：空 rules = 不设防（允许）；有规则有序匹配第一个命中者；有规则无命中 = **disallow（用户规格）**。与 Mojang 官方「无命中默认允许」的差异对真实数据无影响（真实 json 均以无条件兜底规则结尾），golden 验证一致。
2. **features 缺失视为 false**（{x:false} 规则对普通用户命中）。
3. **natives 双模型**：现代（1.19+）四段名条目（natives-* 或架构分类器如 netty 的 linux-aarch_64，按 PlatformContext.platformClassifierCandidates 匹配）；遗留（≤1.12.2）natives 映射 + downloads.classifiers。
4. **附件 A 替代**：自拟 12 条 rules 矩阵用例 + 6 个真实 json（1.8.9/1.12.2/1.20.1/1.20.5/1.21/1.21.4）golden：解析字段、库/natives 计数与分类器、exclude、镜像重写。基线按官方语义独立推导并固化。
5. **JRE 布局**：Pojav 包为经典布局（lib/server/libjvm.so 经符号链接指向 lib/aarch64/...），EnvLayout 以 Pojav 布局为规范 + 架构子目录候选；解压器把归档内符号链接解析为内容副本（Windows 测试环境无建链权限）。
6. **launcher.db 单所有者**：core-env 的 LauncherDatabase 升 v3（download_tasks/versions/libraries 表 + 手工迁移 1→2→3），避免多模块各建一个 RoomDatabase 破坏同一文件 schema。
7. **Gitee 镜像预留未启用**（无稳定公共端点，框架支持）。
8. **JRE21 下载源待用户决策**（调研结论见下）：JRE17 已 pin（Pojav GitHub 不可变资产 + 实测 sha256）。
9. **测试服务器用 MockWebServer**：JDK 的 com.sun.net.httpserver 不在 Android 单测编译类路径。
10. **KSP 2.3.12**（独立版本线）配 Room 2.8.5 + Kotlin 2.4.10（fork 实证）。

## 3. 目录

```
core-download/src/main/java/org/aclpe/core/download/
  json/VersionJson.kt          # manifest v2 + version json 模型与解析（BOM 防御）
  rules/RulesEngine.kt         # ★ rules 引擎 + PlatformContext（OsSpec 单一来源）
  mirror/MirrorRegistry.kt     # 官方/BMCLAPI 重写 + Gitee 预留
  resolve/LibraryResolver.kt   # 库/natives 解析（双模型）
  resolve/AssetsResolver.kt    # assets 索引解析/分片/地址
  net/DownloadManager.kt       # OkHttp 传输（分片/续传/重试/镜像/校验）
  sched/DownloadSchedulerImpl.kt  # core-env 契约实现（Room 持久化/进度 Flow/暂停继续/网络门禁）
  install/JreInstaller.kt      # TarXzExtractor + JreInstaller（原子安装/.build）
core-download/src/test/resources/versions/*.json  # 6 个 golden 夹具（Mojang 官方下载）
core-env/src/main/.../store/EnvStore.kt           # LauncherDatabase v3 + 迁移 + 新实体
app/.../core/env/{EnvDependencies,JreManifest}.kt # 真实链路装配 + 内置 pin 清单
```

## 4. 验证结果

- **单测 104/104 全绿**（core-download 33 执行 + 1 网络探针默认跳过）：rules 12、golden 8、镜像 4、assets 2、下载器 7、安装器 5、解析等。
- **构建**：BUILD SUCCESSFUL（含 app 真实链路装配）。
- **真实网络部分验证**：NetworkProbeTest 两轮实证「真实下载 38,065,004 字节 + SHA-256 校验通过 + 安装冒烟」（首次失败正是布局/符号链接 bug，已修复并单测固化）；第三轮因本机 GitHub 直连中断（ConnectException）未跑完——**环境网络问题非代码问题**。JRE17 sha256 已实测 pin：`C64583AC2E0EC8857E43456FA9ADCF482C6A8E454A7133173BF15692D2478B8D`。
- **手工步骤（真机/双轨校验）**：
  1. 装 APK → 环境卡片「Java 17：未安装（可下载）」「Java 21：下载源待配置」→ 点 Java 17「下载」→ 真实进度条+暂停/继续 → 完成后自动变「✓ 就绪」（Wi-Fi 下）。
  2. 蜂窝网络下点下载 → 不启动、状态失败（wifi_required）→ 设置里关掉「仅 Wi-Fi」再试。
  3. 杀进程断点续传：下载中杀 App → 重启 → 从 .part 断点继续。
  4. 1.21.x 轨：待 JRE21 源决策后同法验证（NetworkProbeTest 已备好骨架）。
  5. `files/logs/launcher.log` 全程无 token 明文（脱敏门面）。

## 5. 已知问题

1. **JRE21 槽位方案 B 已落地（2026-11-01 用户确认）**：组件键 jre:21 实际安装社区 JRE25（mcddos 直链，本机实测 sha256 `94BCC9C6…E9C4C2` pin）；探测用 atLeast 语义（EnvScope.jreAtLeastMajors）；风险（http、无日期文件名、内容可被替换）由 sha256 pin 兜底（替换即校验失败拒装）。若将来获得官方 JRE21 只需替换 JreManifest 常量。
2. **双轨验证状态**：1.21.x 轨（JRE25）端到端真实通过（下载→校验→安装→标记→atLeast 探测）；1.20.1 轨（JRE17）真实下载+sha256 校验+安装冒烟已多轮实证，最终断言因本机 GitHub 断连未跑完（环境网络，非代码），链路各环均有单测覆盖。
3. Gitee 镜像未启用（无稳定端点）。
4. 网络探针测试依赖本机网络（默认跳过，`ACLPE_NETWORK=true` 开启）。
5. 分片并发仅用于全新下载；断点续传走单流（设计文档已注明取舍）。
6. 下载恢复依赖 Room 任务状态 + .part 文件；任务被 cancel 后 .part 删除。
7. 符号链接目标若在归档外/缺失则跳过（冒烟与探测兜底判损坏重下）。
8. Room exportSchema=false（CI/迁移管理阶段再开）。
