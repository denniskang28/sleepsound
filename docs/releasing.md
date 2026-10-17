# Release 构建与发布

Release 使用正式包名 `com.miansheng.sleep`，关闭 Java/JNI 调试，启用 R8 代码压缩、混淆与资源裁剪。Compose 调试工具只在 Debug 依赖中。Release 与 `com.miansheng.sleep.debug` 是不同应用，数据不会自动迁移。

## 本机签名

默认读取 `~/.android/miansheng/signing.properties`，也可使用环境变量 `MIANSHENG_RELEASE_SIGNING_PROPERTIES` 或 Gradle 参数 `-PreleaseSigningProperties=/absolute/path/signing.properties` 指定文件。格式如下，值需替换为实际配置：

```properties
storeFile=/absolute/path/release.jks
storePassword=REPLACE_WITH_LOCAL_SECRET
keyAlias=miansheng
keyPassword=REPLACE_WITH_LOCAL_SECRET
```

私钥和密码文件仅保存在本机，不提交 Git、不上传到 Release。请安全备份这两份文件，后续升级必须使用相同密钥。缺少签名配置时，Release 构建会失败，避免误发未签名安装包。构建日志和 R8 mapping 不作为下载附件发布。

## 构建和校验

```sh
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
# 将路径替换为本机 SDK build-tools 路径
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk
```

检查包名、版本和 launcher 图标，确认不存在 `application-debuggable`。发布前还应在专用设备上验证启动、麦克风监测、报告和录音回放。缩混后的 APK 不包含测试 APK，测试记录不应进入发布产物。

## 远端发布

先推送代码提交，再对同一提交创建版本 tag 和 GitHub Release，上传 `sleepsound-VERSION-release.apk` 及其 `SHA256SUMS.txt`，并同步 README 的下载链接。不要覆盖不同签名的旧安装包。
