# 验证记录

## v0.2.0 声音强度与校准

验证日期：2026-10-18。使用 JDK 21、Android SDK 35，以及独立 Android 16 / API 36 ARM64 模拟器。

- Release APK 构建通过；14 项 Release 单元测试、13 项 Android 设备测试全部通过，无跳过、无失败。
- Debug / Release Lint 均为 0 错误、26 项警告（依赖版本与代码风格等建议）。
- 包名 `com.miansheng.sleep`，`versionCode=3`，`versionName=0.2.0`；未开启 `debuggable`。
- `apksigner verify` 通过，RSA 3072 位、APK v2 签名，证书与 v0.1.1 相同：`50e2a6a0e4684fe437a6b581c43b19e5c4eaf486f5e2a97cf4c6122348d954ae`。
- APK 为 33,651,579 字节；SHA-256：`a76d1810d06736bb6584ad6dda1ba426459fe22e6b3d7120955cf4f13e9d32c7`。
- 正式签名包在专用模拟器上 `adb install -r` 安装成功，冷启动返回 `Status: ok`。

新增验证覆盖：

- 合成正弦波的 dBFS、振幅翻倍约 +6.02 dB、A 计权响应与跨窗口滤波连续性。
- 能量平均、时长加权 P95、实时直方图与精确统计的一致性，以及全零、过载、缺测和校准范围边界。
- 三组校准的声级跨度、稳定性和残差检查；校准冻结、混合配置回退为 dBFS。
- 从真实 v1 表结构迁移到 Room v2，保留历史备注；删除会话级联清理声级，停用校准保留历史配置。
- 校准向导填写、保存、停用，报告指标与刻度图，以及重新打开麦克风后的配置签名一致性。
- 查看另一晚报告时，小播放器继续加载所属夜晚声级；关闭播放器后释放额外夜晚的数据订阅。
- 原有离线模型、AAC 编码、回放、历史、锁屏监测、暂停、恢复与心跳裁剪。

声级报告和校准结果截图使用测试夹具；实际麦克风流程仅验证采集与状态管理。未使用真实 S30 与参考声级计进行声级精度测量，未完成整夜续航验证。安装后需要按 [声音强度与校准说明](sound-levels.md) 完成参考仪器比对，才会在匹配配置下显示估计 dBA；这不构成仪器精度认证。

复现命令：

```sh
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease :app:lintDebug :app:connectedDebugAndroidTest
```

Release 构建需要本机签名配置；设备测试需要专用设备或模拟器。

## v0.1.1 Release 验证

验证日期：2026-10-17。以下为上一发布版本的记录。

- `:app:assembleRelease :app:testReleaseUnitTest :app:lintRelease` 通过；6 项单元测试全部通过，Release Lint 无错误。
- 正式包名 `com.miansheng.sleep`，`versionCode=2`，`versionName=0.1.1`；包含新的自适应桌面图标。
- APK 为 33,549,891 字节（约 32 MiB），启用 R8 压缩、混淆及资源裁剪。
- `apksigner verify` 通过，使用独立的 RSA 3072 位 Release 证书，APK v2 签名；证书 SHA-256：`50e2a6a0e4684fe437a6b581c43b19e5c4eaf486f5e2a97cf4c6122348d954ae`。
- APK 清单未开启 `debuggable`；所有 8 个原生库均不含 `.debug*`、`.zdebug*`、`.symtab` 调试段；未打入 Compose 预览/测试工具、签名私钥或 R8 mapping。
- APK SHA-256：`6dd88976224893f64456e7dee43ec74a75821dfa0ade55dfa92612c66e1a91c3`。
- 在独立 API 36 ARM64 模拟器上安装成功，正式 Activity 启动返回 `Status: ok`。

## 环境与结果

- JDK 21、Gradle 8.11.1、Android SDK 35。
- Android Emulator 37.2.12；设备测试：独立 Android 16 / API 36 ARM64 模拟器，1080 × 2400、420 dpi。
- Debug APK 可构建；6 项 JVM 核心逻辑测试、7 项 Android 设备测试通过。
- Android Lint 无错误；警告主要涉及依赖可更新、Compose 参数顺序及 KTX 建议，完整报告见本地 `app/build/reports/lint-results-debug.html`。

复现命令：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

设备测试需要先启动模拟器或连接测试设备。锁屏测试会让测试设备短暂息屏并唤醒，应使用专用测试设备。

## 覆盖内容

| 层次 | 验证内容 |
| --- | --- |
| 核心统计 | 有效时长分母、少于 3 小时规则、修正排除、删除音频保留统计 |
| 事件处理 | 合并短间隙但排除静默时长、类别切换、不重复累计、过滤单窗口、最长事件及环形缓冲覆盖 |
| 真正运行模型 | 随包 TFLite 模型可推理，静音不生成声音事件 |
| 音频文件 | AAC 编码结果可读取、时长符合输入、波形来自实际 PCM |
| 存储与恢复 | 到期清理保护收藏、统计保留、恢复至最后心跳、采集更新不覆盖生活标签 |
| 实际采集流程 | 前置检查、AudioRecord 前台服务、锁屏后采集、暂停不累计、恢复与结束保存 |
| 页面交互 | 报告数据、回放与拖动、收藏、误判修正、可点击撤销、标签持久化、历史与趋势 |

## 页面截图

`screenshots/` 中为设备测试对原生 Android 界面的截图。首页、报告、录音、历史与趋势使用**仅在测试 APK 内生成的合成夹具**，以便复现原型的版式和验证统计。回放夹具是短时合成音调，并非模型识别的实际鼾声或梦话。正常安装的应用没有这些示例记录。

开始前检查、监测及短会话报告走实际麦克风采集流程。模拟器输入不能作为真实卧室识别准确度证据。

## 尚未验证

真实手机整夜 8–10 小时的续航、温升、厂商省电限制、来电抢占、设备麦克风差异，以及真实卧室鼾声/人声精确率和召回率。首版不提供 REM / 浅睡 / 深睡分期。
