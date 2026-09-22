package com.mozinodey.bfilter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mozinodey.bfilter.domain.VideoItem
import com.mozinodey.bfilter.ui.AccountScreen
import com.mozinodey.bfilter.ui.FeedScreen
import com.mozinodey.bfilter.ui.PlayerScreen
import com.mozinodey.bfilter.ui.WhitelistScreen
import com.mozinodey.bfilter.ui.theme.BfilterTheme

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
