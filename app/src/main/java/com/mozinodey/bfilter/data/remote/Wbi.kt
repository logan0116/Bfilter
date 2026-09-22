package com.mozinodey.bfilter.data.remote

import java.security.MessageDigest

/**
 * B 站 wbi 签名（纯函数，方便单元测试）。
 *
 * 算法：
 *  1. 把 `nav` 接口返回的 img_url / sub_url 取文件名拼成 raw key
 *  2. 按固定的 MIXIN_KEY_ENC_TAB 重排 raw key，取前 32 位得到 mixin_key
 *  3. 参数按 key 升序 → 过滤值里的 `!'()*` → 百分号编码 → 拼成 query
 *  4. w_rid = md5(query + mixin_key)，最后把 wts 与 w_rid 一起交给服务端
 *
 * ⚠️ 第 3 步的百分号编码必须与服务端一致。这里刻意**不用** java.net.URLEncoder：
 * 它放行 `*` 却编码 `~`，而 Python 的 quote_plus 恰好相反。用错会导致 w_rid 不匹配，
 * 服务端表现为 `-352 风控校验失败`。真值由 Python 侧生成，见 WbiTest。
 */
object Wbi {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    /** 参数值里需要被剔除的字符（B 站约定） */
    private const val STRIP_CHARS = "!'()*"

    /** 由 img_key / sub_key 推导 mixin_key */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return buildString(32) {
            for (idx in MIXIN_KEY_ENC_TAB) {
                if (idx < raw.length) append(raw[idx])
            }
        }.take(32)
    }

    /**
     * 生成带 wts 与 w_rid 的 query 字符串，可直接拼到 URL 后。
     *
     * @param nowSec 当前秒级时间戳（显式传入便于测试）
     */
    fun signedQuery(
        params: Map<String, Any?>,
        mixinKey: String,
        nowSec: Long
    ): String {
        val clean = LinkedHashMap<String, String>()
        for ((k, v) in params) {
            if (v != null) clean[k] = strip(v.toString())
        }
        clean["wts"] = nowSec.toString()

        val query = clean.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${percentEncode(it.value)}" }

        val wRid = md5Hex(query + mixinKey)
        return "$query&w_rid=$wRid"
    }

    /** 剔除 `!'()*` */
    fun strip(value: String): String = value.filter { it !in STRIP_CHARS }

    /** 对齐 Python urllib.parse.quote_plus：放行 A-Za-z0-9 与 `_.-~`，空格转 `+` */
    fun percentEncode(value: String): String {
        val sb = StringBuilder(value.length * 3)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c == '_' || c == '.' || c == '-' || c == '~' -> sb.append(c)
                c == ' ' -> sb.append('+')
                else -> sb.append('%').append(HEX[(b.toInt() and 0xF0) shr 4]).append(HEX[b.toInt() and 0x0F])
            }
        }
        return sb.toString()
    }

    fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
