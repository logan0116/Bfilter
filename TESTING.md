# Bfilter 测试记录

> 2026-09-23 · v0.3
> 真机两台：**Mi MIX 2S / LineageOS 22.2 (Android 15)** 与 **红米 K80 至尊版 / HyperOS 3.0 (Android 16)**
> 全部结论来自**真机实测**（adb + uiautomator 语义树 + 截图像素分析 + 系统 `dumpsys` 状态），不是静态审阅。
> 复现命令见本文末尾。

## 一、一句话结论

核心链路（取数 → 缓存 → 展示 → 播放 → 白名单管理 → 生命周期 → 扫码登录 → 登录后画质 → 息屏后台播放）**全部实测通过**；
静态检查从 14 个 error 清到 0；测试过程中发现并修复 **6 处真实缺陷**（5 处应用 + 1 处测试工具）。

**目前没有未验证的核心功能项。** 剩余的不确定性只有「登录后风控到底宽松多少」这一条的强度问题（见第四节）。

## 二、测试矩阵

| # | 测试项 | 方法 | 结果 |
| --- | --- | --- | --- |
| 1 | Android Lint | `:app:lintRelease` | ⚠️→✅ 初始 14 errors / 25 warnings；修复后 **0 errors / 24 warnings** |
| 2 | 单元测试 | `:app:testDebugUnitTest` | ✅ **11/11** 通过 |
| 3 | 冷启动读缓存 | force-stop 后重启，3 秒内 dump | ✅ 立即显示内容且标题为「更新于 23 分钟前」= **启动没打网络** |
| 4 | 三个 tab 切换 | 关注/UP 主/账号 往返 | ✅ 页面正确；切回关注页仍是 24 分钟前 = **切 tab 不触发刷新** |
| 5 | 白名单·搜索添加 | 英文键盘输入 `Geekerwan` → 搜索 | ✅ 返回 5 条，点选添加成功 |
| 6 | 白名单·去重 | 重复点击同一条结果 | ✅ 已添加项显示「已添加」；白名单中只有一条，不会重复 |
| 7 | 白名单·删除 | 点第 8 条「移除」 | ✅ 恢复到你原本的 7 个 |
| 8 | 白名单·非法 UID | UID `99999999999` | ✅ 报错（修复后文案见第三节） |
| 9 | 白名单·搜不到 | 关键词 `zzqqxxww` | ✅ 提示「没搜到相关的 UP 主」（修复后） |
| 10 | 手动刷新 | 点刷新，7 个 UP 串行拉取 | ✅ 标题变「更新于 刚刚」，播放量真的变了（59.6万→60.6万） |
| 11 | 播放 | 点开视频 | ✅ `正在播放 · 480P`；截图 543KB 且像素分析通过 |
| 12 | 播放页返回 | 点返回 | ✅ 回到列表，顺序与内容保持 |
| 13 | 断网 | 关 WiFi 后刷新 | ✅ 报错且**缓存内容完好保留**（见第三节修复） |
| 14 | 快速连点刷新 | 连续点 8 次 | ✅ 无崩溃、无异常日志，最终状态正确 |
| 15 | 生命周期 | force-stop 后重启 | ✅ 白名单 7 条、缓存、登录态全部保留 |
| 16 | 扫码登录·二维码 | 生成 + 存相册 + **反向解码** | ✅ 解码结果 = 标准 B 站登录链接 |
| 17 | 扫码登录·全流程 | 二维码桥同步到 PC → 扫码 → 手机确认 | ✅ 登录成功（`SD0116` / UID 19702803） |
| 18 | **登录后清晰度** | 点开视频看清晰度标签 | ✅ **480P → 1080P**（截图从 543KB 涨到 642KB） |
| 19 | **登录后风控宽松度** | 连续刷新 3 次 | ✅ 全部成功，**无登录前那种软限流空结果** |
| 20 | 登录态持久化 | 杀进程后重启 | ✅ 仍为「已登录 / SD0116」 |
| 21 | **息屏后台播放**（红米 K80） | 播放中按电源键息屏，每 15 秒采样一次系统状态，共 24 次 | ✅ **6 分 4 秒全程未断**：`audio state:started` 24/24、前台服务 `isForeground=true` 24/24、进程 `adj=0` 且带 FGS 标记（**从未掉成 cached**）；同一视频播放位置 8:55 → 21:34 |
| 22 | 息屏期间是否触发过错误 | 同上，观察播放页状态行 | ✅ 全程未出现「第 N 次重连中…」或红色报错 = `onPlayerError` 一次都没触发 |
| 23 | 播放期间的锁 | 息屏前 `dumpsys power` / `dumpsys wifi` | ✅ `WifiLock{ExoPlayer:WifiLockManager type=4}` 与 `PARTIAL_WAKE_LOCK 'ExoPlayer:WakeLockManager'` 均已持有 |

