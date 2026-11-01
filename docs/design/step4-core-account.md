# Step 4 设计：core-account（账号契约与占位）

> 状态：已实现并验证（2026-11-01）。微软登录本期**只留契约与方案**，不实现。

## 1. 目标与范围

- 契约：AuthProvider（login/logout/refresh/skinUrl）、TokenStore（加密读写）、AccountRepository；
- 实现：LocalOfflineAccountProvider（正式）、MockAuthProvider（仅测试/演示，禁生产）、
  FileAccountStore、EncryptedTokenStore（Android Keystore + AES-GCM）、SkinCache（ETag）；
- Profile ↔ Account 绑定（每 Profile 独立活跃账号；骨架期 profileId="default"）；
- MS OAuth2+PKCE 完整流程图 + TODO 清单 + 改动面评估（docs/design/step4-ms-oauth-plan.md）；
- 顺带接线启动按钮（Step 3 遗留）：门禁 → 账号 → 版本 → 拼装 → 拉起 的真实链路（每步诚实提示）。

## 2. 关键取舍

1. **Token 不随 Account 存**：Account（元数据，JSON 明文）与 token（EncryptedTokenStore，AES-GCM）
   分离——账号列表读取永远不接触密钥材料。
2. **离线账户限制显式标注**（UI 红色小字：无皮肤同步、无法联机、可能违反 EULA），
   创建对话框同样标注；**绝不做默认推荐**（无默认账号，未登录显示「未登录账号」）。
3. **EncryptedTokenStore 每次写新随机 IV**；读取失败视为无 token；JVM 单测用 FakeTokenStore
   （Keystore 依赖真机，手工步骤见 §4）。
4. **会话刷新窗口 60s**（AccountRepository.REFRESH_WINDOW_MS）：过期临近自动 provider.refresh 并回写；
   离线会话 expiresAt=null 永不触发。
5. **皮肤缓存 ETag**：304 复用本地；200 更新 png+etag；skinUrl=null（离线）→ null。
6. **改动面评估**：微软登录 = 仅新增 MicrosoftAuthProvider（+OAuth 客户端），其余模块零改动
   （评估表见 step4-ms-oauth-plan.md §1）。
7. **client_id 策略待用户决策**（硬约束「凭证不写客户端」vs Minecraft 生态事实「公共 client_id」，
   冲突已明确记录在方案文档 §3 首条）。

## 3. 目录

```
core-account/src/main/java/org/aclpe/core/account/
  model/Account.kt            # Account/AccountType/AuthTokens/AuthSession（Account 可序列化）
  contract/AuthProvider.kt    # 契约（login/logout/refresh/skinUrl）
  contract/TokenStore.kt      # 加密读写契约
  AccountRepository.kt        # 登录/登出/活跃账号/会话刷新
  provider/LocalOfflineAccountProvider.kt  # 本机 UUID，token 空
  provider/MockAuthProvider.kt             # 固定假 token/UUID（禁生产）
  store/AccountStore.kt       # 文件实现 + create 工厂（kotlinx）
  store/EncryptedTokenStore.kt# Android Keystore AES-GCM
  skin/SkinCache.kt           # ETag 缓存
core-account/src/test/.../    # 契约集成测试 9 例（内存/Fake 替身 + MockWebServer 皮肤）
app/.../ui/account/AccountViewModel.kt   # 账号状态 + 创建 + 提示消费
app/.../ui/screens/SettingsScreen.kt     # 账号区（限制标注/创建对话框/微软登录禁用）
app/.../ui/home/HomeViewModel.kt         # launch() 真实链路
docs/design/step4-ms-oauth-plan.md       # MS 方案（流程图/TODO/改动面）
```

## 4. 验证结果

- **单测 131/131 全绿**（core-account 新增 9：登录持久化/固定假身份/登出清理/Profile 隔离/
  空会话/过期刷新回写/皮肤 200/304/null）。
- **构建**：BUILD SUCCESSFUL。
- **手工（真机）**：
  1. 设置 → 账号 → 创建离线账户 → 显示昵称 + 「本地离线」+ 红色限制标注；
  2. 重启 App → 账号仍在（FileAccountStore 持久化）；
  3. 微软登录按钮置灰 + 预留说明文案；
  4. EncryptedTokenStore：离线账户下 files/accounts/<id>/tokens.enc 为密文（非明文 token）；
  5. 首页启动按钮：环境未就绪时置灰；就绪后点击按链路提示（当前会提示「尚未安装游戏版本」——诚实占位）。

## 5. 已知问题

1. 微软登录未实现（本期范围）；client_id 策略待决策（见方案文档）。
2. EncryptedTokenStore 依赖真机验证（JVM 单测用 Fake 替身，契约级覆盖）。
3. 骨架期 profileId 固定 "default"（core-profile 落地后随 Profile 切换——AccountStore 已按 profileId 建模）。
4. 启动链路第 3 步依赖「版本安装流水线」（Step 2 遗留范围），当前提示诚实占位。
5. MockAuthProvider 仅测试用：无运行时开关防止误入生产（依赖代码评审纪律），后续可加 BuildConfig 断言。
