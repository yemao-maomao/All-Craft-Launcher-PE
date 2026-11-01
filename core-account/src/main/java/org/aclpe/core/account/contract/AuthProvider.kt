package org.aclpe.core.account.contract

import org.aclpe.core.account.model.AccountType
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.common.result.AppResult

/**
 * 认证提供者契约（用户规格 Step 4）。
 * 本期实现：LocalOfflineAccountProvider（正式）与 MockAuthProvider（仅测试/演示，禁用于生产）。
 * 未来微软登录 = 新增一个 MicrosoftAuthProvider 实现本契约即可，
 * 其余模块（启动命令拼装、Profile、皮肤缓存）全部基于契约开发、无需改动（改动面评估见
 * docs/design/step4-ms-oauth-plan.md）。
 */
interface AuthProvider {

    val type: AccountType

    /** 登录，返回会话。 */
    suspend fun login(): AppResult<AuthSession>

    /** 登出（服务端吊销/本地清理；具体语义由实现决定）。 */
    suspend fun logout(session: AuthSession): AppResult<Unit>

    /** 刷新 token（离线账户无 token，直接返回原会话）。 */
    suspend fun refresh(session: AuthSession): AppResult<AuthSession>

    /** 皮肤 URL；无皮肤/离线返回 null（皮肤缓存责任在 AccountRepository 侧）。 */
    suspend fun skinUrl(session: AuthSession): AppResult<String?>
}
