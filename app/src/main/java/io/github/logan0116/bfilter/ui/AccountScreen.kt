package io.github.logan0116.bfilter.ui

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.logan0116.bfilter.BfilterApp
import io.github.logan0116.bfilter.data.FeedSettings
import io.github.logan0116.bfilter.data.remote.BiliApi
import io.github.logan0116.bfilter.data.remote.BiliHttp
import io.github.logan0116.bfilter.domain.AccountInfo
import io.github.logan0116.bfilter.domain.QrPollResult
import io.github.logan0116.bfilter.domain.QrState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(
    val account: AccountInfo? = null,
    val showQr: Boolean = false,
    val qrImage: ImageBitmap? = null,
    val qrState: QrState? = null,
    val statusText: String = "",
    val error: String? = null,
    val settings: FeedSettings = FeedSettings()
)

/** 扫码轮询间隔 */
private const val POLL_INTERVAL_MS = 2_000L

/** 单张二维码的轮询次数（2s × 90 ≈ 3 分钟，即 B 站二维码的寿命） */
private const val POLL_TIMES = 90

/** 二维码过期后自动换码的次数上限 */
private const val MAX_QR_ROUNDS = 3

class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val loginStore = (app as BfilterApp).loginStore
    private val settingsStore = (app as BfilterApp).settingsStore

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            loginStore.account.collect { account -> _state.update { it.copy(account = account) } }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
    }

    fun setRecentDays(days: Int) {
        viewModelScope.launch { settingsStore.setRecentDays(days) }
    }

    fun setMinDuration(seconds: Int) {
        viewModelScope.launch { settingsStore.setMinDuration(seconds) }
    }

    fun startLogin() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.update { it.copy(showQr = true, statusText = "", error = null, qrState = null) }

            // 一张码只有约 3 分钟寿命，过期就自动换新的 —— 否则用户还在找扫一扫时就失效了。
            // 用 for 而不是 repeat：循环里需要 break（repeat 的 lambda 里 break/continue 非法）。
            for (round in 0 until MAX_QR_ROUNDS) {
                val session = runCatching { BiliApi.qrGenerate() }.getOrElse { e ->
                    _state.update { it.copy(statusText = "", error = "获取二维码失败：${e.message}") }
                    return@launch
                }
                _state.update {
                    it.copy(
                        qrImage = generateQrCode(session.qrContent),
                        qrState = QrState.WAITING,
                        statusText = if (round == 0) {
                            "请用哔哩哔哩 App 扫码"
                        } else {
                            "二维码已自动刷新，请重新扫码"
                        }
                    )
                }

                var expired = false
                for (tick in 0 until POLL_TIMES) {
                    delay(POLL_INTERVAL_MS)

                    // 单次轮询失败（限流、抖动）不该终止整个流程，只提示并继续
                    val result = runCatching { BiliApi.qrPoll(session.qrcodeKey) }.getOrNull()
                    if (result == null) {
                        _state.update { it.copy(statusText = "轮询出错，重试中…") }
                        continue
                    }

                    when (result.state) {
                        QrState.WAITING -> _state.update { it.copy(statusText = "请用哔哩哔哩 App 扫码") }
                        QrState.SCANNED -> _state.update {
                            it.copy(statusText = "已扫码，请在手机上点击确认")
                        }

                        QrState.EXPIRED -> expired = true

                        QrState.SUCCESS -> {
                            if (completeLogin(result)) return@launch
                        }
                    }
                    if (expired) break
                }

                if (!expired) break // 走到这里说明是超时，不再继续换码
            }

            _state.update {
                it.copy(qrState = QrState.EXPIRED, statusText = "二维码已失效，请重新获取")
            }
        }
    }

    /** 拿到凭证后的收尾：注入 → 验证 → 持久化。返回是否真的登录成功。 */
    private suspend fun completeLogin(result: QrPollResult): Boolean {
        val cookie = result.cookie
        if (cookie.isNullOrBlank()) {
            _state.update { it.copy(statusText = "登录成功但未取到凭证，请重试") }
            return false
        }
        // 必须先注入再查账号，否则 nav 仍是游客态
        BiliHttp.setLoginCookie(cookie)
        val account = runCatching { BiliApi.accountInfo() }.getOrNull()
        if (account == null) {
            _state.update { it.copy(statusText = "已登录但取不到账号信息，请重试") }
            return false
        }
        loginStore.save(cookie, account)
        _state.update {
            it.copy(
                account = account,
                showQr = false,
                qrImage = null,
                qrState = QrState.SUCCESS,
                statusText = ""
            )
        }
        return true
    }

    fun cancelLogin() {
        pollJob?.cancel()
        _state.update { it.copy(showQr = false, qrImage = null, qrState = null, statusText = "", error = null) }
    }

    fun logout() {
        pollJob?.cancel()
        viewModelScope.launch {
            loginStore.clear()
            BiliHttp.setLoginCookie(null)
            _state.update { AccountUiState() }
        }
    }

    /**
     * 用粘贴来的 SESSDATA 登录。
     *
     * 手机端扫码有"自己扫自己"的物理限制，有电脑时这条路更省事：
     * 浏览器里登录 B 站，F12 复制 SESSDATA 值即可。大部分接口只认 SESSDATA。
     */
    fun loginWithSessdata(raw: String) {
        pollJob?.cancel()
        viewModelScope.launch {
            val value = raw.trim()
                .removePrefix("SESSDATA=")
                .substringBefore(';')
                .trim()
            if (value.isBlank()) {
                _state.update { it.copy(error = "SESSDATA 不能为空") }
                return@launch
            }
            val cookie = "SESSDATA=$value"
            // 先注入再验证：accountInfo 得带上这个凭证才认得出登录态
            BiliHttp.setLoginCookie(cookie)
            val account = runCatching { BiliApi.accountInfo() }.getOrNull()
            if (account == null) {
                BiliHttp.setLoginCookie(null)
                _state.update { it.copy(error = "这个凭证无效或已过期") }
                return@launch
            }
            loginStore.save(cookie, account)
            _state.update { it.copy(account = account, error = null, showQr = false) }
        }
    }
}

