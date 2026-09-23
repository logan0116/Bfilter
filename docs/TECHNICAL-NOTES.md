# 技术笔记

做这个项目的过程中撞出来的坑，**每条都是真机实测结论，不是照文档抄的**。
做同类项目（B 站第三方客户端 / media3 播放器 / 安卓 UI 自动化）的话可以直接省掉这些弯路。

---

## 1. `x/space/arc/search`（空间投稿）对游客是封死的

拿某个 UP 主的投稿列表，最直觉的接口是这个。但游客态下它长期返回
`HTTP 412 request was banned`，wbi 版同理。

**改用 UP 主动态流** `x/polymer/web-dynamic/v1/feed/space`（需 wbi 签名）：
一次请求就能拿到该 UP 的全部最新动态，里面天然包含视频投稿（`DYNAMIC_TYPE_AV`）。
实测 7 个 UP 串行拉取（间隔 1.6s）全部成功。

## 2. 顺序反转实验：我差点写错两版代码

排查 412 时我做了一组对照实验，结论看起来非常硬：

> 不带额外参数 → 成功；带 `order` / `platform` / `web_location` → 全部 412

于是我得出「参数导致风控」的结论，并据此改了两版代码。

后来把**成功那组挪到后面**再测一次，结论立刻反转 —— 原来只是「每轮第一个请求恰好能过」。

**凡 B 站风控类结论，必须做顺序反转实验（把"成功"的变体放到后面重测）才能采信。**

## 3. 软限流：`code: 0` 但列表是空的

动态流被限流时**不报错**，而是返回 `code: 0` + 空 `items` ——
看起来和「这个 UP 主没发新东西」一模一样。

对策：**空结果绝不覆盖缓存**，退回上次内容并如实提示：

```
偷星九月333、司波图、极客湾Geekerwan 等 7 个：网络不可用
这次没拉到新内容，显示的是 2 分钟前的缓存
```

## 4. wbi 签名里最容易错的一行

参数值的百分号编码必须对齐 Python 的 `quote_plus`（放行 `A-Za-z0-9_.-~`，空格转 `+`）。
用 `java.net.URLEncoder` 会不一致 —— 它放行 `*` 却编码 `~`，算出来的 `w_rid` 对不上，
服务端只会甩你一句 `-352 风控校验失败`。见 `Wbi.kt`，有单测锚定 Python 侧的黄金值。

## 5. 手机端扫码登录有个物理限制

扫码登录的经典场景是「二维码在电脑上、手机来扫」。但在手机 app 里，
**二维码和哔哩哔哩 App 在同一块屏幕上，用户没法自己扫自己**。

两条路都实现了：存二维码到相册（`MediaStore`，Android 10+ 免权限）后从 B站 App 的
「扫一扫 → 相册」选它；或者直接粘贴 `SESSDATA`。另外 B 站二维码只有约 3 分钟寿命，
过期要自动换码，否则用户还在找扫一扫时就失效了。

> 开发期间还写了个 `tools/qr_bridge.py`：把手机上的二维码持续同步到电脑屏幕上，
> 这样扫码和确认都方便。它同时也是个可复用的教训 ——
> 二维码**失效后 `ImageView` 仍留在界面上**，所以判断「失效」必须放在「取二维码坐标」之前，
> 否则会对着死码无限空转。

## 6. 息屏播放需要**两个**独立条件，缺一不可

只做其中一个都不够，因为它们在拦两件不同的事：

| 机制 | 拦的是什么 | 对策 |
| --- | --- | --- |
| Wi-Fi 省电 + CPU 浅睡 | 屏幕一关，芯片进低功耗，下一段分片请求建不起连接 | `ExoPlayer.setWakeMode(WAKE_MODE_NETWORK)` —— 播放期间持有 `WifiLock` + `PARTIAL_WAKE_LOCK` |
| 后台进程网络限制 | 非前台进程会被系统与 ROM 限制网络 | 播放期间起 `foregroundServiceType="mediaPlayback"` 的前台服务，让进程不掉成 cached |

