package io.github.logan0116.bfilter.data.remote

import io.github.logan0116.bfilter.domain.AccountInfo
import io.github.logan0116.bfilter.domain.PlayUrls
import io.github.logan0116.bfilter.domain.QrPollResult
import io.github.logan0116.bfilter.domain.QrSession
import io.github.logan0116.bfilter.domain.QrState
import io.github.logan0116.bfilter.domain.UpHit
import io.github.logan0116.bfilter.domain.VideoDetail
import io.github.logan0116.bfilter.domain.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * B 站接口封装 —— **唯一需要跟随 B 站改动的地方**。
 *
 * 各接口的实测状态（2026-09-22，游客态）见 README 第三节。两条硬约束：
 *  - UP 主投稿必须用**旧版** `x/space/arc/search`；wbi 版对游客返回 HTTP 412。
 *  - 播放地址用非 wbi 的 `x/player/playurl`，游客上限 480P。
 */
object BiliApi {

    /** 搜索 UP 主（需要 wbi 签名） */
    suspend fun searchUsers(keyword: String): List<UpHit> {
        val query = BiliHttp.wbiQuery(
            mapOf(
                "search_type" to "bili_user",
                "keyword" to keyword,
                "page" to 1
            )
        )
        val json = BiliHttp.getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?$query")
        val result = json.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        return result.mapObjects { o ->
            val mid = o.optLong("mid")
            if (mid <= 0L) null else UpHit(
                mid = mid,
                name = o.optString("uname"),
                face = normalizeImageUrl(o.optString("upic")),
                fans = o.optInt("fans"),
                sign = o.optString("usign")
            )
        }
    }

    /**
     * 取某个 UP 主的最新更新（只保留视频投稿）。
     *
     * **为什么不用"空间投稿列表"**（`x/space/arc/search` 及其 wbi 版）：实测对游客长期返回
     * `HTTP 412 request was banned`，偶尔漏放一次但完全不可依赖 —— 见 README 的踩坑记录。
     *
     * **改用 UP 主动态流**：一次请求就能拿到该 UP 的全部最新动作（视频投稿 / 图文 / 转发），
     * 实测连续拉多个 UP（间隔 2s）稳定成功。我们只挑出 `DYNAMIC_TYPE_AV` 作为可看的内容。
     *
     * 注意限流特性：同一 UP 短时间重复请求会返回 code 0 但 `items` 为空（软限流），
     * 所以调用方必须靠缓存 + 低频刷新，不要连续打。
     */
    suspend fun userVideos(mid: Long, pageSize: Int = 12): List<VideoItem> {
        val query = BiliHttp.wbiQuery(
            mapOf(
                "host_mid" to mid,
                "offset" to "",
                "timezone_offset" to -480,
                "platform" to "web",
                "features" to "itemOpusStyle",
                "web_location" to "333.1387"
            )
        )
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?$query"
        val json = BiliHttp.getJson(url)
        val items = json.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        return items.mapObjects { parseDynamicArchive(it) }.take(pageSize)
    }

    /** 从一条动态里提取视频投稿信息；非视频动态返回 null */
    private fun parseDynamicArchive(dynamic: JSONObject): VideoItem? {
        if (dynamic.optString("type") != "DYNAMIC_TYPE_AV") return null
        val modules = dynamic.optJSONObject("modules") ?: return null
        val author = modules.optJSONObject("module_author") ?: JSONObject()
        val archive = modules.optJSONObject("module_dynamic")
            ?.optJSONObject("major")
            ?.optJSONObject("archive")
            ?: return null

        val bvid = archive.optString("bvid")
        if (bvid.isBlank()) return null

        return VideoItem(
            bvid = bvid,
            title = archive.optString("title"),
            cover = normalizeImageUrl(archive.optString("cover")),
            durationSec = parseDuration(archive.optString("duration_text")),
            // 动态接口里 pub_ts 是字符串
            pubDateSec = author.optString("pub_ts").toLongOrNull() ?: 0L,
            authorName = author.optString("name"),
            authorMid = author.optLong("mid"),
            // stat.play 是 "26.8万" 这样的展示字符串
            playCount = parseCount(archive.optJSONObject("stat")?.optString("play").orEmpty())
        )
    }