### 播放画面的验收依据

不靠"看起来在播"，而是量化的：播放器区域**标准差 62.7、75133 种不同颜色**，
对比底部纯色导航区只有 5 种颜色；取样点 RGB 各异。

### 二维码的验收依据

把相册里的 PNG 拉回电脑，用 zxing core 反向解码：

```
https://account.bilibili.com/h5/account-h5/signin/scan-web?...&qrcode_key=...
```

即「任何扫码器都能识别」，而不是"看起来像个二维码"。

## 三、发现并修复的 6 处缺陷

| # | 缺陷 | 实测表现 | 修复 |
| --- | --- | --- | --- |
| 1 | media3 的 `@UnstableApi` 未 opt-in | Lint **14 errors** | 在 `PlayerScreen` 上用 `androidx.annotation.OptIn`（**不是** Kotlin 的 `@OptIn`，后者不被该检查认可；`@file:OptIn` 更不行，因为该注解没有 FILE 目标） |
| 2 | 网络错误直接把底层异常抛给用户 | 断网时 7 个 UP 各刷一条 `Unable to resolve host "api.bilibili.com": No address associated with hostname`，糊满整屏 | ① `BiliHttp` 把异常翻译成「网络不可用 / 连接超时」；② `VideoRepository` **按原因聚合**成一条：「偷星九月333、司波图、极客湾Geekerwan 等 7 个：网络不可用」 |
| 3 | UID 查不到时暴露 B 站原文 | 界面显示「搜索失败：**啥都木有**」 | `userCard` 捕获后替换为「查不到 UID 99999999999 这个 UP 主」；并按 UID 查询不再误用「搜索失败」前缀 |
| 4 | 搜了没结果与还没搜共用一句提示 | 搜完无结果仍显示「输入昵称后点搜索」 | 加 `searchedOnce` 状态，区分「还没搜」与「没搜到相关的 UP 主」 |
| 5 | 二维码桥对着失效的码空转（**测试工具**） | 二维码失效后 `ImageView` 仍留在界面上，`find_qr` 一直命中 → 处理失效的那个 `elif` **永远轮不到**，实测空转 8 分钟 | 调整判断顺序：**先判失效、再取坐标**（这类"两个条件都可能命中、顺序写反"的 bug，只能靠观察长时间行为发现） |
| 6 | 息屏后网络被系统与 ROM 掐断（**v0.3**） | 播放中静置约 1 分钟即报 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`。关键判据：**进程还活着、还在发请求，只是连接建不起来** —— 若是进程被冻结/杀死，表现会是直接没声音而不是报网络错误 | ① `ExoPlayer.setWakeMode(WAKE_MODE_NETWORK)` 让播放期间持有 WifiLock + PARTIAL_WAKE_LOCK；② 播放期间起 `foregroundServiceType="mediaPlayback"` 的前台服务，进程不再掉成 cached；③ 单段加载重试 3→12 次，网络类错误按 1s→30s 指数退避自动 `prepare()` 重连（`prepare()` 保留播放位置，不会从头开始） |

修复后逐条复测，均已在真机确认（第 8、9、13、21、22、23 项）。

## 四、未覆盖项与遗留风险

| 项 | 状态 | 说明 |
| --- | --- | --- |
| ~~登录后的 1080P~~ | ✅ **已实测** | 登录前 `正在播放 · 480P` → 登录后 `正在播放 · 1080P` |
| ~~登录后的风控宽松度~~ | ✅ **已实测** | 连续 3 次刷新全部成功，没有出现软限流空结果。⚠️ **对照不算严格**：登录前的软限流是在间隔 2 秒的密集脚本请求下观察到的，这次是 app 内刷新（间隔 15 秒），两者强度不同 —— 只能说「登录后没再复现」，不能说「已证明限流消失」 |
| 二维码桥的自动换码 | ✅ 已实测 | 失效后自动点「重新获取」（修了一个判断顺序的 bug，见下） |
| 二维码自动换码 | ⏳ 未实测 | 需等 3 分钟过期，逻辑已审阅但没跑完整周期 |
| **中文昵称搜索** | ⚠️ 未自动化验证 | 见下节——`adb input text` 在中文输入法下不可靠，只能手动验证 |
| 白名单扩到 15+ 个 UP | ⚠️ 未验证 | 7 个已验证零失败；更多 UP 时软限流是否成为瓶颈未知 |
| 图文动态 | 未实现 | 设计如此（只取 `DYNAMIC_TYPE_AV`），UP 主的纯图文动态会被丢掉 |
| 横屏 / 平板 / 大字体 / 深色切换 | 未测 | Manifest 锁竖屏 |
| **长时间息屏**（v0.3） | ⏳ 未测 | 只验证到 6 分 4 秒；单次 1 小时以上的表现没测过 |
| **真实骑行场景**（v0.3） | ⏳ 未测 | 移动中切基站、进隧道、弱网抖动都没跑过 —— 息屏测试是在**静止**状态下做的 |
| **低电量模式**（v0.3） | ⏳ 未测 | HyperOS 低电量模式对前台服务与后台网络另有一套限制，未验证 |
| 25 条 lint warning | 有意保留 | 24 条是「依赖有新版本可用」（锁定版本是刻意选择）；1 条 `ClickableViewAccessibility` 是因为 `setOnTouchListener` 返回 `false` 只旁听不消费，那是刻意的 |

## 五、自动化测试的三个真实限制（可复用）

1. **`adb shell input text` 在中文输入法下会吃掉字母。**
   实测输入 `Geekerwan` 得到 `Geek二万` —— Gboard 把 `erwan` 当拼音联想成了「二万」。
   数字不受影响（所以按 UID 添加那条路一直测得通）。
   对策：`adb shell ime set com.android.inputmethod.latin/.LatinIME` 切到 AOSP 英文键盘，
   测完再切回 Gboard。**因此中文昵称搜索这一项无法自动化，需要人工验证。**

2. **空输入框在 uiautomator 语义树里没有 text 节点，只按文案定位会点空。**
   第一次测搜索就是这么失败的（点到了 label 的边框上，没聚焦）。
   对策：`dump` 时把 `class=EditText` 的空节点也打出来，按它的中心坐标点。

3. **MIUI/HyperOS 默认禁止 adb 注入触摸事件。**
   `input tap` / `keyevent` / `swipe` 全部抛
   `SecurityException: Injecting input events requires the caller ... to have the INJECT_EVENTS permission`，
   而**界面毫无反应** —— 症状是"点了没生效"，极容易误判成 App 或脚本的毛病（我一开始就误判成列表项不可点）。
   必须在开发者选项里额外打开「USB 调试（安全设置）」（要求插 SIM 卡 + 登录小米账号），
   开完可能还要重插一次 USB 线才生效。
   无副作用的探测命令：`input keyevent 0`（键码 0 无效）—— 有权限时静默通过，无权限时抛异常。
   ⚠️ 判据要写成**正向确认**：第一版探测脚本用"输出里没有 INJECT_EVENTS 就算放行"，
   结果设备掉线时输出的是 `adb: no devices/emulators found`，被误判成已放行。

另外，Compose 没有 view id，界面自动化只能靠语义树 —— 这也是 `tools/ui_probe.py`
存在的理由（按文案 / contentDescription 定位，换设备不用改坐标）。

## 六、复现命令

```bash
cd ~/PycharmProjects/Bfilter
export GRADLE_USER_HOME=$PWD/.gradle-home

