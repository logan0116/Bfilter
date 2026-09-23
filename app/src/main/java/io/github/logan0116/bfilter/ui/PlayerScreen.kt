package io.github.logan0116.bfilter.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import io.github.logan0116.bfilter.BfilterApp
import io.github.logan0116.bfilter.data.remote.BiliApi
import io.github.logan0116.bfilter.data.remote.BiliHttp
import io.github.logan0116.bfilter.domain.VideoItem
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 播放请求必须带 Referer，否则 B 站的视频 CDN 会拒绝 */
private const val PLAY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * 播放页。
 *
 * 产品上刻意保持"死路一条"：播完即停，没有自动连播、没有相关推荐、没有下一个视频。
 * 想看别的必须自己返回列表再选 —— 这一道摩擦就是过滤器的一部分。
 *
 * 注：这里的 opt-in 必须用 `androidx.annotation.OptIn` 而不是 Kotlin 的 `@OptIn`
 * （更不能用 `@file:OptIn`）—— media3 的 `@UnstableApi` 由 AndroidX 的 lint 检查
 * `UnsafeOptInUsageError` 把关，而 `androidx.annotation.OptIn` 没有 FILE 目标，
 * 所以只能标在这个用到了 media3 API 的函数上。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    video: VideoItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    var status by remember { mutableStateOf("正在获取播放地址…") }
    var isError by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("") }

    // 只有真的在缓冲时才该转圈。之前把显示条件写成了「没出错」，
    // 于是视频都播起来了那个环还在转 —— 这是它一直转的原因。
    var isBuffering by remember { mutableStateOf(true) }

    // 倍速只做展示提示；真值以 player.playbackParameters 为准，避免闭包读到旧状态
    var speedLabel by remember { mutableStateOf("") }

    // 断点续播：要等播放器 READY 才能 seek，先把它记下来
    var pendingSeekMs by remember { mutableStateOf(0L) }

    val context = LocalContext.current
    val app = context.applicationContext as BfilterApp
    val positionStore = app.playbackPositionStore

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .build()
            .apply { playWhenReady = true }
    }

    // 上次看到哪儿了
    LaunchedEffect(video.bvid) {
        pendingSeekMs = positionStore.positionOf(video.bvid)
    }

    // 每 5 秒落一次进度；退出时的最后一次在 onDispose 里补
    LaunchedEffect(video.bvid) {
        while (true) {
            delay(5_000)
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (pos > 0 && dur > 0) positionStore.save(video.bvid, pos, dur)
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                isError = true
                isBuffering = false
                status = "播放出错：${error.errorCodeName}"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE

                // 必须等 READY 再 seek：prepare 尚未完成时 seek 会被丢掉
                if (playbackState == Player.STATE_READY && pendingSeekMs > 0L) {
                    exoPlayer.seekTo(pendingSeekMs)
                    status = "已跳到上次位置 ${formatDuration((pendingSeekMs / 1000).toInt())}"
                    pendingSeekMs = 0L
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) isBuffering = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            // 退出时补记一次进度：可能距上次定期保存还不到 5 秒。
            // 这里不能用 Composable 的作用域 —— 它马上就会被取消。
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (pos > 0 && dur > 0) {
                app.launchInBackground { positionStore.save(video.bvid, pos, dur) }
            }
            exoPlayer.release()
        }
    }

    LaunchedEffect(video.bvid) {
        runCatching {
            val detail = BiliApi.videoDetail(video.bvid)
            val urls = BiliApi.playUrls(detail.bvid, detail.cid)
            quality = urls.qualityLabel

            val dataSourceFactory = OkHttpDataSource.Factory(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
            ).setDefaultRequestProperties(
                buildMap {
                    put("Referer", "https://www.bilibili.com")
                    put("User-Agent", PLAY_UA)
                    // 登录后把凭证也带给 CDN；未登录时 currentCookieHeader 里仍有 buvid（可为 null）
                    BiliHttp.currentCookieHeader()?.let { put("Cookie", it) }
                }
            )

            val source: MediaSource = when {
                urls.videoUrl != null -> {
                    val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(urls.videoUrl))
                    val audioUrl = urls.audioUrl
                    if (audioUrl != null) {
                        MergingMediaSource(
                            videoSource,
                            ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(audioUrl))
                        )
                    } else {
                        videoSource
                    }
                }

                else -> ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(urls.durl.first()))
            }

            exoPlayer.setMediaSource(source)
            exoPlayer.prepare()
            status = "正在播放"
        }.onFailure { e ->
            isError = true
            isBuffering = false
            status = "无法播放：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // 长按切换倍速：1× ↔ 2×。
    // 用 player 上的真实值做判断，不用 speedLabel —— 那是个展示状态，闭包里会读到旧值。
    val toggleSpeed: () -> Unit = {
        val next = if (exoPlayer.playbackParameters.speed > 1f) 1f else 2f
        exoPlayer.setPlaybackSpeed(next)
        speedLabel = if (next > 1f) "${next.toInt()}×" else ""
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 横屏 = 真全屏：视频铺满整屏，交给 PlayerView 自己按比例缩放（FIT，两侧留黑边而非裁切）。
    // 以前无论横竖屏都写死 aspectRatio(16:9)，横屏时按屏宽（2400）算出的容器高度约 1348，
    // 远超屏高 1080 —— 超出部分被裁掉，就成了"全屏后上下看不到了"。
    if (isLandscape) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            VideoArea(
                player = exoPlayer,
                onToggleSpeed = toggleSpeed,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 播放页是画在 Scaffold 之外的，拿不到它的 window insets。
                // 不自己补这颗 padding，标题就会钻进系统状态栏（截图里正是这样）。
                .statusBarsPadding()
                .padding(top = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        VideoArea(
            player = exoPlayer,
            onToggleSpeed = toggleSpeed,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isError -> Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    // 只有正在缓冲才转圈；播起来之后就不该再转
                    isBuffering -> {
                        CircularProgressIndicator(
                            modifier = Modifier.height(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (quality.isBlank()) status else "$status · $quality",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> Text(
                        text = buildString {
                            append(if (quality.isBlank()) status else "$status · $quality")
                            if (speedLabel.isNotBlank()) append(" · $speedLabel")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = video.authorName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "播完即停 · 没有连播、没有相关推荐",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 播放画面。抽成独立 Composable 是因为横屏与竖屏两处都要用，
 * 并且 `player` 必须走 update 而不是只在 factory 里赋一次 —— 否则重组后会拿到旧实例。
 *
 * 注意 opt-in 得再标一次：`androidx.annotation.OptIn` 没有 FILE 目标，
 * 抽出新函数就要自己带上，否则 lint 的 UnsafeOptInUsageError 会直接让构建失败。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    player: ExoPlayer,
    onToggleSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            // 双击暂停 / 长按倍速只能用 Android 原生的 GestureDetector 接。
            // 试过在 Compose 层用 pointerInput 盖在上面 —— 完全不触发：
            // AndroidView 是独立的 View 层级，触摸事件先被它吃掉，外层收不到。
            val gestures = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        player.playWhenReady = !player.playWhenReady
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        onToggleSpeed()
                    }
                }
            )

            PlayerView(context).apply {
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                // media3 默认把这个按钮藏起来，只有给了监听器它才会出现在控制条上。
                // 它只负责"切方向"，横屏/竖屏的布局由 PlayerScreen 按 configuration 决定。
                setFullscreenButtonClickListener { enterFullscreen ->
                    context.findActivity()?.requestedOrientation = if (enterFullscreen) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }

                // 返回 false = 只旁听，不消费。让事件继续走 PlayerView.onTouchEvent，
                // 否则控制条的显示/隐藏会被我们的手势吃掉。
                setOnTouchListener { _, event ->
                    gestures.onTouchEvent(event)
                    false
                }
            }
        },
        update = { view -> view.player = player },
        modifier = modifier
    )
}

/** 视频区。手势在 PlayerSurface 里用 GestureDetector 接，这一层只负责摆位置和底色。 */
@Composable
private fun VideoArea(
    player: ExoPlayer,
    onToggleSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        PlayerSurface(player, onToggleSpeed, Modifier.fillMaxSize())
    }
}

/** 从任意 Context 向上找宿主 Activity —— 切屏幕方向得用它 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
