# 只看关注 · Bfilter

一个**只有你指定 UP 主更新**的 B 站客户端。没有推荐流、没有热搜、没有自动连播 —— 不是把那些入口藏起来，而是它们在这个 app 里**根本不存在**。

> 解决的问题：卸载 B 站能挡住推荐流，但想看关注的内容时又得装回来。装回来就又被推荐流抓住。
> 真正的解法不是"屏蔽"，是**换掉信息源**。

---

## 一、为什么是独立客户端

| 方案 | 否决理由 |
| --- | --- |
| 卸载 B 站 App | 想看关注内容时不得不装回来，回到原点 |
| LSPosed 模块改官方 App | 依赖 root；且官方 App 的推荐入口随版本变动，需持续跟进 |
| 无障碍服务遮挡 | 靠识别界面元素，脆弱、误判、体验粗糙 |
| **独立客户端（本方案）** | 不依赖 root；**信息流由本地按白名单组装，推荐位在数据层就不存在** |

---

## 二、构建（不重复下载 SDK）

SDK 与 Gradle **只读复用** `~/PycharmProjects/counter` 工程内已装好的构建链：

| 资产 | 位置 | 说明 |
| --- | --- | --- |
| Android SDK | `counter/.android-sdk`（608M） | platforms/android-35、build-tools 34/35，路径写在 `local.properties` |
| Gradle 8.9 | `counter/.tooling/gradle` | 直接调用，不装 wrapper |
| 依赖缓存 | **本工程 `.gradle-home`（1.2G，已复制）** | 必须可写，故复制一份到本工程 |
| adb | `tools/adb-wrap.sh` | 沙箱 /dev 被裁剪看不到 USB，借道 privileged 容器跑 adb |

```bash
cd ~/PycharmProjects/Bfilter
export GRADLE_USER_HOME=$PWD/.gradle-home
~/PycharmProjects/counter/.tooling/gradle/bin/gradle :app:assembleDebug
```

> 若 counter 工程被移动/删除：改 `local.properties` 的 `sdk.dir`，并把 `.tooling/gradle` 换成本机任意 Gradle 8.9+。

装到手机：

```bash
tools/adb-wrap.sh devices
tools/adb-wrap.sh install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 三、数据来源与风控（实测，2026-09-22）

**一句话结论：白名单 UP 的更新走「动态流」接口，别碰「空间投稿」接口。**

| 用途 | 接口 | 实测 |
| --- | --- | --- |
| **白名单 UP 的更新** | `x/polymer/web-dynamic/v1/feed/space`（需 wbi 签名） | ✅ code 0，一次拿到该 UP 全部最新动态（视频/图文/转发） |
| 视频详情（拿 cid / 封面） | `x/web-interface/view` | ✅ code 0 |
| 播放地址 | `x/player/playurl` | ✅ DASH 可用，游客上限 480P |
| 搜索 UP 主 / 视频 | `x/web-interface/wbi/search/type` | ✅ code 0（wbi 签名已验证，见单测） |
| 按 UID 查 UP 主 | `x/web-interface/card` | ✅ code 0 |
| ✗ 空间投稿（旧版） | `x/space/arc/search` | ❌ 对游客长期 `HTTP 412 request was banned` |
| ✗ 空间投稿（wbi 版） | `x/space/wbi/arc/search` | ❌ 同样 412 |
| ✗ 全站关注流 | `x/polymer/web-dynamic/v1/feed/all` | ❌ `-101 账号未登录` |

### 两个风控坑（都实际踩过）

1. **`x/space/arc/search` 对游客基本不可用。** 它偶尔会漏放一次成功（我据此写完了一套实现），
   但真机上稳定 412。**不要用。**
2. **动态流有"软限流"**：同一个 UP 短时间重复请求会返回 `code 0` 但 `items` 为空 ——
   不报错，看起来就像"这个 UP 没发东西"。对策：
   - 取数为空时**不覆盖缓存**，退回上次内容并如实提示（`VideoRepository`）；
   - 启动只读缓存，只有用户手动刷新或白名单变动才联网（`FeedViewModel`）。

> **方法论教训（比结论更值钱）**：排查 412 时我先做了一组对照实验 ——
> "不带额外参数 → 成功，带 `order`/`platform`/`web_location` → 全部 412"，证据看起来非常硬，
> 于是得出"参数导致风控"的结论并改了两版代码。
> 但把成功那组放到**后面**再测一次，结论立刻反转：原来只是"每轮第一个请求恰好能过"。
> **凡 B 站风控类结论，必须做顺序反转实验（把"成功"的变体挪到后面重测）才能采信。**

**请求必需的头**：`User-Agent`、`Referer: https://www.bilibili.com`、`Origin: https://www.bilibili.com`，
以及 cookie `buvid3` / `buvid4` / `b_nut` —— 由 `x/frontend/finger/spi` 取到后**手动种入**
（该接口不走 `Set-Cookie`，这是最容易踩的坑）。