    /**
     * 按 UID 取 UP 主信息 —— 搜索有时搜不到（改名、昵称含特殊字符），UID 是更精确的入口。
     */
    suspend fun userCard(mid: Long): UpHit {
        val json = try {
            BiliHttp.getJson("https://api.bilibili.com/x/web-interface/card?mid=$mid")
        } catch (e: BiliHttp.BiliException) {
            // B 站对不存在的 UID 只回一句「啥都木有」，对用户没有任何信息量，替换成能读懂的
            throw BiliHttp.BiliException(e.code, "查不到 UID $mid 这个 UP 主")
        }
        val data = json.optJSONObject("data")
            ?: throw BiliHttp.BiliException(-1, "找不到 UID $mid 对应的 UP 主")
        val card = data.optJSONObject("card")
            ?: throw BiliHttp.BiliException(-1, "找不到 UID $mid 对应的 UP 主")
        val name = card.optString("name")
        if (name.isBlank()) throw BiliHttp.BiliException(-1, "UID $mid 查不到昵称")
        return UpHit(
            mid = card.optLong("mid", mid),
            name = name,
            face = normalizeImageUrl(card.optString("face")),
            fans = data.optInt("follower"),
            sign = card.optString("sign")
        )
    }

    // ---------------------------------------------------------------- 扫码登录

    /** 申请一个扫码登录二维码 */
    suspend fun qrGenerate(): QrSession {
        val json = BiliHttp.getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        val data = json.optJSONObject("data")
            ?: throw BiliHttp.BiliException(-1, "二维码接口没有返回数据")
        val key = data.optString("qrcode_key")
        val content = data.optString("url")
        if (key.isBlank() || content.isBlank()) {
            throw BiliHttp.BiliException(-1, "二维码接口返回不完整")
        }
        return QrSession(qrcodeKey = key, qrContent = content)
    }

