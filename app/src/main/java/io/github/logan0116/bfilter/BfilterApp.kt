package io.github.logan0116.bfilter

import android.app.Application
import io.github.logan0116.bfilter.data.FeedCacheStore
import io.github.logan0116.bfilter.data.LoginStore
import io.github.logan0116.bfilter.data.PlaybackPositionStore
import io.github.logan0116.bfilter.data.VideoRepository
import io.github.logan0116.bfilter.data.WhitelistStore
import io.github.logan0116.bfilter.data.remote.BiliHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 极简依赖持有者。工程规模小，不引入 DI 框架 —— ViewModel 直接从 Application 取。
 */
class BfilterApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val whitelistStore: WhitelistStore by lazy { WhitelistStore(this) }
    val feedCacheStore: FeedCacheStore by lazy { FeedCacheStore(this) }
    val loginStore: LoginStore by lazy { LoginStore(this) }
    val playbackPositionStore: PlaybackPositionStore by lazy { PlaybackPositionStore(this) }
    val repository: VideoRepository by lazy { VideoRepository(feedCacheStore) }

    /**
     * 借应用级作用域跑一次性任务。
     * 播放页退出时要落一次观看进度，但那时 Composable 的作用域已经被取消了 ——
     * 用它发出去的协程会被立刻掐断，进度就丢了。
     */
    fun launchInBackground(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    override fun onCreate() {
        super.onCreate()
        // 把持久化的登录凭证持续注入到网络层：进程启动时立刻生效，
        // 之后登录/退出的变化也自动跟随。
        appScope.launch {
            loginStore.cookie.collect { cookie -> BiliHttp.setLoginCookie(cookie) }
        }
    }
}
