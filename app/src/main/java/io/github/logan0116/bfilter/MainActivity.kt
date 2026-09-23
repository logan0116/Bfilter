package io.github.logan0116.bfilter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.logan0116.bfilter.domain.VideoItem
import io.github.logan0116.bfilter.ui.AccountScreen
import io.github.logan0116.bfilter.ui.FeedScreen
import io.github.logan0116.bfilter.ui.PlayerScreen
import io.github.logan0116.bfilter.ui.WhitelistScreen
import io.github.logan0116.bfilter.ui.theme.BfilterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BfilterTheme {
                BfilterRoot()
            }
        }
    }
}

/**
 * 全 app 只有两个页面：一条流 + 一张白名单。播放页是覆盖在上面的临时状态。
 * 刻意不做"首页"，因为首页就是推荐流的同义词。
 */
@Composable
private fun BfilterRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<VideoItem?>(null) }

    // Android 13+ 显示"正在播放"那颗通知需要授权。没有它前台服务照样在跑，
    // 但在小米这类 ROM 上，"用户看得见的前台服务"才不容易被省电策略收掉，
    // 所以进 App 就顺手问一次；被拒也不影响播放，无需处理结果。
    val context = LocalContext.current
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val current = playing
    if (current != null) {
        PlayerScreen(video = current, onBack = { playing = null })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    label = { Text("关注") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("UP 主") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("账号") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> FeedScreen(
                    onPlay = { playing = it },
                    onGoWhitelist = { tab = 1 }
                )

                1 -> WhitelistScreen()

                else -> AccountScreen()
            }
        }
    }
}
