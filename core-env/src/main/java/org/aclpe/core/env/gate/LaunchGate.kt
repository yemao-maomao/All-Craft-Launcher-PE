package org.aclpe.core.env.gate

import org.aclpe.core.env.fsm.EnvItemState
import org.aclpe.core.env.store.EnvItemEntity

/**
 * 启动门禁（硬性约束：未完成环境补齐前不允许进入游戏）。
 * 全部必需组件 READY 才放行；纯函数，UI 订阅 Store Flow 实时派生。
 */
object LaunchGate {

    fun canLaunch(items: List<EnvItemEntity>, requiredComponents: Set<String>): Boolean {
        if (requiredComponents.isEmpty()) return false   // 无版本选中时不允许启动
        val byComponent: Map<String, EnvItemEntity> = items.associateBy { it.component }
        return requiredComponents.all { component ->
            byComponent[component]?.state == EnvItemState.READY.name
        }
    }

    /** 返回缺失/未就绪组件清单（用于启动按钮点击提示）。 */
    fun blockers(items: List<EnvItemEntity>, requiredComponents: Set<String>): List<String> {
        val byComponent: Map<String, EnvItemEntity> = items.associateBy { it.component }
        return requiredComponents.filter { byComponent[it]?.state != EnvItemState.READY.name }
    }
}
