package org.aclpe.aclpe.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.core.account.AccountRepository
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.provider.LocalOfflineAccountProvider
import org.aclpe.core.common.result.AppResult

/**
 * 账号 ViewModel（Step 4）：
 * - 离线账户创建（本机 UUID，UI 明确标注限制，绝不做成默认推荐）；
 * - 微软登录按钮预留禁用（MS OAuth2+PKCE 方案见 docs/design/step4-ms-oauth-plan.md）。
 * 骨架期 profileId 固定 "default"（core-profile 落地后随 Profile 切换）。
 */
class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: AccountRepository = (application as ACLApplication).envDependencies.accountRepository

    private val _activeAccount = MutableStateFlow<Account?>(null)
    val activeAccount: StateFlow<Account?> = _activeAccount

    val accounts: StateFlow<List<Account>> = repository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _activeAccount.value = repository.activeAccount(PROFILE_ID).getOrNull()
        }
    }

    fun createOfflineAccount(name: String) {
        val trimmed: String = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = "名字不能为空"
            return
        }
        viewModelScope.launch {
            val result: AppResult<Account> = repository.login(LocalOfflineAccountProvider(trimmed), PROFILE_ID)
            _message.value = if (result.isOk) "已创建离线账户：${result.getOrNull()!!.username}" else "创建失败"
            refresh()
        }
    }

    /** UI 消费一次性提示后调用（防重复 toast）。 */
    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        const val PROFILE_ID: String = "default"
    }
}
