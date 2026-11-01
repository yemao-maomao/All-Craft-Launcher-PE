# Step 3 设计：core-launch（启动命令拼装与进程拉起）

> 状态：已实现并验证（2026-11-01）。

## 1. 目标与范围

- 按 version json 模板展开 classpath（顺序敏感）、natives 路径、jvm/game args；
- rules 过滤后替换占位符（复用 Step 2 RulesEngine）；
- JVM 参数按内存档位自动生成（-Xmx ≤ 可用内存一半、G1GC、ReservedCodeCacheSize=512m、GC 日志落盘）；
- 进程拉起契约 + 真实实现（JAVA_HOME/LD_LIBRARY_PATH/ANDROID_DATA/TMPDIR，stdout/stderr 双落盘）；
- ★ OsName/Arch 与 Step 2 rules 引擎共用同一常量源（OsSpec → PlatformContext，硬性约束满足）。

## 2. 关键取舍

1. **classpath 顺序敏感**：LibraryResolver 输出顺序即 classpath 顺序 + client jar（官方等价，golden 断言 44 段 = 43 库 + client）。
2. **-cp 注入去重**：1.20.1+ 官方模板自带 `-cp ${classpath}`，启动器检测不重复；遗留模板全量生成。
3. **主类恒由启动器追加**（模板不含 mainClass）。
4. **占位符**：覆盖官方全集（含 ${user_properties}=>{}、${arch}、${classpath_separator}）；**未知占位符原样保留**（不静默吞掉，模板变化可被发现）。
5. **内存策略**：heap = min(档位, max(512, 可用内存/2))；GC 日志 javaMajor 分流（9+ 用 -Xlog:gc*，8 用 -Xloggc）。
6. **环境变量**：LD_LIBRARY_PATH 用 EnvLayout.jreLibPathCandidates（与 EnvProbe 同一常量源）；ANDROID_DATA=files 根、ANDROID_ROOT=/system、HOME/TMPDIR=gameDir。
7. **进程拉起为接口 + 真实实现**：JVM 单测不直接拉起（linux JRE 无法在本机执行），真机步骤见 §4。

## 3. 目录

```
core-launch/src/main/java/org/aclpe/core/launch/
  model/LaunchContext.kt        # 上下文（版本/账号/路径/内存档位/javaMajor）
  args/Placeholders.kt          # 占位符表 + 替换（未知保留）
  args/MemoryPolicy.kt          # 内存档位 + GC 日志分流
  args/LaunchArgsBuilder.kt     # jvm/game 参数拼装、classpath、遗留分词
  process/LaunchCommand.kt      # 命令数据类
  process/LaunchPlanBuilder.kt  # ctx + EnvLayout → LaunchCommand（环境变量注入）
  process/GameProcessStarter.kt # 拉起契约 + RealGameProcessStarter（双落盘）
core-launch/src/test/resources/versions/{1.20.1,1.8.9}.json   # golden 夹具（复制自 core-download）
```

## 4. 验证结果

- **单测 15/15**（全项目 122/122）：占位符矩阵、遗留分词、内存策略（半内存上限/512 下限/档位封顶/GC 分流）、
  **golden**：1.20.1 现代模板 game 前 6 项与官方等价、accessToken offline 空、jvm 模板 cp 不重复、
  classpath 44 段、natives 目录展开、品牌注入、osx/windows 参数被 rules 过滤、主类末尾；
  1.8.9 遗留模板占位符/分词/传统 client jar 位置；LaunchPlanBuilder 环境变量与缺失二进制报错。
- **构建**：BUILD SUCCESSFUL。
- **手工（真机，core-launch 全链路）**：
  1. 环境就绪后点「启动游戏」（Step 3 尚未接线 UI 按钮——接线随 core-account 一并做，见已知问题 2）；
  2. 观察 `files/logs/`：game-stdout.log / game-stderr.log 落盘、gc 日志轮转；
  3. 断点检查：进程被杀后 JVM 退出码被记录。

## 5. 已知问题

1. **UI 未接启动按钮**：HomeScreen 的启动按钮仍为空操作——启动链路需要 Profile/账号上下文，计划随 Step 4（账号契约）一并接线（避免为临时占位返工）。
2. 进程拉起真实实现未在 JVM 单测覆盖（linux JRE 无法在本机运行）；真机验证步骤见 §4。
3. 遗留版本（≤1.8.9）的 natives 分类器/classpath 已 golden 覆盖，但真实拉起未验（首轮目标不含旧版本）。
4. GC 日志路径在 gameDir/logs（与启动器日志 files/logs 分离，游戏侧习惯）。
5. `${arch}` 等新增占位符在 1.21.5+ 模板中才实际出现（当前 golden 覆盖到 1.21.4；后续补 1.21.5 夹具时验证）。
