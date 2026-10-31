package org.aclpe.core.env.gate

import org.aclpe.core.env.fsm.EnvItemState
import org.aclpe.core.env.store.EnvItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LaunchGate] 启动门禁测试（硬性约束：未完成补齐不允许进入游戏）。 */
class LaunchGateTest {

    private fun item(component: String, state: EnvItemState): EnvItemEntity =
        EnvItemEntity(component = component, state = state.name)

    @Test
    fun `必需组件为空时不允许启动`() {
        assertFalse(LaunchGate.canLaunch(emptyList(), emptySet()))
        assertFalse(LaunchGate.canLaunch(listOf(item("jre:17", EnvItemState.READY)), emptySet()))
    }

    @Test
    fun `全部就绪才放行`() {
        val items = listOf(
            item("jre:17", EnvItemState.READY),
            item("natives:h1", EnvItemState.READY),
        )
        assertTrue(LaunchGate.canLaunch(items, setOf("jre:17", "natives:h1")))
    }

    @Test
    fun `任一未就绪即拦截`() {
        val items = listOf(
            item("jre:17", EnvItemState.READY),
            item("natives:h1", EnvItemState.DOWNLOADING),
        )
        assertFalse(LaunchGate.canLaunch(items, setOf("jre:17", "natives:h1")))
    }

    @Test
    fun `blockers 列出未就绪组件`() {
        val items = listOf(
            item("jre:17", EnvItemState.READY),
            item("jre:21", EnvItemState.MISSING),
        )
        assertEquals(listOf("jre:21"), LaunchGate.blockers(items, setOf("jre:17", "jre:21")))
    }
}
