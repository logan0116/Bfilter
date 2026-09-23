package io.github.logan0116.bfilter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.playbackDataStore by preferencesDataStore(name = "playback_positions")

/**
 * 断点续播：记住每个视频看到哪儿了。
 *
 * 用 `bvid` 做键，值是毫秒。位置存成 DataStore 里的一堆键值对 —— 不搞数据库，
 * 因为这个数据的特点就是「随时可丢」：丢了顶多从头看，不值得为它引入复杂度。
 *
 * 另外**只在有意义时记**：太靠前（<15s）或已接近结尾（>95%）都不记，
 * 否则会出现「点开视频直接跳到片尾」这种更烦人的体验。
 */
class PlaybackPositionStore(private val context: Context) {

    private fun keyFor(bvid: String) = longPreferencesKey("pos_$bvid")

    suspend fun positionOf(bvid: String): Long =
        context.playbackDataStore.data.first()[keyFor(bvid)] ?: 0L

    /** 保存进度；durationMs 用于判断是否值得记 */
    suspend fun save(bvid: String, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_SAVE_MS) return
        if (durationMs > 0 && positionMs > durationMs * 0.95) {
            // 已经看到结尾了，下次应该从头开始
            clear(bvid)
            return
        }
        context.playbackDataStore.edit { prefs -> prefs[keyFor(bvid)] = positionMs }
    }

    suspend fun clear(bvid: String) {
        context.playbackDataStore.edit { prefs -> prefs.remove(keyFor(bvid)) }
    }

    private companion object {
        /** 前 15 秒不值得记 —— 记了也只是让"从头看"变成"从 12 秒看" */
        const val MIN_SAVE_MS = 15_000L
    }
}
