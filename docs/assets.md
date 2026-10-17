# 随包资源

运行时资源完全本地化。应用不需要互联网权限。下载来源和 SHA-256 保存在 `assets.lock.json`，用于确认已包含的文件。

| 资源 | 来源 | 说明 |
| --- | --- | --- |
| YAMNet TFLite 分类模型 | Google / TensorFlow 官方模型存储，地址见 lock 文件 | 16 kHz 单声道，15,600 个浮点输入，521 类。初始阈值：Snoring ≥ 0.35；Speech/Whispering 等人声 ≥ 0.55 且 Music/Television < 0.45。至少两个窗口形成事件。阈值尚未经过卧室数据验证。 |
| YAMNet 类别表 | tensorflow/models 的 research/audioset/yamnet | 与预训练模型一起使用。不是睡眠分期标签。 |
| Archivo 可变字体 | google/fonts / ofl/archivo | SIL Open Font License，许可见 `licenses/Archivo-OFL.txt`。 |
| Noto Sans SC 可变字体 | google/fonts / ofl/notosanssc | SIL Open Font License，许可见 `licenses/NotoSansSC-OFL.txt`。 |
| Lucide 0.469.0 图标 | lucide-icons/lucide 官方仓库对应 tag | 从 SVG 几何转换为 Android VectorDrawable，ISC 许可见 `licenses/Lucide-ISC.txt`。 |
| 眠声桌面图标 | 本项目原创，矢量源文件见 `branding/app-icon.svg` | 深夜蓝底、薄荷绿月牙与声波、暖金色星光。包含自适应、圆形与 Android 13+ 单色主题图标；预览与 1024 px 导出见 `branding/`。 |

TensorFlow 模型仓库的 Apache-2.0 许可保存于 `licenses/TensorFlow-Apache-2.0.txt`。发布前应结合模型原发布页与所用第三方依赖补全分发所需 notices。

音频训练数据未包含在项目中。UI 测试只生成短时合成音调作为回放与编码夹具，不使用真实个人夜间录音，也不构成模型准确度测试。
