package io.github.logan0116.bfilter.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.logan0116.bfilter.BfilterApp
import io.github.logan0116.bfilter.data.FeedSettings
import io.github.logan0116.bfilter.domain.VideoItem
import io.github.logan0116.bfilter.domain.WhitelistUp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUiState(
    val ups: List<WhitelistUp> = emptyList(),
    /** 过滤后、真正展示的时间线 */
    val items: List<VideoItem> = emptyList(),
    /** 拉回来的原始结果；改设置时拿它就地重算，不用重新联网 */
    val allItems: List<VideoItem> = emptyList(),
    val settings: FeedSettings = FeedSettings(),
    val failures: List<String> = emptyList(),
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    /** 缓存写入时间（毫秒），0 表示当前内容不是来自缓存 */
    val cachedAt: Long = 0L
) {
    /** 明明拉到了内容，却被筛没了 —— 得单独说一句，否则会被当成"拉取失败" */
    val filteredOutEverything: Boolean
        get() = items.isEmpty() && allItems.isNotEmpty()
}

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BfilterApp).repository
    private val store = (app as BfilterApp).whitelistStore
    private val settingsStore = (app as BfilterApp).settingsStore

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var lastMids: List<Long>? = null

    init {
        viewModelScope.launch {
            // 先放本地缓存。B 站对未登录请求是分钟级频控（见 README），
            // 每次启动都打网络必然吃 412/-799，所以默认显示缓存、不自动联网。
            repo.cachedFeed()?.let { cached ->
                if (cached.items.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            allItems = cached.items,
                            items = applyFilter(cached.items, it.settings),
                            cachedAt = cached.savedAt,
                            loadedOnce = true
                        )
                    }
                }
            }

            store.ups.collect { ups ->
                val mids = ups.map { it.mid }
                val changed = lastMids != null && lastMids != mids
                lastMids = mids
                _state.update { it.copy(ups = ups) }

                if (mids.isEmpty()) {
                    _state.update {
                        it.copy(
                            allItems = emptyList(),
                            items = emptyList(),
                            failures = emptyList(),
                            cachedAt = 0L,
                            loadedOnce = true
                        )
                    }
                    return@collect
                }
                // 只在「还没有任何内容」或「白名单刚变动」时自动联网 —— 其余交给用户手动刷新
                if (changed || !_state.value.loadedOnce) refresh()
            }
        }

        // 设置一变就地重算，无需重新联网
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                _state.update { it.copy(settings = s, items = applyFilter(it.allItems, s)) }
            }
        }
    }

    /** 按观看偏好筛选：只看最近 N 天 + 过滤短视频 */
    private fun applyFilter(all: List<VideoItem>, settings: FeedSettings): List<VideoItem> {
        val cutoff = if (settings.recentDays > 0) {
            System.currentTimeMillis() / 1000 - settings.recentDays * 86_400L
        } else {
            0L
        }
        return all.filter { item ->
            (cutoff == 0L || item.pubDateSec >= cutoff) &&
                (settings.minDurationSec <= 0 || item.durationSec >= settings.minDurationSec)
        }
    }

    fun refresh() {
        val ups = _state.value.ups
        refreshJob?.cancel()
        if (ups.isEmpty()) {
            _state.update {
                it.copy(allItems = emptyList(), items = emptyList(), failures = emptyList(), loading = false, loadedOnce = true)
            }
            return
        }
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = runCatching { repo.loadFeed(ups) }
            result
                .onSuccess { feed ->
                    _state.update {
                        it.copy(
                            allItems = feed.items,
                            items = applyFilter(feed.items, it.settings),
                            failures = feed.failures,
                            loading = false,
                            loadedOnce = true,
                            cachedAt = if (feed.items.isEmpty()) it.cachedAt else System.currentTimeMillis()
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            failures = listOf("加载失败：${e.message ?: e.javaClass.simpleName}"),
                            loading = false,
                            loadedOnce = true
                        )
                    }
                }
        }
    }
}

/** 头部副标题：几个 UP 主 + 内容来路 + 当前筛选条件 */
private fun feedSubtitle(state: FeedUiState): String? {
    if (state.ups.isEmpty()) return null

    val parts = mutableListOf("${state.ups.size} 个 UP 主")
    when {
        state.loading -> parts += "更新中…"
        state.cachedAt > 0 -> parts += "更新于 ${formatRelativeTime(state.cachedAt / 1000)}"
    }

    if (!state.settings.filtersNothing) {
        val label = buildList {
            if (state.settings.recentDays > 0) add("近 ${state.settings.recentDays} 天")
            val mins = state.settings.minDurationSec
            if (mins > 0) add(if (mins < 60) "≥${mins}秒" else "≥${mins / 60}分")
        }.joinToString("/")
        if (label.isNotBlank()) parts += label
    }

    return parts.joinToString(" · ")
}

/**
 * 主页 —— 整个 app 唯一的一条信息流。
 * 没有推荐、没有热搜、没有"接下来播放"，因为它只由白名单 UP 主的投稿组成。
 */
@Composable
fun FeedScreen(
    onPlay: (VideoItem) -> Unit,
    onGoWhitelist: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "只看关注",
            subtitle = feedSubtitle(state)
        ) {
            IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        FailureBanner(state.failures)

        when {
            state.ups.isEmpty() -> EmptyHintWithAction(
                text = "还没有添加 UP 主\n这里只会出现你手动加进来的人",
                actionText = "去添加",
                onAction = onGoWhitelist
            )

            state.loading && state.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.loadedOnce && state.items.isEmpty() -> EmptyHint(
                // 「筛没了」和「确实没更新」必须分开说，否则用户会以为拉取坏了
                if (state.filteredOutEverything) {
                    "当前筛选条件下没有内容\n（已筛掉 ${state.allItems.size} 条，可在「账号」页调整）"
                } else {
                    "白名单里的 UP 主最近没有投稿"
                }
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(state.items, key = { it.bvid }) { video ->
                    VideoCard(video = video, onClick = { onPlay(video) })
                }
            }
        }
    }
}

@Composable
private fun EmptyHintWithAction(    text: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onAction) { Text(actionText) }
    }
}

@Composable
private fun VideoCard(video: VideoItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 148.dp, height = 84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (video.cover.isNotBlank()) {
                AsyncImage(
                    model = video.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            val duration = formatDuration(video.durationSec)
            if (duration.isNotBlank()) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            androidx.compose.ui.graphics.Color(0x99000000),
                            RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = video.authorName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = listOf(formatCount(video.playCount) + " 播放", formatRelativeTime(video.pubDateSec))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
