# Step 5 设计：core-settings（游戏外设置编辑器）

> 状态：已实现并验证（2026-11-01）。第一阶段收尾模块。

## 1. 目标与范围

- options.txt 双向解析/序列化（key:value、未知 key 原样保留、注释与空行保真、坏行=注释保留）；
- 可编辑项分组：画面 / 声音 / 控制 / 其他 + 启动器侧（内存档位/JVM 附加/镜像源/图形后端/性能总闸 L0~L5）；
- 按 Profile 隔离 + 默认模板 + 标脏 + 恢复默认/对比差异/导入导出/回退备份；
- 安全：写入前校验取值范围、游戏运行中禁止覆盖（进程检测）、写入前备份 .bak、原子写入失败保留原文件；
- 合并策略：最后写入胜出 + 保留 bak（不做三方合并，用户规格）。

## 2. 关键取舍

1. **图形后端存启动器配置**（用户已确认）：options.txt 无法承载（MC 忽略未知键），UI 仍放在画面组。
2. **坏行保留为注释**：键值视图不可见（「非法值丢弃」语义），但回写保真——模组自定义行绝不丢。
3. **末尾换行符是终止符非空行**（split 去尾空串），保证 parse→serialize round-trip 逐字节一致。
4. **原子写入用 NIO Files.move(REPLACE_EXISTING, ATOMIC_MOVE)**：Windows 上 File.renameTo 无法覆盖已存在目标（测试实证），ATOMIC_MOVE 不支持时降级。
5. **工作副本以 OptionsFile 全程传递**：保存保留原文件注释/空行/未知键（不进 map 重建）。
6. **启动器侧校验**：内存档位枚举、镜像源枚举（Gitee 不启用）、JRE 覆盖 ∈{8,17,21,25}、代理需 http(s)、JVM 附加参数 ≤512 字符。
7. **性能总闸 L0~L5 默认全关**（M7 逐项上线并附 Perfetto 基线——硬性约束）。
8. **导入导出骨架走固定路径**（files/exports/），SAF 文件选择器后续接入（MANAGE_EXTERNAL_STORAGE 声明已就绪）。
9. **触控方案存储完整、UI 简化**：TouchScheme JSON 按 Profile 存取；UI 提供「保存默认方案」入口，完整管理界面随触控模块（M9）。

## 3. 目录

```
core-settings/src/main/java/org/aclpe/core/settings/
  options/OptionsFile.kt        # 行模型 + parse/serialize/diff/原子写/备份/回退
  options/GameOptionKeys.kt     # 键定义 + 类型 + 取值范围校验
  launcher/LauncherSettings.kt  # 启动器侧模型 + 校验器
  touch/TouchScheme.kt          # 触控方案模型
  store/SettingsStores.kt       # Profile 隔离存储 + 默认模板 + 导入导出
core-settings/src/test/.../     # 22 例（解析保真/校验矩阵/存储/模板/导入导出）
app/.../ui/settings/GameSettingsViewModel.kt  # 编辑器状态（标脏/差异/消息）
app/.../ui/screens/GameSettingsSection.kt     # 五组 UI + 动作按钮
app/.../core/env/GameProcessRegistry.kt       # 运行中检查源
```

## 4. 验证结果

- **单测 153/153 全绿**（core-settings 新增 22：round-trip 保真含未知键/冒号值/CRLF/坏行、
  校验矩阵、原子写/备份/运行中拒绝/回退、Profile 隔离、触控方案、模板、导入导出）。
- **构建**：BUILD SUCCESSFUL；APK 44.1MB，SHA256 `953003CB…E3E4BF8`。
- **手工（真机）**：
  1. 设置 → 游戏设置：改渲染距离 33 → toast「非法值已忽略」；改 8 → 标脏 ●；
  2. 保存 → options.txt 更新且 options.txt.bak 生成（原内容）；
  3. 游戏运行中（拉起后）保存 → 「游戏运行中，禁止覆盖」；
  4. 恢复默认/设为默认模板/对比差异（显示 N 项差异）/导出/导入/回退备份；
  5. 声音滑杆 0-100%、镜像源切换、性能总闸六开关持久化（重启保持）。

## 5. 已知问题

1. 触控方案 UI 为最小入口（管理界面随 M9）。
2. 导入导出为固定路径骨架（SAF 选择器后续接入）。
3. options.txt 保存为「全文件替换」（保留 .bak），与 MC 自身写入构成最后写入胜出——符合用户规格，不做三方合并。
4. keybinds 编辑器仅展示数量（映射表编辑随触控模块）。
5. 图形后端 Vulkan 枚举已预留，真实渲染后端 M8 落地。