**判断根因的一个有用信号**：如果日志里是 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`，
说明**进程还活着、还在发请求**，只是连接建不起来 —— 那就不是"进程被杀"，别往保活方向查。
进程真被冻结时表现是**直接没声音**，而不是报网络错误。

顺带一个与直觉相反的实测结论：**Android 16 上自建 `mediaPlayback` 前台服务不需要配 `MediaSession`**。
声明 service + 四个权限即可（`WAKE_LOCK`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、
`POST_NOTIFICATIONS`），`isForeground=true types=0x00000002` 正常。这省掉了把播放器搬进
`MediaSessionService` 的整套跨进程重构。

另外，**"息屏后继续播" 和 "播放时不自动息屏" 是两件独立的事**：
前者靠上面的 wake lock + 前台服务，后者靠 `View.keepScreenOn`。
只做了前者的话，表现就是「播放中屏幕照样会自己灭」（v0.4 之前就是这样）。

## 7. media3 的 API 不要凭记忆写

`media3 1.4.1` 里这几处和"想当然"不一样，每一处都让编译直接失败：

| 你以为 | 实际 |
| --- | --- |
| `androidx.media3.exoplayer.LoadErrorHandlingPolicy` | 在 **`.upstream`** 子包下 |
| `ExoPlayer.Builder.setLoadErrorHandlingPolicy(...)` | 该方法在 **MediaSource 工厂**上，Builder 里没有 |
| 实现 `LoadErrorHandlingPolicy` 接口只覆写两个方法 | 1.4.1 里还得实现 `getFallbackSelectionFor`，不值得 —— 直接用 `DefaultLoadErrorHandlingPolicy(n)` |
| `PlaybackException.errorCause` | 1.4.1 里叫 **`cause`**（`errorCause` 是 1.5+） |

笨但这台机器上可行的核实办法：从 aar 里解出 `classes.jar`，用 `strings` 读 class 常量池看方法名和字段名。

## 8. 「点了没反应」先怀疑注入权限，再怀疑 App

在小米设备上做 adb UI 自动化，`input tap / keyevent / swipe` 会抛
`SecurityException: Injecting input events requires ... INJECT_EVENTS permission`，
而**界面毫无反应** —— 症状和"App 的按钮坏了"一模一样。需要在开发者选项里额外打开
「USB 调试（安全设置）」（要求插 SIM 卡 + 登录小米账号），开完可能还要重插一次 USB 线。

三个附带的坑：

- **无副作用的探测命令**：`input keyevent 0`（键码 0 无效）。有权限时静默通过，无权限时抛异常。
  探针判据要写成**正向确认** —— 用"输出里没有 INJECT_EVENTS 就算放行"，会在设备掉线时
  把 `adb: no devices/emulators found` 误判成已放行。
- **`input tap` 有时触发不了 Compose 的点击**，尤其 FloatingActionButton。改用
  `input swipe x y x y 150`（原地按住 150ms）即可。
- **但 `input swipe` 在底部导航栏那条区域又会失效** —— 那里贴着系统手势区，注入的原地按压
  会被系统当成手势吃掉，底部的 tab 反而必须用 `input tap`。两种方式各有失效场景，没有通用的那一个。

## 9. media3 的 `PlayerView` 是独立 View 层级

在 Compose 里用 `Modifier.pointerInput` 盖在 `PlayerView`（`AndroidView`）上面**收不到触摸事件** ——
触摸先被那一层原生 View 吃掉。双击暂停、长按倍速这类手势，必须用 Android 原生的
`GestureDetector` 挂在 `PlayerView.setOnTouchListener` 上（返回 `false` 只旁听不消费）。

另外 media3 的全屏按钮**默认隐藏**，只有调过 `setFullscreenButtonClickListener` 之后才会出现在控制条上。

## 10. 全屏状态的判据不能用屏幕方向

「进入/退出全屏」如果用 `requestedOrientation = SENSOR_LANDSCAPE / UNSPECIFIED` 实现，
那么判断"当前是否处于全屏"时**不能看 `configuration.orientation`** ——
手机物理横着放时它同样返回横屏，于是返回键会永远停在「退出全屏」这一步，用户反而出不去了。

正确判据是看**自己有没有设过**：`activity.requestedOrientation == SCREEN_ORIENTATION_SENSOR_LANDSCAPE`。
