package io.github.logan0116.bfilter.domain

import kotlinx.serialization.Serializable

/** 白名单里的一个 UP 主 */
@Serializable
data class WhitelistUp(
    val mid: Long,
    val name: String,
    val face: String = "",
    /** 加入时间，用于稳定排序 */
    val addedAt: Long = 0L
)

/** 时间线上的一条视频 */
@Serializable
data class VideoItem(
    val bvid: String,
    val title: String,
    val cover: String,
    val durationSec: Int,
    val pubDateSec: Long,
    val authorName: String,
    val authorMid: Long,
    val playCount: Int
)

/** 搜索 UP 主的结果项 */
data class UpHit(
    val mid: Long,
    val name: String,
    val face: String,
    val fans: Int,
    val sign: String
)

/** 视频详情（播放前拿 cid） */
data class VideoDetail(
    val bvid: String,
    val cid: Long,
    val title: String,
    val cover: String,
    val durationSec: Int,
    val authorName: String,
    val authorMid: Long,
    val desc: String
)

/** 播放地址：优先 DASH（画质更好），退化到 durl */
data class PlayUrls(
    val videoUrl: String?,
    val audioUrl: String?,
    val durl: List<String>,
    val qualityLabel: String
)

/** 扫码登录：一次会话 */
data class QrSession(
    val qrcodeKey: String,
    /** 二维码里要编码的内容（B 站 App 扫码后跳转的地址） */
    val qrContent: String
)

/** 扫码状态机 */
enum class QrState {
    /** 86101 等待扫码 */
    WAITING,

    /** 86090 已扫码、等待手机上确认 */
    SCANNED,

    /** 0 登录成功 */
    SUCCESS,

    /** 86038 二维码过期 */
    EXPIRED
}

data class QrPollResult(
    val state: QrState,
    val message: String,
    /** 仅 SUCCESS 时非空：可直接用于请求的 Cookie 串 */
    val cookie: String? = null,
    val refreshToken: String? = null
)

/** 已登录账号（只留展示所需的最小字段） */
@Serializable
data class AccountInfo(
    val mid: Long,
    val name: String,
    val face: String = "",
    val loggedAt: Long = 0L
)
