package com.mozinodey.bfilter.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mozinodey.bfilter.BfilterApp
import com.mozinodey.bfilter.domain.UpHit
import com.mozinodey.bfilter.domain.WhitelistUp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WhitelistUiState(
    val ups: List<WhitelistUp> = emptyList(),
    val searching: Boolean = false,
    val results: List<UpHit> = emptyList(),
    val error: String? = null,
    /** 是否已经查过 —— 用来区分「还没搜」和「搜了但没有结果」这两种空状态 */
    val searchedOnce: Boolean = false
)

class WhitelistViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as BfilterApp).whitelistStore
    private val repo = (app as BfilterApp).repository

    private val _state = MutableStateFlow(WhitelistUiState())
    val state: StateFlow<WhitelistUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            store.ups.collect { ups -> _state.update { it.copy(ups = ups) } }
        }
    }

    fun add(hit: UpHit) {
        viewModelScope.launch {
            store.add(WhitelistUp(mid = hit.mid, name = hit.name, face = hit.face))
        }
    }

    fun remove(mid: Long) {
        viewModelScope.launch { store.remove(mid) }
    }

    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null, results = emptyList()) }
            runCatching { repo.searchUps(kw) }
                .onSuccess { hits ->
                    _state.update { it.copy(searching = false, results = hits, searchedOnce = true) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            searching = false,
                            searchedOnce = true,
                            error = "搜索失败：${e.message ?: e.javaClass.simpleName}"
                        )
                    }
                }
        }
    }

    /** 按 UID 添加：搜索搜不到（改名、昵称带特殊字符）时的精确入口 */
    fun addByUid(uid: Long) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null, results = emptyList()) }
            runCatching { repo.findUpByUid(uid) }
                .onSuccess { hit ->
                    store.add(WhitelistUp(mid = hit.mid, name = hit.name, face = hit.face))
                    _state.update {
                        it.copy(searching = false, results = listOf(hit), searchedOnce = true)
                    }
                }
                .onFailure { e ->
                    // 这里不加「搜索失败」前缀：按 UID 查不是搜索，而且 message 本身已说清原因
                    _state.update {
                        it.copy(
                            searching = false,
                            searchedOnce = true,
                            error = e.message ?: "查询失败"
                        )
                    }
                }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(results = emptyList(), error = null, searchedOnce = false) }
    }
}

/**
 * 白名单管理 —— 这个 app 的"输入"。首页的信息流完全等于这张列表的投影。
 */
@Composable
fun WhitelistScreen(
    modifier: Modifier = Modifier,
    viewModel: WhitelistViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var showSearch by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "UP 主白名单",
                subtitle = "只有这里的人会出现在关注页"
            )

            if (state.ups.isEmpty()) {
                EmptyHint("还没有添加任何 UP 主")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(state.ups, key = { it.mid }) { up ->
                        UpRow(
                            name = up.name,
                            face = up.face,
                            subtitle = "UID ${up.mid}",
                            trailing = {
                                IconButton(onClick = { viewModel.remove(up.mid) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "移除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showSearch = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "添加 UP 主")
        }
    }

    if (showSearch) {
        SearchUpDialog(
            state = state,
            onSearch = viewModel::search,
            onAdd = viewModel::add,
            onAddByUid = viewModel::addByUid,
            onDismiss = {
                showSearch = false
                viewModel.clearSearch()
            }
        )
    }
}

@Composable
private fun SearchUpDialog(
    state: WhitelistUiState,
    onSearch: (String) -> Unit,
    onAdd: (UpHit) -> Unit,
    onAddByUid: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var keyword by remember { mutableStateOf("") }
    var uid by remember { mutableStateOf("") }
    val addedMids = state.ups.map { it.mid }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 UP 主") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("UP 主昵称") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { onSearch(keyword) }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uid,
                    // UID 长度：老账号 9 位，2023 年后注册的可达 16 位。这里给足 20 位余量，
                    // 别像最初那样按 12 位截断（会把新账号 UID 切掉尾部，查出来是另一个号或直接失败）。
                    onValueChange = { input -> uid = input.filter { it.isDigit() }.take(20) },
                    label = { Text("或直接填 UID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        IconButton(
                            onClick = { uid.toLongOrNull()?.let(onAddByUid) },
                            enabled = uid.toLongOrNull()?.let { it > 0 } == true
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "按 UID 添加")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    state.searching -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    state.error != null -> Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    state.results.isEmpty() -> Text(
                        text = if (state.searchedOnce) "没搜到相关的 UP 主" else "输入昵称后点搜索",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(state.results, key = { it.mid }) { hit ->
                            UpRow(
                                name = hit.name,
                                face = hit.face,
                                subtitle = formatFans(hit.fans),
                                clickable = !addedMids.contains(hit.mid),
                                onClick = { onAdd(hit) },
                                trailing = {
                                    if (addedMids.contains(hit.mid)) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "已添加",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "添加",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun UpRow(
    name: String,
    face: String,
    subtitle: String,
    clickable: Boolean = false,
    onClick: () -> Unit = {},
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (face.isNotBlank()) {
                AsyncImage(
                    model = face,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        trailing()
    }
}
