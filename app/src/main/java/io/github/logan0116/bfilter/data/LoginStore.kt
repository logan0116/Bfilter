package io.github.logan0116.bfilter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.logan0116.bfilter.domain.AccountInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.loginDataStore by preferencesDataStore(name = "login")

/**
 * 登录凭证的本地存储。
 *
 * ⚠️ `SESSDATA` 的效力等同于账号密码：**只落本地 DataStore、只出现在请求头里**，
 * 绝不写进日志、绝不上报、也没有任何导出入口。退出登录会整体清除这个文件的内容。
 */
class LoginStore(private val context: Context) {

    private val cookieKey = stringPreferencesKey("cookie")
    private val accountKey = stringPreferencesKey("account")
    private val json = Json { ignoreUnknownKeys = true }

    /** 可直接拼进 `Cookie:` 请求头的串；未登录为 null */
    val cookie: Flow<String?> = context.loginDataStore.data.map { it[cookieKey] }

    /** 展示用的账号信息（昵称/头像/mid） */
    val account: Flow<AccountInfo?> = context.loginDataStore.data.map { prefs ->
        prefs[accountKey]?.let { raw ->
            runCatching { json.decodeFromString<AccountInfo>(raw) }.getOrNull()
        }
    }

    suspend fun save(cookie: String, account: AccountInfo) {
        context.loginDataStore.edit { prefs ->
            prefs[cookieKey] = cookie
            prefs[accountKey] = json.encodeToString(
                account.copy(loggedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun clear() {
        context.loginDataStore.edit { prefs ->
            prefs.remove(cookieKey)
            prefs.remove(accountKey)
        }
    }
}
