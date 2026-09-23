package io.github.logan0116.bfilter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "feed_settings")

/**
 * 观看偏好。
 *
 * 默认值（只看近 3 天 + 过滤 1 分钟以内）是刻意的：
 * 白名单一多，时间线会变成一条刷不到头的流 —— 那就又回到"被信息流拖着走"的老问题上了。
 * 默认筛短，列表才有尽头。
 */
data class FeedSettings(
    /** 只看最近几天；0 表示不限 */
    val recentDays: Int = DEFAULT_RECENT_DAYS,
    /** 过滤掉短于该秒数的视频；0 表示不过滤 */
    val minDurationSec: Int = DEFAULT_MIN_DURATION_SEC
) {
    val filtersNothing: Boolean get() = recentDays <= 0 && minDurationSec <= 0

    companion object {
        const val DEFAULT_RECENT_DAYS = 3
        const val DEFAULT_MIN_DURATION_SEC = 60

        val RECENT_DAY_CHOICES = listOf(1, 3, 7, 30, 0)
        val MIN_DURATION_CHOICES = listOf(0, 30, 60, 180, 600)
    }
}

class SettingsStore(private val context: Context) {

    private val recentDaysKey = intPreferencesKey("recent_days")
    private val minDurationKey = intPreferencesKey("min_duration_sec")

    val settings: Flow<FeedSettings> = context.settingsDataStore.data.map { prefs ->
        FeedSettings(
            recentDays = prefs[recentDaysKey] ?: FeedSettings.DEFAULT_RECENT_DAYS,
            minDurationSec = prefs[minDurationKey] ?: FeedSettings.DEFAULT_MIN_DURATION_SEC
        )
    }

    suspend fun setRecentDays(days: Int) {
        context.settingsDataStore.edit { prefs -> prefs[recentDaysKey] = days }
    }

    suspend fun setMinDuration(seconds: Int) {
        context.settingsDataStore.edit { prefs -> prefs[minDurationKey] = seconds }
    }
}
