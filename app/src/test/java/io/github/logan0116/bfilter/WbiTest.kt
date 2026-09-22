package io.github.logan0116.bfilter

import io.github.logan0116.bfilter.data.remote.BiliApi
import io.github.logan0116.bfilter.data.remote.Wbi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wbi 签名的黄金值来自 Python 参考实现（本机实测可成功调用 B 站 wbi 接口）。
 * 只要这些断言通过，Kotlin 侧的签名就与 B 站服务端一致。
 */
class WbiTest {

    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun mixinKey_matchesPythonReference() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", Wbi.mixinKey(imgKey, subKey))
    }

    @Test
    fun signedQuery_matchesPythonReference() {
        val query = Wbi.signedQuery(
            params = mapOf(
                "search_type" to "bili_user",
                "keyword" to "影视飓风",
                "page" to 1
            ),
            mixinKey = Wbi.mixinKey(imgKey, subKey),
            nowSec = 1_700_000_000L
        )
        assertEquals(
            "keyword=%E5%BD%B1%E8%A7%86%E9%A3%93%E9%A3%8E&page=1&search_type=bili_user" +
                "&wts=1700000000&w_rid=15c11eba808eb3f03da42742f53e4ed3",
            query
        )
    }

    /**
     * 这一条守的是最容易写错的地方：percent 编码必须对齐 Python 的 quote_plus。
     * 用 java.net.URLEncoder 会得到不同的串（它放行 `*` 却编码 `~`），w_rid 就会对不上，
     * 服务端表现为 -352 风控校验失败。
     */
    @Test
    fun signedQuery_percentEncoding_matchesQuotePlus() {
        val query = Wbi.signedQuery(
            params = mapOf("keyword" to "a b&c=d/e?f~g*h"),
            mixinKey = Wbi.mixinKey(imgKey, subKey),
            nowSec = 1_700_000_000L
        )
        assertEquals(
            "keyword=a+b%26c%3Dd%2Fe%3Ff~gh&wts=1700000000" +
                "&w_rid=d0cdb9e933c9d27c672f5c63b9c001aa",
            query
        )
    }

    @Test
    fun percentEncode_keepsUnreservedAndEncodesRest() {
        assertEquals("a+b", Wbi.percentEncode("a b"))
        assertEquals("_.-~", Wbi.percentEncode("_.-~"))
        assertEquals("%2A", Wbi.percentEncode("*"))
        assertEquals("%E4%B8%AD", Wbi.percentEncode("中"))
    }

    @Test
    fun strip_removesForbiddenChars() {
        assertEquals("abcd", Wbi.strip("a!b'c(d)*"))
    }

    @Test
    fun md5Hex_isLowercaseHex() {
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Wbi.md5Hex("a"))
    }

    @Test
    fun parseDuration_handlesAllFormats() {
        assertEquals(754, BiliApi.parseDuration("12:34"))
        assertEquals(3723, BiliApi.parseDuration("1:02:03"))
        assertEquals(120, BiliApi.parseDuration("120"))
        assertEquals(0, BiliApi.parseDuration(""))
        // 防御：脏数据不应抛异常
        assertEquals(0, BiliApi.parseDuration("--:--"))
        assertTrue(BiliApi.parseDuration("10:00") == 600)
        // 动态接口的 duration_text 实测就是 "13:05" 这种；也见过省略前导零的 "9:5"
        assertEquals(785, BiliApi.parseDuration("13:05"))
        assertEquals(545, BiliApi.parseDuration("9:5"))
    }

    /** 动态接口把播放量给成 "26.8万" 这种展示串，必须能安全还原成数字 */
    @Test
    fun parseCount_handlesChineseUnits() {
        assertEquals(268_000, BiliApi.parseCount("26.8万"))
        assertEquals(12_000, BiliApi.parseCount("1.2万"))
        assertEquals(1234, BiliApi.parseCount("1234"))
        assertEquals(120_000_000, BiliApi.parseCount("1.2亿"))
        assertEquals(0, BiliApi.parseCount(""))
        assertEquals(0, BiliApi.parseCount("--"))
    }

    // ------------------------------------------------ 扫码登录的凭证提取

    private val crossDomainUrl =
        "https://passport.biligame.com/crossDomain?SESSDATA=from%2Curl&DedeUserID=111&bili_jct=urljct"

    /** Set-Cookie 头的值未经 URL 编码，且更权威 —— 必须覆盖 url 里的同名项 */
    @Test
    fun extractLoginCookie_setCookieHeaderWins() {
        val cookie = BiliApi.extractLoginCookie(
            crossDomainUrl,
            listOf(
                "SESSDATA=realValue%2Ckept; Path=/; Domain=.bilibili.com",
                "bili_jct=realjct; Path=/",
                "DedeUserID=222; Path=/"
            )
        )
        assertNotNull(cookie)
        assertTrue("应保留 Set-Cookie 里的原值", cookie!!.contains("SESSDATA=realValue%2Ckept"))
        assertTrue("DedeUserID 应被头覆盖", cookie.contains("DedeUserID=222"))
        assertTrue(cookie.contains("bili_jct=realjct"))
    }

    /** 只给到 data.url 时，query 里的值必须先 URL 解码（否则 SESSDATA 会带着 %2C 用出去） */
    @Test
    fun extractLoginCookie_urlFallbackDecodesValues() {
        val cookie = BiliApi.extractLoginCookie(crossDomainUrl, emptyList())
        assertNotNull(cookie)
        assertTrue(cookie!!.contains("SESSDATA=from,url"))
        assertTrue(cookie.contains("DedeUserID=111"))
    }

    /** 没有 SESSDATA 就不算登录成功，不能返回半个凭证 */
    @Test
    fun extractLoginCookie_requiresSessdata() {
        assertNull(BiliApi.extractLoginCookie("https://x/?DedeUserID=1", emptyList()))
        assertNull(BiliApi.extractLoginCookie("", emptyList()))
    }
}
