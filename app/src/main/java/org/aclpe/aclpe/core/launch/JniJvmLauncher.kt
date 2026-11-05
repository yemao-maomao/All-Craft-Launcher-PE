package org.aclpe.aclpe.core.launch

import timber.log.Timber

/**
 * JNI 进程内 JVM 启动桥（仿造 FCL/Pojav 思路，自研实现）。
 *
 * 解决 Android 10+ W^X：不再用 ProcessBuilder exec java 子进程（会被 SELinux 禁止），
 * 而是在当前进程内 dlopen libjvm.so → JNI_CreateJavaVM 创建 JVM → 调用 Minecraft 主类。
 *
 * 对应 native 实现：app/src/main/cpp/jvm_bridge.c
 */
class JniJvmLauncher {

    init {
        System.loadLibrary("aclpe_jvm")
    }

    /**
     * 启动 JVM 并调用 Minecraft 主类（阻塞，须在独立线程调用）。
     * @return 0 = 正常（main 返回后）；<0 = 启动失败错误码（-1 dlopen、-2 dlsym、-5 CreateJavaVM、-6 FindClass…）
     */
    external fun launchJvm(
        jvmLibPath: String,
        classpath: String,
        mainClass: String,
        jvmArgs: Array<String>,
        gameArgs: Array<String>,
    ): Int

    /** 重定向 stdout/stderr 到 logcat（游戏日志可查）。 */
    external fun redirectStdio(): Int

    /** 环境变量注入（JAVA_HOME / LD_LIBRARY_PATH 等）。 */
    external fun setenv(name: String, value: String)

    /** 安装退出陷阱：游戏 System.exit 时回传退出码到 [onGameExit]。 */
    external fun setupExitTrap(bridge: Any)

    /** 游戏退出回调（native nominal_exit 经 JNI 调用）。 */
    fun onGameExit(code: Int) {
        Timber.i("game exit via JNI trap: code=%d", code)
    }

    companion object {
        const val TAG: String = "JniJvmLauncher"
    }
}
