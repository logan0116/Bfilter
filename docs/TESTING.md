# Bfilter 测试记录

> 2026-09-23 · v0.4
> 真机两台：**Mi MIX 2S / LineageOS 22.2 (Android 15)** 与 **红米 K80 至尊版 / HyperOS 3.0 (Android 16)**
> 全部结论来自**真机实测**（adb + uiautomator 语义树 + 截图像素分析 + 系统 `dumpsys` 状态），不是静态审阅。
> 复现命令见本文末尾。

## v0.4 发布前的全量回归

v0.4 只是换图标和名字，但发布前把整条链路重跑了一遍（在红米 K80 / Android 16 上，v0.4 release 包）：

| # | 项 | 结果 | 判据 |
| --- | --- | --- | --- |
| R1 | 静态检查 + 单测 | ✅ | `lintRelease` 0 errors / 25 warnings（24 条"依赖有新版本"+1 条刻意的手势告警）；单测 11/11 |
| R2 | 冷启动读缓存 | ✅ | force-stop 后重启，标题保持缓存时间戳，不联网 |
| R3 | 三 tab 切换 | ✅ | 关注 / UP 主 / 账号 往返正常 |
| R4 | 白名单·添加对话框 | ✅ | FAB 正常弹出（注意：`input tap` 触发不了它，见第五节） |
| R5 | 白名单·非法 UID | ✅ | 「查不到 UID 99999999999 这个 UP 主」，未暴露接口原文 |
| R6 | 白名单·搜索 / 去重 / 删除 | ✅ | 能返回结果；已添加项显示「已添加」；删除后恢复原 9 条 |
| R7 | 断网冷启动 | ✅ | 缓存内容正常显示、无报错 —— 一次同时验证「启动不联网」与「断网可读缓存」 |
| R8 | 断网时刷新 | ✅ | 错误聚合成一条 + 「显示的是 1 分钟前的缓存」+ 缓存完好 |
| R9 | 播放 / 返回 / 断点续播 | ✅ | `正在播放 · 1080P`；返回正常；`已跳到上次位置 1:31` |
| R10 | **息屏后台播放** | ✅ | 息屏 5 分 2 秒、20 次采样（每 15 秒）全部 `Dozing / audio=started / isForeground=true`；播放位置 **1:31 → 7:30**（推进 5:59） |
| R11 | force-stop 后数据保留 | ✅ | 白名单 9 条、缓存、登录态全部保留 |
| R12 | 图标与名称 | ✅ | APK 内 `application: label='SeeLess' icon='res/BW.xml'`；启动器与系统应用信息页均显示正确 |

**R10 的判据值得单独说明**：只看「没报错」是不够的 —— 如果播放器卡在缓冲，界面上同样看不到错误。
所以额外用播放位置是否推进来交叉验证（1:31 → 7:30，与息屏时长吻合）。

**本轮跳过的项**：快速连点刷新。它是防抖测试，而 v0.3/v0.4 完全没触碰刷新逻辑（只改了播放与图标资源），
v0.2 时已通过一次。

**本轮未发现功能性缺陷。**

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

## 三、发现并修复的 8 处缺陷

