# 眠声 · Android 夜间声音监测

眠声是一款仅使用手机麦克风的 Android 夜间声音监测应用，记录鼾声和疑似梦话，支持早晨查看报告和回听录音。声音识别与录音存储均在本机完成。

## 直接下载并安装 APK

**[下载眠声 v0.1.1 Release APK](https://github.com/denniskang28/sleepsound/releases/download/v0.1.1/sleepsound-0.1.1-release.apk)** · Android 9 / API 28 及以上 · 约 32 MB

也可以打开 [Releases 下载页面](https://github.com/denniskang28/sleepsound/releases)，在版本下方的 **Assets** 中选择以 `.apk` 结尾的安装包。`Source code (zip)` 和 `Source code (tar.gz)` 是源码，不能直接安装到手机。

1. 在 **Android 手机浏览器**中打开上面的 APK 下载链接，下载完成后点击文件；也可以在系统“文件 / 文件管理器 → 下载”中找到它。
2. 如果系统提示“不允许安装未知应用”，点击提示中的“设置”，为当前使用的浏览器或文件管理器开启 **允许来自此来源的应用**，然后返回安装页面。
3. 点击 **安装**，完成后打开 **眠声**。直接安装 APK 不需要 Android Studio、开发者模式或 USB 调试。
4. 完成引导，点击“开始第一次监测”，按提示允许麦克风权限；如果系统询问通知权限，建议允许，以便查看后台监测状态和暂停操作。
5. 完成开始前检查，将手机放在床头，点击“开始监测”。早晨点击“结束监测 → 结束并查看报告”，查看报告并回听片段。

如果先下载到电脑，可以通过 USB、蓝牙或文件传输工具把 `.apk` 文件复制到手机，再在手机文件管理器中点击安装。

当前提供的是 **v0.1.1 Release 签名版**，包含新的月牙声波桌面图标，关闭调试并启用代码压缩、混淆与资源裁剪。正式包名为 `com.miansheng.sleep`；此前的 Debug 版包名带 `.debug`，两者可同时安装，数据不会自动迁移。新安装没有演示记录；短时间试用会显示“数据不足”，因为有效监测少于 3 小时不计算鼾声占比。识别准确度和真实手机整夜稳定性仍需验证，首版不提供 REM / 浅睡 / 深睡分期。

下载文件的 SHA-256 校验值见同一版本 Assets 中的 `SHA256SUMS.txt`。

## 使用 Android Studio 开发运行

按 `docs/ui/sleep-ui/sleep.dc.html` 原型实现。技术栈：Kotlin、Jetpack Compose、Room、AudioRecord 麦克风前台服务与离线 YAMNet 音频分类。

需要 Android Studio、JDK 17 或 21、Android SDK 35；设备 Android 9 / API 28 以上。

1. 克隆本仓库，用 Android Studio 打开包含 `settings.gradle.kts` 的项目根目录，等待 Gradle 同步。
2. 连接手机或启动模拟器，运行 `app`。
3. 首次使用完成引导，点击开始、授予麦克风权限，完成环境检查。
4. 结束监测后查看真实报告。新安装默认没有演示记录。

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
# 有运行中的 Android 模拟器或测试设备时：
./gradlew :app:connectedDebugAndroidTest
```

本地 SDK 路径放入不提交的 `local.properties`，例如 `sdk.dir=/path/to/Android/sdk`。Debug 安装包输出到 `app/build/outputs/apk/debug/app-debug.apk`，包名 `com.miansheng.sleep.debug`。Release 包名 `com.miansheng.sleep`。

Release 构建需配置本机签名密钥，执行 `./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease`，安装包输出到 `app/build/outputs/apk/release/app-release.apk`。签名配置、校验与发布步骤见 [Release 发布说明](docs/releasing.md)。私钥和密码不得提交到仓库。

## 已实现范围

- 原型的引导、首页、权限说明、开始前检查、夜间监测、报告、录音列表、播放器、历史、日历、7/30 天趋势、标签、感受、设置与异常状态。
- 实际检查麦克风、电量、可用空间，以及基于短时音频能量的相对环境提示。
- 由可见界面启动麦克风前台服务，持续通知、暂停/恢复、结束确认、锁屏后采集。
- 16 kHz 单声道采集，本机 YAMNet 分类；连续声音合并为事件，使用有限内存环形缓冲，AAC 32 kbit/s 事件录音与真实波形。
- Room 持久化会话、事件、有效采集/暂停/中断区间、收藏、修正、生活标签及备注。
- 真实录音回听、拖动跳转、前后十秒、上一段/下一段、收藏、修正、撤销最近修正、FileProvider 主动分享。
- 7/30 天保留期限、手动清理、收藏保护、每日到期清理；删除音频保留统计，删除会话则删除其全部关联数据。
- 麦克风异常/系统静音后的重试和中断区间；进程退出后重新打开，保守恢复至最后已保存的心跳，保留实际有效数据。
- 不申请 Internet 权限，不强制账号，不上传录音，禁用系统自动备份。主动分享的片段交给用户选择的外部应用。

## 目录

```text
app/src/main/java/com/miansheng/sleep/
  audio/       采集服务、YAMNet 推理、AAC 编码
  data/        Room 表、DAO、本地录音与清理
  domain/      统计、事件合并、环形缓冲
  ui/          原型对应 Compose 页面与组件、播放器状态
app/src/main/assets/        离线模型与类别表
app/src/test/               核心统计和事件边界测试
app/src/androidTest/        模型、编码、存储、监测和 UI 流程测试
app/schemas/                Room 数据库版本快照
docs/ui/sleep-ui/           用户提供的原型，保持原文件
docs/implementation.md      实现口径、边界和真机验收
licenses/                  字体、图标和模型代码相关许可
```

## 识别边界

当前是能够运行的首版，**不是经过卧室数据验证的成熟识别产品**。YAMNet 是通用预训练声音模型，阈值为初始工程配置，鼾声和疑似梦话准确度尚未量化。人声不代表确认梦话，无法保证区分同床者或电视声。完整四阶段睡眠分期不在此首版范围。

有效监测时长来自成功采集、分析的音频帧，不等同于睡眠时长。按原型，少于 3 小时的会话标记数据不足，不计算鼾声占比，不进入趋势平均。清理文件不影响已有声音事件统计。

音频模型、字体、Lucide 图标均随安装包提供，应用运行不依赖远程字体、CDN 或模型下载。来源见 `docs/assets.md`。

## 本轮验证

已完成 Debug 构建、6 项核心逻辑测试、7 项 Android 设备测试与 Lint 检查（无错误）。验证条件和边界见 [验证记录](docs/verification.md)，原生页面截图位于 [docs/screenshots](docs/screenshots)。截图中的示例报告来自测试夹具，正常安装不会注入示例数据。
