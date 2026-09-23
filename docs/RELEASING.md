# 构建与发布

## 本地构建

需要 **JDK 17** 和 **Android SDK**（`compileSdk 35`）。

```bash
git clone https://github.com/logan0116/Bfilter.git
cd Bfilter

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:testDebugUnitTest   # 11 个单元测试
./gradlew :app:lintRelease         # 静态检查（当前 0 errors / 25 warnings）
```

想要**正式签名**的 release 包，在工程根目录建 `keystore.properties`
（已在 `.gitignore` 里，不会进版本库）：

```properties
storeFile=keystore/release.jks
storePassword=你的口令
keyAlias=你的别名
keyPassword=你的口令
```

> 不想自己编译：GitHub Actions 每次 push 会自动出 debug 包，在 **Actions** 页面下 artifact 即可。

## 发布新版本

正式签名的 release 包由 GitHub Actions 在**打 tag 时**自动构建并发布 ——
签名材料放在仓库 Secrets 里，不进代码库。

### 首次配置（只需一次）

1. 把 keystore 导出成单行 base64：

   ```bash
   base64 -w0 keystore/release.jks
   ```

2. 到仓库 **Settings → Secrets and variables → Actions** 添加四个 secret：

   | Secret | 值 |
   | --- | --- |
   | `KEYSTORE_BASE64` | 上一步输出的整行 base64 |
   | `KEYSTORE_PASSWORD` | `keystore.properties` 里的 `storePassword` |
   | `KEY_ALIAS` | `keystore.properties` 里的 `keyAlias` |
   | `KEY_PASSWORD` | `keystore.properties` 里的 `keyPassword` |

### 每次发版

3. 在 `.github/release-notes/<tag>.md` 写好发布说明（CI 优先读它，缺失才回退到 tag 说明），
   递增 `app/build.gradle.kts` 里的 `versionCode` / `versionName`，然后打 tag：

   ```bash
   git tag -a v0.5 -m "v0.5 ..."
   git push origin v0.5
   ```

CI 会自动跑 lint + 单测 → 构建签名包 → 用 `apksigner` 校验签名 → 创建 Release 并附上 APK。
**签名一致**，所以已装旧版的手机会正常收到升级。

### 核对发布产物

Release 建好后值得确认一下签名，这是「已装旧版能否覆盖升级」的唯一依据：

```bash
curl -sL -o /tmp/ci.apk https://github.com/logan0116/Bfilter/releases/download/v0.5/bfilter-v0.5.apk
apksigner verify --print-certs /tmp/ci.apk | grep SHA-256
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256
```

> ⚠️ **不要用文件大小或 sha256 判断"两个包是否一致"**：APK 是 ZIP，内部含构建时间戳，
> 同样大小不代表逐字节相同（v0.4 时我就这么误判过一次）。只有**证书指纹**是可靠判据。

### 两个已知的 CI 坑

- **发布说明必须放仓库文件**，不能靠 tag 说明。`git tag -l --format='%(contents)'` 在本地能拿到，
  在 CI 的浅克隆工作区里拿到的是空 —— 而空值会 fallback 成 commit message，
  结果 Release 正文写成一条 commit log（v0.1 第一次发布就是这么翻车的）。
- **Tag 的 commit 决定跑哪个 workflow**。GitHub 网页上「Delete release」默认**保留 tag**，
  想重发必须先删远端 tag：`git push origin :refs/tags/<tag>`，否则新 commit 上的修复不会被构建。

### 安全说明

Secrets 在传输和存储时都是加密的，Pull Request 触发的 workflow 拿不到它们
（fork PR 不授予 secrets）。但 keystore 一旦泄露就等于别人能签你的包 ——
请确保仓库只有你自己有写权限，并且别把 keystore 直接提交进代码。
