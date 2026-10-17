# 首版验证记录

验证日期：2026-10-17。

## v0.1.1 Release 验证

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
