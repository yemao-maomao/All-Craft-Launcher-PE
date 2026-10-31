package org.aclpe.aclpe.core.env

import android.content.Context
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.EnvBootstrap
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.probe.EnvProbe
import org.aclpe.core.env.probe.RealProcessExecutor
import org.aclpe.core.env.store.EnvDatabase
import org.aclpe.core.env.store.EnvStore
import org.aclpe.core.env.store.RoomEnvStore

/**
 * core-env 依赖装配（骨架期手工注入；后续模块增多再考虑统一 DI）。
 * 真实下载（DownloadScheduler）由 Step 2 core-download 替换 [Step2PendingDownloadScheduler]。
 */
class EnvDependencies(context: Context) {

    val layout: EnvLayout = EnvLayout(context.filesDir)

    val store: EnvStore = RoomEnvStore(EnvDatabase.get(context).envItemDao())

    val probe: EnvProbe = EnvProbe(RealProcessExecutor())

    val scheduler: DownloadScheduler = Step2PendingDownloadScheduler()

    val bootstrap: EnvBootstrap = EnvBootstrap(probe, scheduler, store, layout)
}