/**
 * 账号页 —— 登录入口 + 当前状态。
 *
 * 登录的唯一目的是：1080P，以及更宽松的接口风控。
 * 凭证（SESSDATA）只写在本机 DataStore，界面上也如实说明，不偷偷摸摸。
 */
@Composable
fun AccountScreen(
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            // 加了观看偏好之后内容会超过一屏，得能滚
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(
            title = "账号",
            subtitle = if (state.account == null) "未登录（480P）" else "已登录"
        )

        if (state.showQr) {
            QrLoginPanel(
                state = state,
                onRefresh = viewModel::startLogin,
                onClose = viewModel::cancelLogin
            )
            return@Column
        }

        if (state.account == null) {
            NotLoggedInPanel(
                onLogin = viewModel::startLogin,
                onManualLogin = viewModel::loginWithSessdata
            )
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        } else {
            LoggedInPanel(account = state.account!!, onLogout = viewModel::logout)
        }

        // 观看偏好：跟登录状态无关，未登录也要能调
        FeedPreferencePanel(
            settings = state.settings,
            onRecentDays = viewModel::setRecentDays,
            onMinDuration = viewModel::setMinDuration
        )
    }
}

/**
 * 观看偏好。
 *
 * 默认「近 3 天 + 不短于 1 分钟」是刻意的：白名单一多，时间线就会变成一条刷不到头的流，
 * 那又回到了被信息流拖着走的老问题。默认筛短一点，这条流才有尽头。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedPreferencePanel(
    settings: FeedSettings,
    onRecentDays: (Int) -> Unit,
    onMinDuration: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "观看偏好",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "默认只显示近 3 天、且不短于 1 分钟的内容 —— 让关注页刷得到头。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PreferenceRow(
            label = "只看最近",
            choices = FeedSettings.RECENT_DAY_CHOICES,
            selected = settings.recentDays,
            choiceLabel = { if (it <= 0) "不限" else "$it 天" },
            onSelect = onRecentDays
        )

        PreferenceRow(
            label = "过滤短视频",
            choices = FeedSettings.MIN_DURATION_CHOICES,
            selected = settings.minDurationSec,
            choiceLabel = { sec ->
                when {
                    sec <= 0 -> "不过滤"
                    sec < 60 -> "低于 $sec 秒"
                    else -> "低于 ${sec / 60} 分钟"
                }
            },
            onSelect = onMinDuration
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceRow(
    label: String,
    choices: List<Int>,
    selected: Int,
    choiceLabel: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 用 FlowRow：选项在窄屏上会自动折行，不用自己算宽度
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            choices.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(choiceLabel(value)) }
                )
            }
        }
    }
}

@Composable
private fun NotLoggedInPanel(onLogin: () -> Unit, onManualLogin: (String) -> Unit) {
    var showManual by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "登录之后：",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf(
            "清晰度从 480P 提到 1080P",
            "B 站对已登录账号的接口限流宽松得多",
            "能看到需要登录才能看的视频"
        ).forEach { line ->
            Text(
                text = "·  $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("扫码登录")
        }

        TextButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "收起" else "改用粘贴 SESSDATA 登录")
        }

        if (showManual) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text("SESSDATA") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onManualLogin(manual) },
                enabled = manual.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("用这个凭证登录")
            }
            Text(
                text = "取法：电脑浏览器登录 B 站 → F12 → Application → Cookies → " +
                    "https://www.bilibili.com → 复制 SESSDATA 的值",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "登录凭证只保存在本机，不上传、不导出；随时可以在这里退出登录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QrLoginPanel(
    state: AccountUiState,
    onRefresh: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var saveHint by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(248.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center
        ) {
            val qr = state.qrImage
            when {
                // 用 Compose 原生 Image 而不是 Coil 的 AsyncImage：
                // 这里已经是内存里的 ImageBitmap，不需要走图片加载器。
                qr != null -> Image(
                    bitmap = qr,
                    contentDescription = "登录二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )

                state.qrState == QrState.EXPIRED -> Text(
                    text = "已过期",
                    color = androidx.compose.ui.graphics.Color.DarkGray
                )

                else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        if (state.statusText.isNotBlank()) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
        }

        // 手机版必须提供这一步：二维码和 B 站 App 在同一块屏幕上，用户没法自己扫自己
        if (state.qrImage != null) {
            Text(
                text = "同一台手机上：先点下面存进相册，再用哔哩哔哩 App 的「扫一扫 → 相册」选中它",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(onClick = {
                val ok = saveQrToGallery(context, state.qrImage!!)
                saveHint = if (ok) "已存入相册 Pictures/Bfilter，去 B站 App 扫一扫里选「相册」" else "保存失败，请直接截图"
            }) {
                Text("存二维码到相册")
            }
            if (saveHint.isNotBlank()) {
                Text(
                    text = saveHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.qrState == QrState.EXPIRED) {
                Button(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新获取")
                }
            }
            OutlinedButton(onClick = onClose) { Text("取消") }
        }
    }
}

@Composable
private fun LoggedInPanel(account: AccountInfo, onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (account.face.isNotBlank()) {
                    AsyncImage(
                        model = account.face,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "UID ${account.mid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (account.loggedAt > 0) {
                    Text(
                        text = "登录于 ${formatRelativeTime(account.loggedAt / 1000)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = "现在播放会走 1080P；接口请求都带上了登录凭证。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(onClick = onLogout) {
            Text("退出登录", color = MaterialTheme.colorScheme.error)
        }
    }
}
