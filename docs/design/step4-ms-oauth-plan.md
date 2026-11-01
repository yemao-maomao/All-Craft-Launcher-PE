# Step 4 预留：微软登录（MS OAuth2 + PKCE）接入方案

> 状态：**仅设计，本期不实现**（用户规格：干净接口 + 完整流程图 + TODO 占位）。
> 硬性约束：认证只走 Microsoft OAuth 2.0 + PKCE 官方流；不做离线破解、不做 token 共享、日志绝不出现 token。

## 1. 改动面评估（结论：仅新增一个 Provider 实现）

| 改动点 | 内容 |
|---|---|
| 新增 `MicrosoftAuthProvider : AuthProvider` | 唯一新增业务类；实现 login/logout/refresh/skinUrl |
| 新增 `MsOAuthClient`（可选拆分） | 负责 authorize/token 端点交互与 PKCE 计算 |
| `EncryptedTokenStore` | 零改动（契约不变） |
| `AccountRepository` | 零改动 |
| 启动命令拼装（core-launch） | 零改动（token 经 LaunchAuth 传入，脱敏门面兜底日志） |
| 皮肤缓存（SkinCache） | 零改动（skinUrl 契约已含 ETag 责任） |
| UI | 仅「账号」区启用登录按钮（已预留禁用态与标注） |

## 2. 流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant A as ACL-PE(App)
    participant B as 系统浏览器(CustomTab)
    participant M as Microsoft login.live.com
    U->>A: 点击「微软登录」
    A->>A: 生成 code_verifier(43-128) → code_challenge=S256(verifier)
    A->>B: https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize
            ?client_id=<Azure 注册的 clientId>&response_type=code
            &redirect_uri=msauth://org.aclpe.aclpe/<签名哈希>
            &scope=XboxLive.signin%20offline_access&code_challenge=...&code_challenge_method=S256
    U->>M: 输入凭据并授权
    M->>B: 302 redirect_uri?code=...
    B->>A: 回调（app link scheme 拦截）
    A->>M: POST /token (code + verifier + client_id + redirect_uri)  ← 换取 access/refresh token
    A->>M: POST https://user.auth.xboxlive.com/user/authenticate → XBL token
    A->>M: POST https://xsts.auth.xboxlive.com/xsts/authorize → XSTS token
    A->>M: POST https://api.minecraftservices.com/authentication/login_with_xbox → MC access token
    A->>M: GET https://api.minecraftservices.com/minecraft/profile → 游戏 UUID + 皮肤纹理
    A->>A: EncryptedTokenStore.save → AccountRepository.setActive
```

## 3. 待填 TODO（实现清单）

- [ ] Azure AD 应用注册（用户自己的 tenant，client_id 仅存构建配置/服务端，绝不硬编码进 APK 明文区——用户规格硬约束 3：client_id 等凭证不写客户端。**此条冲突需决策**：Minecraft 生态事实是官方 launcher 携带公共 client_id；备选：公开 client_id 常量 + PKCE 仍安全。等待用户拍板。）
- [ ] redirect_uri：`msauth://org.aclpe.aclpe/<APK 签名 SHA256 base64>`；AndroidManifest 声明对应 scheme intent-filter
- [ ] CustomTab 打开 authorize；回调解析 code；错误分支（user_cancel/网络）
- [ ] PKCE：code_verifier 生成（安全随机 43–128 字符）、S256 challenge；verifier 内存持有（进程死亡需重新发起）
- [ ] 刷新策略：access_token 过期（expiresAt - 5min 窗口，AccountRepository.REFRESH_WINDOW_MS 已预留）→ refresh_token 换新；失败 → 置为需重新登录
- [ ] 皮肤：GET minecraft/services profile → skins[].url（SkinCache 契约已含 ETag）
- [ ] Token 日志防线：登录链路所有日志经 LogRedactor（JWT 形态已覆盖）+ 显式禁止打印 token

## 4. 风险与已知限制

1. client_id 策略待决策（§3 首条）。
2. redirect_uri 绑定签名哈希：换签名需同步更新 Azure 配置与代码常量。
3. Xbox/XSTS/MC 三层换发共 4 个 HTTP 端点，任一限流/失败需完整重试与降级文案。
4. 设备无浏览器（定制 ROM）时 CustomTab 不可用 → 需 WebView 兜底或明确报错。
