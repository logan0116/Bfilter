package io.github.logan0116.bfilter.data

import io.github.logan0116.bfilter.data.remote.BiliApi
import io.github.logan0116.bfilter.data.remote.BiliHttp
import io.github.logan0116.bfilter.domain.VideoItem
import io.github.logan0116.bfilter.domain.WhitelistUp

/**
 * 把白名单 UP 主的投稿聚合成**唯一的一条信息流**。
 *
 * 设计要点：
 *  - 串行拉取：B 站对未登录请求有分钟级频控，并发只会更快触发 -799/412。
 *  - 单个 UP 失败不影响其他 UP，失败原因如实上报，不静默吞掉。
 *  - 只有拿到内容才写缓存，绝不用"空结果/失败"覆盖上一次的好数据。
 */
class VideoRepository(private val cache: FeedCacheStore) {

    data class FeedResult(
        val items: List<VideoItem>,
        val failures: List<String>
    )

    /** 每个 UP 拉最近多少条 */
    private val perUpLimit = 10

    suspend fun loadFeed(ups: List<WhitelistUp>): FeedResult {
        val items = mutableListOf<VideoItem>()
        // 按原因聚合：断网时 7 个 UP 会给出 7 条一模一样的错误，逐条列出只会糊满屏幕
        val failuresByReason = LinkedHashMap<String, MutableList<String>>()

        for (up in ups.sortedBy { it.addedAt }) {
            runCatching { BiliApi.userVideos(up.mid, pageSize = perUpLimit) }
                .onSuccess { items += it }
                .onFailure { e ->
                    val reason = when {
                        e is BiliHttp.BiliException && e.isRateLimited -> "B 站限流，稍后点刷新重试"
                        e is BiliHttp.BiliException && e.isRiskBlocked -> "被 B 站风控拦截，稍后重试"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    failuresByReason.getOrPut(reason) { mutableListOf() } += up.name
                }
        }

        val failures = failuresByReason.map { (reason, names) ->
            if (names.size == 1) {
                "${names.first()}：$reason"
            } else {
                val shown = names.take(3).joinToString("、")
                val more = if (names.size > 3) " 等 ${names.size} 个" else ""
                "$shown$more：$reason"
            }
        }

        val sorted = items.sortedByDescending { it.pubDateSec }

        if (sorted.isNotEmpty()) {
            cache.save(sorted)
            return FeedResult(items = sorted, failures = failures)
        }

        // 空结果有两种可能：UP 主真的没发新东西，或者撞上了 B 站的**软限流**
        // （同一 UP 短时间重复请求会返回 code 0 但 items 为空 —— 实测过）。
        // 这两种情况在响应里无法区分，所以有缓存就退回缓存：宁可显示旧内容，
        // 也不能把"没拉到"显示成"没有投稿"。
        val cached = cache.read()
        if (cached != null && cached.items.isNotEmpty()) {
            return FeedResult(
                items = cached.items,
                failures = failures + "这次没拉到新内容，显示的是 ${formatCachedAge(cached.savedAt)}"
            )
        }

        return FeedResult(items = emptyList(), failures = failures)
    }

    private fun formatCachedAge(savedAt: Long): String {
        val minutes = (System.currentTimeMillis() - savedAt) / 60_000
        return when {
            minutes < 1 -> "刚刚的缓存"
            minutes < 60 -> "${minutes} 分钟前的缓存"
            minutes < 60 * 24 -> "${minutes / 60} 小时前的缓存"
            else -> "${minutes / (60 * 24)} 天前的缓存"
        }
    }

    suspend fun cachedFeed(): CachedFeed? = cache.read()

    suspend fun searchUps(keyword: String) = BiliApi.searchUsers(keyword)

    suspend fun findUpByUid(mid: Long) = BiliApi.userCard(mid)
}