---

## 四、工程结构

```
app/src/main/java/com/mozinodey/bfilter/
├── MainActivity.kt              入口 + 两个页面间的导航
├── BfilterApp.kt                Application（持有 store / repository）
├── data/
│   ├── remote/
│   │   ├── BiliHttp.kt          OkHttp 唯一出口：公共头、buvid 初始化、串行限速、退避重试
│   │   ├── Wbi.kt               wbi 签名（纯函数，有单测锚定 Python 参考值）
│   │   ├── BiliApi.kt           接口封装（唯一需要跟随 B 站改动的地方）
│   │   └── ...
│   ├── WhitelistStore.kt        白名单 UP（DataStore + JSON）
│   ├── FeedCacheStore.kt        时间线缓存（软限流的兜底）
│   ├── LoginStore.kt            登录凭证（SESSDATA，只落本机，无导出入口）
│   └── VideoRepository.kt       聚合：拉白名单 → 合并 → 排序 → 过滤 → 写缓存
├── domain/Models.kt             统一数据模型
└── ui/
    ├── FeedScreen.kt            主页：白名单时间线（唯一的信息流）
    ├── WhitelistScreen.kt       白名单增删（搜索昵称 / 直接填 UID）
    ├── AccountScreen.kt         账号页：扫码登录 / 粘贴 SESSDATA / 退出登录
    ├── QrCode.kt                二维码生成 + 存相册
    ├── PlayerScreen.kt          播放页（Media3 + Referer 注入，播完即停）
    ├── Components.kt / Format.kt
    └── theme/Theme.kt
```

工具：

```bash
tools/build.sh          # 构建包装（固化沙箱所需的环境变量，见下）
tools/adb-wrap.sh       # adb（借 privileged 容器）
tools/ui_probe.py       # 靠语义树驱动界面做真机回归（Compose 没有 view id）
```

> `tools/build.sh` 存在的理由：沙箱下 `$HOME` 只读，AGP 默认要在 `~/.android` 建 debug keystore
> 会直接失败（`Unable to create debug keystore`）。脚本把 `ANDROID_USER_HOME` 指到工程内。

---

## 五、已知约束与风险

1. **`x/space/arc/search` 不可用** —— 对游客长期 412，因此取数走「动态流」（见第三节）。
   *不确定项*：动态流在一次刷新拉多个 UP（实测间隔 2s，3/4 成功）时是否会被更严格限流，
   白名单扩大到十几个 UP 后需要重新实测。
2. **游客态播放上限 480P** —— 要 1080P 必须带登录 cookie（第一阶段不做）。
3. **接口会变** —— 所有接口调用集中在 `data/remote/BiliApi.kt`，坏了只改这一个文件。
4. **不自动连播** —— 这是产品决策不是缺陷：播放页播完即停，没有下一个。
5. **合规** —— 自用工具，不公开分发登录凭证；不内置任何 B 站账号。

## 六、登录（v0.2）

登录的目的有两个：**1080P**，以及**更宽松的接口风控**（未登录时的软限流是体验的主要干扰源）。

登录凭证（`SESSDATA` 等）的存放规则：**只写本机 DataStore、只出现在请求头里**，
不写日志、不上报、无导出入口；账号页可一键退出登录并清除。

### 一个手机端特有的问题

扫码登录的经典场景是"二维码在电脑上、手机来扫"。但本 App 的二维码和哔哩哔哩 App
**在同一块屏幕上**，用户没法自己扫自己。因此提供两条路：

