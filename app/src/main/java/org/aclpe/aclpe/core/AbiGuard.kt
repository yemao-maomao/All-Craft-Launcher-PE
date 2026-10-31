package org.aclpe.aclpe.core

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import org.aclpe.aclpe.R

/** 32 位设备守卫：arm64 only（硬性约束），非 arm64-v8a 设备直接提示不支持并退出。 */
object AbiGuard {

    fun isArm64Supported(): Boolean = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    fun showUnsupportedDialog(activity: Activity, onExit: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.abi_unsupported_title)
            .setMessage(R.string.abi_unsupported_message)
            .setCancelable(false)
            .setPositiveButton(R.string.exit) { _, _ -> onExit() }
            .show()
    }
}