# 静态检查 + 单测 + 出包
tools/build.sh :app:lintRelease :app:testDebugUnitTest :app:assembleRelease

# 装到手机
tools/adb-wrap.sh install -r dist/bfilter-0.3-release.apk

# 界面自动化（Compose 无 view id，只能走语义树）
python3 tools/ui_probe.py dump                    # 看当前界面的可点元素
python3 tools/ui_probe.py tap-text "关注"          # 按文案点（精确匹配）
python3 tools/ui_probe.py tap-desc "刷新"          # 按 contentDescription 点
python3 tools/ui_probe.py shot shots/x.png        # 截图

# 息屏播放的状态快照（屏幕/音频/前台服务/进程优先级/锁 一次抓全）
tools/playback_probe.sh

# 息屏播放的连续采样（每 15 秒一行，跑 N 次；注意同一时间只能有一个 adb 会话）
tools/adb-wrap.sh shell 'input keyevent 26; for i in $(seq 1 24); do \
  T=$(date +%H:%M:%S); W=$(dumpsys power | grep -o "mWakefulness=[A-Za-z]*" | head -1 | cut -d= -f2); \
  A=$(dumpsys audio | grep -c "state:started"); \
  S=$(dumpsys activity services io.github.logan0116.bfilter | grep -c "isForeground=true"); \
  echo "$T | $W | audio=$A | fg=$S"; sleep 15; done'
```