| 方式 | 路径 | 适用 |
| --- | --- | --- |
| 扫码（推荐） | 点「存二维码到相册」→ 哔哩哔哩 App → 扫一扫 → 相册 → 选 `Pictures/Bfilter/bfilter-login-qr.png` | 手机本身，不需电脑 |
| 粘贴 SESSDATA | 电脑浏览器登录 B 站 → F12 → Application → Cookies → 复制 `SESSDATA` → 粘进 App | 有电脑时最省事 |

实现要点：
- 二维码用 `zxing` 的 `QRCodeWriter` 生成（只引这一个类，不带它的 UI 包）；
- 存相册走 `MediaStore`，Android 10+ 无需任何存储权限；
- **二维码只有约 3 分钟寿命**，过期会自动换新码（最多 3 轮），用户不用管；
- 扫码状态机：`86101` 待扫码 / `86090` 已扫码待确认 / `0` 成功 / `86038` 已过期；
- 登录凭证同时出现在 `data.url` 的 query 和响应的 `Set-Cookie` 头里，**两边都解析**，
  以 `Set-Cookie` 为准（其值未经 URL 编码），这段逻辑有单测覆盖。

---

## 七、交付状态（v0.3，2026-09-22 真机验证）

> 完整的测试矩阵（16 项）、发现并修复的 4 处缺陷、未覆盖项与自动化限制，见 **[TESTING.md](TESTING.md)**。

| 项 | 状态 |
| --- | --- |
| 构建 | ✅ debug 15.4 MB / **release 2.5 MB**（R8 + v2 签名） |
| 静态检查 | ✅ Android Lint **0 errors**（初始 14 个，已修）；24 warnings 全是「依赖有新版本可用」 |
| 单测 | ✅ 11 个通过（wbi 签名对齐 Python 参考、percent 编码、时长/播放量解析、登录凭证提取） |
| 真机功能 | ✅ Mi MIX 2S / LineageOS 22.2：冷启动读缓存、三 tab、白名单增删改查、刷新、播放、断网、连点、生命周期 |
| 播放 | ✅ 实测 ExoPlayer 拉 DASH 播放成功（未登录 `正在播放 · 480P`）；画面以像素熵量化验收 |
| 缓存策略 | ✅ 冷启动不打网络；断网时错误聚合成一条且**缓存内容完好保留** |
| 限流压测 | ✅ **7 个 UP 主**的白名单，一次刷新（串行、间隔 1.6s）**全部拉取成功，零失败项** |
| 扫码登录 | ✅ 完整流程实测通过：二维码桥同步到 PC → 扫码 → 确认 → 登录成功（`SD0116`） |
| 登录后清晰度 | ✅ **480P → 1080P**（这是登录的第一目的，已实测） |
| 登录后风控 | ✅ 连续刷新 3 次全部成功，未再复现登录前的软限流（对照强度见 TESTING.md） |
| 登录态持久化 | ✅ 杀进程重启后仍为已登录（cookie 落盘 + 启动注入均正常） |

**尚未做**：关键词/分区黑名单、图文动态。

> 注意：v0.1 其实**天然就不需要"屏蔽"** —— 信息流只由白名单组装，推荐位在数据层就不存在。
> 关键词/分区过滤要到做「搜索页」时才有意义（主动搜索时避免被无关结果带走）。

---

## 八、下一步（按优先级）

- [x] 白名单 UP 的更新时间线（主页唯一入口）
- [x] 白名单增删（搜索昵称 / 直接填 UID）
- [x] 点开播放（Media3，播完即停）
- [x] 白名单 7 个 UP 下的限流压测（一次刷新全部成功）—— 若继续扩到 15+ 个需重测
- [x] 扫码登录（1080P + 更宽松的风控）
- [ ] 关键词 / 分区黑名单（配合搜索页才有意义）
- [ ] 扫码登录（拿 1080P，且登录态风控宽松得多 —— 若限流成为瓶颈，这是正解）
- [ ] 图文动态（当前只取了 `DYNAMIC_TYPE_AV`，UP 主的图文动态被丢掉了）
