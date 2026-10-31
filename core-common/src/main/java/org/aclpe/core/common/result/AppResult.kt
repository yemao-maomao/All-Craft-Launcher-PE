package org.aclpe.core.common.result

import kotlinx.coroutines.CancellationException

/**
 * 统一错误类型：全项目错误建模的根，各模块只允许复用或按需扩展 [code]，禁止各造一套。
 */
sealed class AppError(
    val code: String,
    val message: String? = null,
    val cause: Throwable? = null,
) {
    /** 网络不可达 / 超时 / 重试耗尽（HTTP 状态码语义并入 code，如 network.http_429）。 */
    class Network(code: String = "network", message: String? = null, cause: Throwable? = null) : AppError(code, message, cause)

    /** 本地 I/O 失败（磁盘满、权限、文件损坏）。 */
    class Io(code: String = "io", message: String? = null, cause: Throwable? = null) : AppError(code, message, cause)

    /** 环境组件缺失/损坏（core-env 的 EnvProbe 结果映射到本类）。 */
    class Env(code: String = "env", message: String? = null, cause: Throwable? = null) : AppError(code, message, cause)

    /** 解析失败（JSON / rules / 版本文件）。 */
    class Parse(code: String = "parse", message: String? = null, cause: Throwable? = null) : AppError(code, message, cause)

    /** 任务被取消（协程取消 / 用户取消下载），不算失败。 */
    data object Cancelled : AppError("cancelled")

    /** 未分类错误。 */
    class Unknown(message: String? = null, cause: Throwable? = null) : AppError("unknown", message, cause)
}

/** 统一结果类型：全项目业务返回值的根（core-env/core-download/core-launch 等一律返回本类型）。 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>

    val isOk: Boolean get() = this is Ok
    val isErr: Boolean get() = this is Err

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): AppError? = (this as? Err)?.error
}

/** 成功值映射；失败透传。 */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

/** 成功时执行副作用，返回自身以链式调用。 */
inline fun <T> AppResult<T>.onOk(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Ok) block(value)
    return this
}

/** 失败时执行副作用，返回自身以链式调用。 */
inline fun <T> AppResult<T>.onErr(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Err) block(error)
    return this
}

/**
 * 捕获同步异常为 [AppResult]；协程取消原样上抛（不吞取消语义）。
 * 注意：仅用于同步代码块；suspend 调用请显式 try/catch。
 */
inline fun <T> resultOf(block: () -> T): AppResult<T> = try {
    AppResult.Ok(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    AppResult.Err(AppError.Unknown(cause = t))
}
