# core-common

全项目共享基础设施，不依赖任何业务模块：

- `result/`：统一 `AppResult<T>` / `AppError` 与组合函数（`map`/`onOk`/`onErr`/`resultOf`）——各模块业务返回值统一走本类型。
- `platform/OsSpec.kt`：平台身份常量（os.name/os.arch/ABI/classpath 分隔符）——Step 2 rules 引擎与 Step 3 启动参数拼装的**唯一常量源**（硬性约束，防宣称值与实际值漂移）。
- `log/`：日志门面。Timber 经 `api` 暴露给全部模块；`FileLoggingTree` 提供 `files/logs/launcher.log` 双写与大小轮转；`LogRedactor` 在落盘前统一脱敏（JWT / 敏感键值 / Bearer），token 绝不落盘。
