package io.github.logan0116.bfilter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.logan0116.bfilter.domain.WhitelistUp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "bfilter")

/**
 * 白名单持久化。
 *
 * 用 DataStore + kotlinx.serialization 存一个 JSON 数组 —— 白名单是几十条量级的简单列表，
 * 不值得上数据库。注意 release 开了 R8，@Serializable 的 serializer 靠 proguard-rules.pro 保留。
 */
class WhitelistStore(private val context: Context) {

    private val key = stringPreferencesKey("whitelist")
    private val json = Json { ignoreUnknownKeys = true }

    val ups: Flow<List<WhitelistUp>> = context.dataStore.data.map { prefs ->
        decode(prefs[key])
    }

    /** 加入白名单；已存在则忽略（不覆盖用户原有的加入时间） */
    suspend fun add(up: WhitelistUp) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (current.any { it.mid == up.mid }) return@edit
            val next = current + up.copy(addedAt = System.currentTimeMillis())
            prefs[key] = json.encodeToString(next)
        }
    }

    suspend fun remove(mid: Long) {
        context.dataStore.edit { prefs ->
            val next = decode(prefs[key]).filterNot { it.mid == mid }
            prefs[key] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): List<WhitelistUp> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<WhitelistUp>>(raw) }.getOrDefault(emptyList())
    }
}
