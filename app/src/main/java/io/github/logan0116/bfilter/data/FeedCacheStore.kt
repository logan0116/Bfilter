package io.github.logan0116.bfilter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.logan0116.bfilter.domain.VideoItem
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.cacheDataStore by preferencesDataStore(name = "feed_cache")

@Serializable
data class CachedFeed(
    val items: List<VideoItem>,
    val savedAt: Long
)

/**
 * 时间线本地缓存。
 *
 * 这不是"优化"，是**必需品**：B 站对未登录请求的频控是按分钟级窗口放行的
 * （见 README 的接口实测），每次打开 app 都去拉必然失败。所以策略是
 * 拉一次、存下来，之后启动直接读缓存，只有用户主动刷新或白名单变动才打网络。
 */
class FeedCacheStore(private val context: Context) {

    private val itemsKey = stringPreferencesKey("items")
    private val savedAtKey = longPreferencesKey("saved_at")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): CachedFeed? {
        val prefs = context.cacheDataStore.data.first()
        val raw = prefs[itemsKey] ?: return null
        val items = runCatching { json.decodeFromString<List<VideoItem>>(raw) }.getOrNull() ?: return null
        return CachedFeed(items, prefs[savedAtKey] ?: 0L)
    }

    suspend fun save(items: List<VideoItem>) {
        if (items.isEmpty()) return
        context.cacheDataStore.edit { prefs ->
            prefs[itemsKey] = json.encodeToString(items)
            prefs[savedAtKey] = System.currentTimeMillis()
        }
    }

    suspend fun clear() {
        context.cacheDataStore.edit { prefs ->
            prefs.remove(itemsKey)
            prefs.remove(savedAtKey)
        }
    }
}