| # | 缺陷 | 实测表现 | 修复 |
| --- | --- | --- | --- |
| 1 | media3 的 `@UnstableApi` 未 opt-in | Lint **14 errors** | 在 `PlayerScreen` 上用 `androidx.annotation.OptIn`（**不是** Kotlin 的 `@OptIn`，后者不被该检查认可；`@file:OptIn` 更不行，因为该注解没有 FILE 目标） |
| 2 | 网络错误直接把底层异常抛给用户 | 断网时 7 个 UP 各刷一条 `Unable to resolve host "api.bilibili.com": No address associated with hostname`，糊满整屏 | ① `BiliHttp` 把异常翻译成「网络不可用 / 连接超时」；② `VideoRepository` **按原因聚合**成一条：「偷星九月333、司波图、极客湾Geekerwan 等 7 个：网络不可用」 |
| 3 | UID 查不到时暴露 B 站原文 | 界面显示「搜索失败：**啥都木有**」 | `userCard` 捕获后替换为「查不到 UID 99999999999 这个 UP 主」；并按 UID 查询不再误用「搜索失败」前缀 |
| 4 | 搜了没结果与还没搜共用一句提示 | 搜完无结果仍显示「输入昵称后点搜索」 | 加 `searchedOnce` 状态，区分「还没搜」与「没搜到相关的 UP 主」 |
| 5 | 二维码桥对着失效的码空转（**测试工具**） | 二维码失效后 `ImageView` 仍留在界面上，`find_qr` 一直命中 → 处理失效的那个 `elif` **永远轮不到**，实测空转 8 分钟 | 调整判断顺序：**先判失效、再取坐标**（这类"两个条件都可能命中、顺序写反"的 bug，只能靠观察长时间行为发现） |
| 6 | 息屏后网络被系统与 ROM 掐断（**v0.3**） | 播放中静置约 1 分钟即报 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`。关键判据：**进程还活着、还在发请求，只是连接建不起来** —— 若是进程被冻结/杀死，表现会是直接没声音而不是报网络错误 | ① `ExoPlayer.setWakeMode(WAKE_MODE_NETWORK)` 让播放期间持有 WifiLock + PARTIAL_WAKE_LOCK；② 播放期间起 `foregroundServiceType="mediaPlayback"` 的前台服务，进程不再掉成 cached；③ 单段加载重试 3→12 次，网络类错误按 1s→30s 指数退避自动 `prepare()` 重连（`prepare()` 保留播放位置，不会从头开始） |

修复后逐条复测，均已在真机确认（第 8、9、13、21、22、23 项）。

### v0.4 发布后用户报的两处（修于 v0.5）

| # | 缺陷 | 实测表现 | 修复 |
| --- | --- | --- | --- |
| 7 | **全屏状态下返回直接退出播放** | 横屏看视频时右划/返回，本该先退出全屏，实际直接退出了播放页 | `BackHandler` 分两级：只有**我们自己点过全屏按钮**（`requestedOrientation == SENSOR_LANDSCAPE`）才先退回 `UNSPECIFIED`，否则正常退出播放。⚠️ 判据**不能**用 `configuration.orientation` —— 手机物理横着放时它同样返回横屏，那样返回键会永远停在「退出全屏」这一步，用户反而出不去播放页（这个边界问题是修复过程中自己引入、又被测试抓出来的） |
| 8 | **播放中屏幕会自动暗屏 / 息屏** | 正在看视频，系统超时后照样暗屏息屏 | 在 `onIsPlayingChanged` 里设 `rootView.keepScreenOn = playing`，并在 `onDispose` 复位。`keepScreenOn` 只挡**系统超时息屏**，不影响用户按电源键主动关屏 —— 正是要的语义；暂停时放开，免得停在播放页发呆也一直亮着 |

第 8 条的验证办法：把 `screen_off_timeout` 临时改成 15 秒（默认 60 秒），播放中静置 25 秒后查
`dumpsys power` —— 修复前会变成 `Dozing`，修复后仍是 `Awake`。测完注意把系统设置改回去。

第 7 条的验证有个坑：**不能用 `settings put system user_rotation 1` 来模拟全屏** ——
那只是把系统转横，App 的 `requestedOrientation` 仍是 `UNSPECIFIED`，判据不会命中，会得出
「修复没生效」的错误结论。正确做法是**先锁死竖屏**（`accelerometer_rotation 0` + `user_rotation 0`），
再点 App 的全屏按钮：如果屏幕变横，才说明是 App 主动设的全屏状态。

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
| ~~长时间息屏~~（v0.3） | ✅ **初步验证** | 见下一行 |
| ~~真实骑行场景~~（v0.3） | ✅ **初步验证** | **2026-09-23 通勤实测（含移动）：后台播放 1 小时未断。** 这是 v0.3 那批代码负责的核心场景，也是此前唯一的空白。单次观测，还打算再多跑几次 |
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

4. **`input tap` 点不动某些 Compose 组件，尤其 FloatingActionButton；但换 `input swipe` 也不是万能。**
   同一个坐标，`input tap` 完全没反应，换成 `input swipe x y x y 150`（原地按住 150ms）就正常 ——
   差别只在 DOWN 与 UP 之间的时间间隔。本轮测白名单的「添加 UP 主」时卡在这里很久，
   一开始误判成"按钮坏了"或"坐标点偏了"。

   ⚠️ **反过来也有陷阱**：底部导航栏那一条贴着**系统手势区**，在那里注入的原地按压会被系统当成
   手势吃掉 —— 于是 `input swipe` 又失效了，底部的 tab 必须用 `input tap`。

   **结论：两种注入方式各有失效场景，没有通用的那一个。** 更重要的教训是流程上的 ——
   本轮在这个小项上连续重试了两轮，两次的"通过"都是假通过（tab 压根没切过去，点击全落在空白处）。
   **看到"界面没变化"时应当先停下来确认注入是否生效，而不是换个姿势接着重试。**

## 六、复现命令

> 以下命令都在**仓库根目录**执行（本文档位于 `docs/`，但命令里的路径都是相对仓库根的）。

```bash
cd ~/PycharmProjects/Bfilter
export GRADLE_USER_HOME=$PWD/.gradle-home

# 静态检查 + 单测 + 出包
tools/build.sh :app:lintRelease :app:testDebugUnitTest :app:assembleRelease

# 装到手机
tools/adb-wrap.sh install -r app/build/outputs/apk/release/app-release.apk

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
