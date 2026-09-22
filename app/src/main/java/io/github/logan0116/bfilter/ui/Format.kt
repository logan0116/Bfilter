package io.github.logan0116.bfilter.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 147 → "2:27"，3723 → "1:02:03" */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 播放量：12345 → "1.2万" */
fun formatCount(count: Int): String = when {
    count < 0 -> ""
    count < 10_000 -> count.toString()
    count < 100_000_000 -> "%.1f万".format(count / 10_000.0)
    else -> "%.1f亿".format(count / 100_000_000.0)
}

/** 粉丝数：同上，没有数据时返回空 */
fun formatFans(fans: Int): String = if (fans <= 0) "" else "${formatCount(fans)} 粉丝"

/** 发布时间 → "3 小时前" / "2026-08-01" */
fun formatRelativeTime(epochSec: Long): String {
    if (epochSec <= 0) return ""
    val diff = System.currentTimeMillis() / 1000 - epochSec
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86_400 -> "${diff / 3600} 小时前"
        diff < 86_400 * 30 -> "${diff / 86_400} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochSec * 1000))
    }
}
