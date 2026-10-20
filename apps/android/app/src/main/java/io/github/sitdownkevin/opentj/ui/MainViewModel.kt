package io.github.sitdownkevin.opentj.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sitdownkevin.opentj.core.Credentials
import io.github.sitdownkevin.opentj.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountForm(
    val username: String = "",
    val password: String = "",
    val method: String = "LDAP3",
    val remember: Boolean = false,
    val loading: Boolean = true,
    val authorizing: Boolean = false,
    val error: String? = null,
) {
    override fun toString() = "AccountForm(redacted)"
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CredentialStore(application)
    private val mutable = MutableStateFlow(AccountForm())
    val form = mutable.asStateFlow()
    private var pending: Credentials? = null

    init {
        viewModelScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { store.load() }
                mutable.value = AccountForm(account.username, account.password, account.method, account.remember, loading = false)
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, error = "已保存的密码无法读取，请重新输入") }
            }
        }
    }

    fun username(value: String) { mutable.update { it.copy(username = value.take(256), error = null) } }
    fun password(value: String) { mutable.update { it.copy(password = value.take(4096), error = null) } }
    fun method(value: String) { mutable.update { it.copy(method = value, error = null) } }
    fun remember(value: Boolean) {
        mutable.update { it.copy(remember = value) }
        if (!value) viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.forgetPassword() } }
            catch (_: Exception) { mutable.update { it.copy(error = "无法删除已保存的密码，请重试") } }
        }
    }

    suspend fun prepareConnection(): Boolean {
        val value = mutable.value
        if (value.loading || value.authorizing) return false
        if (value.username.isBlank() || value.password.isEmpty()) {
            mutable.update { it.copy(error = "请输入账号和密码") }
            return false
        }
        mutable.update { it.copy(authorizing = true, error = null) }
        val credentials = Credentials(value.username.trim(), value.password, value.method)
        return try {
            withContext(Dispatchers.IO) { store.save(credentials, value.remember) }
            pending = credentials
            true
        } catch (error: CancellationException) {
            mutable.update { it.copy(authorizing = false) }
            throw error
        } catch (_: Exception) {
            mutable.update { it.copy(authorizing = false, error = "无法安全保存账号设置，请关闭记住密码后重试") }
            false
        }
    }

    fun takeCredentials(): Credentials? {
        val value = pending
        pending = null
        mutable.update { it.copy(authorizing = false) }
        return value
    }

    fun permissionDenied() {
        pending = null
        mutable.update { it.copy(authorizing = false, error = "未获得系统 VPN 授权，可再次点击连接") }
    }

    fun startFailed() {
        mutable.update { it.copy(authorizing = false, error = "无法启动连接，请重试") }
    }
}
