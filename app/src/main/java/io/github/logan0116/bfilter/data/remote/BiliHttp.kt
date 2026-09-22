package io.github.logan0116.bfilter.data.remote

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 所有 B 站请求的唯一出口。
 *
 * 这里集中处理三件容易踩坑的事：
 *  1. **游客身份**：`buvid3/buvid4/b_nut` 必须手动种进 Cookie —— `x/frontend/finger/spi`
 *     并不通过 Set-Cookie 下发，不手动带上就会被风控拦。
 *  2. **串行 + 限速**：`x/space/arc/search` 有频控，实测并发/高频会返回 `-799 请求过于频繁`。
 *     所有请求过一个互斥闸门，并保证最小间隔。
 *  3. **退避重试**：命中 `-799` 时按 1.5s / 3s 递增等待重试（实测重试即可成功）。
 *
 * 注意：本 object 的公开方法都会拿闸门锁，因此**私有方法不得再次加锁**（Kotlin 的
 * Mutex 不可重入）。
 */
object BiliHttp {

    /** 桌面 UA：实测可用。移动 UA 并不会让 wbi 版接口解封（同样 412），故不做伪装切换。 */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val REFERER = "https://www.bilibili.com"

    /**
     * 两次请求之间的最小间隔。B 站对未登录请求的频控窗口比想象中敏感，
     * 实测 1.1s 仍会时不时命中 -799，这里放宽到 1.6s。
     */
    private const val MIN_INTERVAL_MS = 1600L
    private const val MAX_ATTEMPTS = 3

    /** 命中 -799 后的退避：第一次等 3s，第二次等 8s（实测窗口不会太长） */
    private val RETRY_BACKOFF_MS = longArrayOf(0L, 3_000L, 8_000L)
    private const val WBI_KEY_TTL_MS = 30 * 60 * 1000L

    /** B 站返回了非 0 code（或 HTTP 异常）时抛出 */
    class BiliException(val code: Int, override val message: String) : Exception(message) {
        val isRateLimited: Boolean get() = code == -799
        val isRiskBlocked: Boolean get() = code == -412
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gate = Mutex()
    private val lastRequestAt = AtomicLong(0L)

    /** 游客身份（buvid3 / buvid4 / b_nut），首次请求时自动获取 */
    @Volatile private var buvidCookie: String? = null

    /** 登录凭证（SESSDATA 等），由 App 启动时从 LoginStore 注入；退出登录置 null */
    @Volatile private var loginCookie: String? = null
    @Volatile private var imgKey: String? = null
    @Volatile private var subKey: String? = null
    @Volatile private var wbiKeyFetchedAt = 0L

    // ---------------------------------------------------------------- 公开入口

    /**
     * 注入登录凭证（App 启动时从 LoginStore 读出；退出登录传 null）。
     *
     * 登录的价值不只是 1080P —— 实测未登录时 B 站对信息流接口的限流明显更紧，
     * 带上 SESSDATA 之后风控宽松得多。
     */
    fun setLoginCookie(cookie: String?) {
        loginCookie = cookie?.takeIf { it.isNotBlank() }
    }

    /**
     * 供播放器（Media3 的 OkHttpDataSource）复用同一份 Cookie。
     * 视频 CDN 主要校验 URL 上的签名，但登录态下带上更稳妥。
     */
    fun currentCookieHeader(): String? = composeCookie()

    /** 需要读响应头（`Set-Cookie`）的场景 —— 目前只有扫码登录的轮询 */
    suspend fun getStringWithHeaders(url: String): Pair<String, List<String>> =
        withContext(Dispatchers.IO) {
            gate.withLock {
                ensureSessionLocked()
                executeLockedWithHeaders(url)
            }
        }

