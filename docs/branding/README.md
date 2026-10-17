# 眠声 App Icon

原创图标以「月牙环抱声波」表达夜间声音监测，沿用应用的深夜蓝与薄荷绿，以暖金色星光点缀。使用实心轮廓，保证桌面小尺寸下的辨识度。

- `app-icon.svg`：可编辑矢量源文件，108 × 108 坐标，与 Android 自适应图标图层对应。
- `app-icon.png`：1024 × 1024、无预置圆角的完整图标导出；对应矢量源文件中央的 `viewBox="18 18 72 72"`。
- `app-icon-preview.png`：圆角、圆形与主题色的设计预览，主题色仅为示例，实际颜色由系统决定。

Android 图层位于 `app/src/main/res/drawable/ic_launcher_*.xml`。`mipmap-anydpi-v26` 定义自适应图标及 Android 13+ 使用的单色主题图层，`mipmap-anydpi` 为完整图标回退。修改图形时应同步 SVG、VectorDrawable 与 PNG 导出。

应用清单的 `android:icon` 和 `android:roundIcon` 均已接入新资源。原有 `ic_moon` 保留用于应用内的 Lucide 界面图标。

验证：`:app:assembleDebug` 与 `:app:lintDebug` 通过，APK 清单已确认引用新的 launcher 资源。预览为设计渲染，未进行真机桌面截图验证。
