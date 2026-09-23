<div align="center">

<img src="docs/icon-source.png" width="128" alt="SeeLess 图标">

# SeeLess

**只看你亲手挑的那几个 UP 主。推荐流不是被屏蔽了 —— 是这个 app 里根本没有。**

[![Build](https://github.com/logan0116/Bfilter/actions/workflows/build.yml/badge.svg)](https://github.com/logan0116/Bfilter/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF)

<sub>仓库目录名是 <code>Bfilter</code>（历史名称），应用显示名是 SeeLess。</sub>

</div>

---

## 它解决什么问题

卸载 B 站能挡住推荐流，但想看关注的内容时又得装回来 —— 装回来就又被推荐流抓住。这是个死循环。

SeeLess 走另一条路：**不屏蔽，换数据源**。它的信息流完全由你手动加入白名单的 UP 主组装而成，
推荐位在**数据层**就不存在 —— 不是被藏起来、不是被 CSS 盖住、不是靠 hook 拦截，
而是这个 app 从来不请求那些接口。

**这条流是会被刷到头的。** 打开发现没有更新是预期状态，不是加载失败 ——
这是它和你手机里其他视频 App 最大的区别。

| | |
|---|---|
| <img src="docs/screenshots/01-feed.png" width="230"> | <img src="docs/screenshots/02-whitelist.png" width="230"> |
| 主页就是你选的 UP 主的更新，按时间混排 | 白名单：搜昵称或直接填 UID |
| <img src="docs/screenshots/03-player.png" width="230"> | <img src="docs/screenshots/04-account.png" width="230"> |
| 播完即停 —— 没有连播、没有"相关推荐" | 登录之后 480P → 1080P |

## 功能

- **唯一的一条流** —— 主页只有白名单 UP 主的更新，没有推荐、热搜、直播入口
- **白名单管理** —— 搜索昵称添加，或直接填 UID（新账号 UID 有 16 位，也支持）
- **内置播放器** —— 双击暂停/播放、长按 1× ↔ 2× 倍速、全屏、断点续播
- **息屏继续听** —— 播放中保持屏幕常亮，主动锁屏后也不会断流；断网按 1s→30s 指数退避自动重连
- **登录** —— 扫码或粘贴 `SESSDATA`，换来 1080P 与更宽松的接口频率限制
- **拉一次存一次** —— 启动只读本地缓存、不联网；断网时旧内容照常看，错误提示合并成一条
- **不依赖 root** —— 普通应用，不需要 LSPosed、不需要无障碍服务

## 安装

到 [Releases](https://github.com/logan0116/Bfilter/releases) 下载最新的 `bfilter-vX.Y.apk` 直接安装。
已装旧版的会**正常覆盖升级**（签名一致，不会清数据）。

首次使用：到「UP 主」页添加你想看的人 —— 主页只会出现他们的更新，没有推荐流。

想自己编译 → [docs/RELEASING.md](docs/RELEASING.md)

## 验证范围

**先说定位：这是一个自用工具。** 功能取舍按作者自己的使用习惯来，不追求覆盖更多机型或使用场景 ——
所以下面这些「未验证」是**已知边界，不是待办事项**。代码公开只是为了方便自己查阅与备份。

结论全部来自真机实测，但**只覆盖了两台机器**：

| 项 | 情况 |
| --- | --- |
| 实测设备 1 | 红米 K80 至尊版 · Android 16 / HyperOS 3.0 |
| 实测设备 2 | Mi MIX 2S · Android 15 / LineageOS 22.2 |
| 声明的 `minSdk` | 24（Android 7.0）—— **但 Android 7–14 没有实机验证过**，只是编译能通过 |

**未验证的场景**（已知空白，不是"应该没问题"）：移动中的长播放（骑行切基站、进隧道）、
单次 1 小时以上、系统低电量模式、平板 / 大字体 / 深色主题、白名单超过 15 个 UP。

逐项测试记录见 [docs/TESTING.md](docs/TESTING.md)。

## 隐私

- 登录凭证（`SESSDATA` 等）**只写在本机 DataStore、只出现在请求头里**
- 不写日志、不上报、没有导出入口；账号页可一键退出并清除
- 应用不收集任何数据、没有第三方 SDK、没有广告
- 全部网络请求只发往 `*.bilibili.com` 与视频 CDN

## 免责声明

- 这是**非官方**客户端，与哔哩哔哩（bilibili）无任何关联
- 不提供、不托管任何内容；所有数据均来自 B 站公开接口，版权归原作者所有
- 仅供学习与个人使用，请遵守 B 站用户协议；请勿用于商业用途或大规模抓取
- 接口随时可能变动，使用风险自负

## License

[MIT](LICENSE) © 2026 logan0116

---

## 更多文档

| 文档 | 内容 |
| --- | --- |
| [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md) | 踩过的坑：B 站接口风控、media3 的 API 差异、安卓 UI 自动化 |
| [docs/RELEASING.md](docs/RELEASING.md) | 本地构建、签名配置、打 tag 自动发版 |
| [docs/TESTING.md](docs/TESTING.md) | 逐项真机测试记录与已修缺陷清单 |