    /**
     * 轮询扫码状态。
     *
     * 响应里有**两层 code**：外层是接口状态，`data.code` 才是扫码状态
     * （86101 未扫码 / 86090 已扫码待确认 / 0 成功 / 86038 已过期）。
     * 登录凭证同时出现在 `data.url` 的 query 和响应的 `Set-Cookie` 头里 —— 两边都解析。
     */
    suspend fun qrPoll(qrcodeKey: String): QrPollResult {
        val (body, setCookies) = BiliHttp.getStringWithHeaders(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey"
        )
        val json = JSONObject(body)
        val outerCode = json.optInt("code", 0)
        if (outerCode != 0) {
            throw BiliHttp.BiliException(outerCode, json.optString("message", "登录接口异常"))
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val state = when (data.optInt("code", -1)) {
            0 -> QrState.SUCCESS
            86090 -> QrState.SCANNED
            86038 -> QrState.EXPIRED
            else -> QrState.WAITING
        }
        return QrPollResult(
            state = state,
            message = data.optString("message").ifBlank { state.name },
            cookie = if (state == QrState.SUCCESS) {
                extractLoginCookie(data.optString("url"), setCookies)
            } else {
                null
            },
            refreshToken = data.optString("refresh_token").ifBlank { null }
        )
    }

    /** 当前登录账号；未登录返回 null（此时 nav 的 code=-101，但 HTTP 仍是 200） */
    suspend fun accountInfo(): AccountInfo? {
        val (body, _) = BiliHttp.getStringWithHeaders("https://api.bilibili.com/x/web-interface/nav")
        val data = JSONObject(body).optJSONObject("data") ?: return null
        if (!data.optBoolean("isLogin")) return null
        val mid = data.optLong("mid")
        if (mid <= 0) return null
        return AccountInfo(
            mid = mid,
            name = data.optString("uname"),
            face = normalizeImageUrl(data.optString("face"))
        )
    }

    /**
     * 从两个来源提取登录 Cookie。`Set-Cookie` 头优先（值是原始串，无需解码），
     * `data.url` 的 query 参数兜底（需要先 URL 解码）。
     */
    internal fun extractLoginCookie(redirectUrl: String, setCookies: List<String>): String? {
        val wanted = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")
        val found = LinkedHashMap<String, String>()

        redirectUrl.substringAfter('?', "")
            .split('&')
            .forEach { pair ->
                if (pair.isBlank()) return@forEach
                val key = pair.substringBefore('=')
                val value = urlDecode(pair.substringAfter('=', ""))
                if (key in wanted && value.isNotBlank()) found[key] = value
            }

        setCookies.forEach { raw ->
            val first = raw.substringBefore(';')
            val key = first.substringBefore('=').trim()
            val value = first.substringAfter('=', "").trim()
            if (key in wanted && value.isNotBlank()) found[key] = value
        }

        if (!found.containsKey("SESSDATA")) return null
        return found.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun urlDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** 视频详情（播放前拿 cid） */
    suspend fun videoDetail(bvid: String): VideoDetail {
        val json = BiliHttp.getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        val data = json.optJSONObject("data")
            ?: throw BiliHttp.BiliException(-1, "视频详情为空（$bvid）")
        val owner = data.optJSONObject("owner") ?: JSONObject()
        return VideoDetail(
            bvid = data.optString("bvid", bvid),
            cid = data.optLong("cid"),
            title = data.optString("title"),
            cover = normalizeImageUrl(data.optString("pic")),
            durationSec = data.optInt("duration"),
            authorName = owner.optString("name"),
            authorMid = owner.optLong("mid"),
            desc = data.optString("desc")
        )
    }

    /**
     * 取播放地址。优先 DASH（音视频分离，画质更好），拿不到就退化到 durl（整段）。
     * 之所以优先 H.264：部分设备的硬解不支持 HEVC，avc1 更保险。
     */
    suspend fun playUrls(bvid: String, cid: Long): PlayUrls {
        val url = "https://api.bilibili.com/x/player/playurl" +
            "?bvid=$bvid&cid=$cid&qn=80&fnval=4048&fourk=1"
        val json = BiliHttp.getJson(url)
        val data = json.optJSONObject("data")
            ?: throw BiliHttp.BiliException(-1, "播放地址为空（$bvid）")

        data.optJSONObject("dash")?.let { dash ->
            val video = pickBestVideo(dash.optJSONArray("video"))
            if (video != null) {
                val audio = dash.optJSONArray("audio")?.optJSONObject(0)
                return PlayUrls(
                    videoUrl = firstUrl(video),
                    audioUrl = audio?.let { firstUrl(it) },
                    durl = emptyList(),
                    qualityLabel = qualityLabel(video.optInt("id"))
                )
            }
        }

        val durl = data.optJSONArray("durl")
        val segments = (0 until (durl?.length() ?: 0))
            .mapNotNull { durl?.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotBlank() } }
        if (segments.isEmpty()) throw BiliHttp.BiliException(-1, "没有可用的播放地址")
        return PlayUrls(videoUrl = null, audioUrl = null, durl = segments, qualityLabel = "整段")
    }

    // ---------------------------------------------------------------- 工具

    /** DASH video 列表里挑一条：优先 avc1（H.264），在其内部取清晰度最高的一条 */
    private fun pickBestVideo(videos: JSONArray?): JSONObject? {
        if (videos == null || videos.length() == 0) return null
        val all = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
        if (all.isEmpty()) return null
        val avc = all.filter { it.optString("codecs").startsWith("avc") }
        val pool = avc.ifEmpty { all }
        return pool.maxByOrNull { it.optInt("id") }
    }

    private fun firstUrl(o: JSONObject): String? {
        o.optString("baseUrl").takeIf { it.isNotBlank() }?.let { return it }
        val backups = o.optJSONArray("backupUrl") ?: return null
        for (i in 0 until backups.length()) {
            val u = backups.optString(i)
            if (u.isNotBlank()) return u
        }
        return null
    }

    private fun qualityLabel(id: Int): String = when (id) {
        6 -> "240P"
        16 -> "360P"
        32 -> "480P"
        64 -> "720P"
        74 -> "720P60"
        80 -> "1080P"
        112 -> "1080P+"
        116 -> "1080P60"
        120 -> "4K"
        125 -> "HDR"
        126 -> "杜比视界"
        127 -> "8K"
        else -> "${id}P"
    }

    /** B 站图片常返回 `//i0.hdslb.com/...`，补上协议 */
    private fun normalizeImageUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    /** "26.8万" / "1.2亿" / "1234" → 数字；解析不了就返回 0 而不是崩 */
    internal fun parseCount(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        val multiplier = when {
            text.endsWith("亿") -> 100_000_000.0
            text.endsWith("万") -> 10_000.0
            else -> 1.0
        }
        val number = if (multiplier == 1.0) text.toDoubleOrNull() else text.dropLast(1).toDoubleOrNull()
        return number?.let { (it * multiplier).toInt() } ?: 0
    }

    /** "12:34" / "1:02:03" / "754" → 秒 */
    internal fun parseDuration(raw: String): Int {
        val parts = raw.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T?): List<T> {
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            transform(o)?.let { out += it }
        }
        return out
    }
}