    /** GET 一个返回 JSON 的接口，校验 code == 0 后返回整个 JSONObject */
    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        gate.withLock {
            ensureSessionLocked()
            var attempt = 0
            while (true) {
                val text = executeLocked(url)
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw BiliException(-1, "响应不是合法 JSON：${text.take(120)}")
                }
                val code = json.optInt("code", 0)
                if (code == 0) return@withLock lock@ json

                if (code == -799 && attempt < MAX_ATTEMPTS - 1) {
                    attempt++
                    delay(RETRY_BACKOFF_MS.getOrElse(attempt) { 8_000L })
                    continue
                }
                throw BiliException(code, json.optString("message", "未知错误").ifBlank { "code=$code" })
            }
            @Suppress("UNREACHABLE_CODE") throw BiliException(-1, "unreachable")
        }
    }

    /** 用缓存的 wbi key 对参数签名，返回可拼到 URL 的 query */
    suspend fun wbiQuery(params: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        gate.withLock {
            ensureSessionLocked()
            ensureWbiKeysLocked()
            val mixin = Wbi.mixinKey(imgKey!!, subKey!!)
            Wbi.signedQuery(params, mixin, System.currentTimeMillis() / 1000)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun ensureSessionLocked() {
        if (buvidCookie != null) return
        val text = executeLocked("https://api.bilibili.com/x/frontend/finger/spi")
        val data = JSONObject(text).optJSONObject("data") ?: JSONObject()
        val b3 = data.optString("b_3")
        val b4 = data.optString("b_4")
        if (b3.isBlank()) throw BiliException(-1, "无法获取游客标识（buvid3）")
        buvidCookie = buildString {
            append("buvid3=").append(b3)
            if (b4.isNotBlank()) append("; buvid4=").append(b4)
            append("; b_nut=").append(System.currentTimeMillis() / 1000)
        }
    }

    /** 游客身份 + 登录凭证拼成最终 Cookie 头 */
    private fun composeCookie(): String? {
        val parts = listOfNotNull(buvidCookie, loginCookie)
        return parts.joinToString("; ").ifBlank { null }
    }

    private suspend fun ensureWbiKeysLocked() {
        val fresh = SystemClock.elapsedRealtime() - wbiKeyFetchedAt < WBI_KEY_TTL_MS
        if (imgKey != null && subKey != null && fresh) return

        val text = executeLocked("https://api.bilibili.com/x/web-interface/nav")
        val wbi = JSONObject(text).optJSONObject("data")?.optJSONObject("wbi_img")
            ?: throw BiliException(-1, "nav 接口未返回 wbi_img，B 站可能改了结构")
        imgKey = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
        subKey = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
        if (imgKey.isNullOrBlank() || subKey.isNullOrBlank()) {
            throw BiliException(-1, "wbi key 解析失败")
        }
        wbiKeyFetchedAt = SystemClock.elapsedRealtime()
    }

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", DESKTOP_UA)
        .header("Referer", REFERER)
        .header("Origin", REFERER)
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "zh-CN,zh;q=0.9")
        .apply { composeCookie()?.let { header("Cookie", it) } }
        .build()

    /** 真正的网络调用：限速 → 带公共头 → 交给 read 解析。调用方必须已持有闸门。 */
    private suspend fun <T> executeLocked(url: String, read: (Response) -> T): T {
        throttleLocked()
        return try {
            client.newCall(buildRequest(url)).execute().use { response ->
                when {
                    response.code == 412 -> throw BiliException(-412, "接口被风控拦截（HTTP 412）")
                    !response.isSuccessful -> throw BiliException(response.code, "HTTP ${response.code}")
                    else -> read(response)
                }
            }
        } catch (e: BiliException) {
            throw e
        } catch (e: IOException) {
            throw BiliException(-1, friendlyNetworkError(e))
        }
    }

    /**
     * 把底层网络异常转成人能看懂的一句话。
     *
     * 直接把 `Unable to resolve host "api.bilibili.com": No address associated with hostname`
     * 甩给用户毫无意义 —— 实测断网时白名单里 7 个 UP 会各刷一条完全相同的长错误，占满整屏。
     */
    private fun friendlyNetworkError(e: IOException): String = when (e) {
        is UnknownHostException -> "网络不可用"
        is SocketTimeoutException -> "连接超时"
        else -> "网络异常"
    }

    private suspend fun executeLocked(url: String): String =
        executeLocked(url) { it.body?.string().orEmpty() }

    private suspend fun executeLockedWithHeaders(url: String): Pair<String, List<String>> =
        executeLocked(url) { response ->
            response.body?.string().orEmpty() to response.headers.values("Set-Cookie")
        }

    private suspend fun throttleLocked() {
        val elapsed = SystemClock.elapsedRealtime() - lastRequestAt.get()
        val wait = MIN_INTERVAL_MS - elapsed
        if (wait > 0) delay(wait)
        lastRequestAt.set(SystemClock.elapsedRealtime())
    }
}
