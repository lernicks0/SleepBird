# SleepBird · Android

原生 Kotlin 的本地催睡 App，支持 **Android 8.0 / API 26 及以上**的手机和平板。
没有服务器、登录、广告、付费 API、分析 SDK，也没有申请联网权限。与 iOS 版分别保存本机数据。

## 给朋友安装

1. 下载 GitHub Releases 中的 `SleepBird-Android-1.0.0.apk`。
2. 用浏览器下载，或通过网盘、QQ、微信发送文件后在 Android 手机上打开。
3. 按手机提示，允许当前浏览器或文件管理器安装此来源的应用，完成安装。
4. 打开 SleepBird，点 **开启通知**。Android 13 及以上需要用户授权。
5. 如需接近计划时刻触发，点 **开启准时提醒**，在系统的“闹钟和提醒”页面允许 SleepBird。不开启也可运行，但使用系统允许的非精确提醒，可能延迟。
6. 设置 → Developer / Debug Mode → **10 秒后发送测试通知**，回到桌面等待；展开通知，点 **我睡了 💤**，检查首页状态和连续记录。

APK 自带 Android 签名，没有 iOS 免费签名的七天期限。不需要连接电脑、Apple ID 或开发者模式。
部分聊天工具限制发送 APK；可改发 Releases 下载链接。

## 功能

- 默认 20:30 至次日 04:00，首条偏移 5–18 分钟；后续随机间隔：低 65–90、正常 32–55、高 20–35 分钟。
- 每晚计划持久化；相邻夜晚首条偏移不同。五级各 15 条中文文案，避免连续重复。
- 完成当天任务后取消该夜剩余普通及测试提醒；通知快捷操作无需打开首页。
- 跨午夜的 `SleepNight`：00:30 算前一晚，04:00 起算新逻辑日。旧夜的通知操作不会误完成新夜。
- streak 按日期连续性计算；当前夜尚未完成时保留上一夜 streak，漏掉一夜后归零；重复打卡不会多加一天。
- 首页、历史、设置、调试；深色模式、手机/平板自适应；提醒强度、频率、声音和振动可设置。
- 私有 SharedPreferences 中保存一个 JSON 快照；保存失败或损坏会提示，避免静默覆盖原始记录。

## Android 提醒边界

使用 `AlarmManager`，默认优先用户授权的 `setExactAndAllowWhileIdle`，否则使用 `setAndAllowWhileIdle`。
普通提醒只登记下一条，广播接收器处理后生成/续排滚动的八晚计划。打卡、前台打开、重启、应用更新和时间/时区变化时重新登记。
不依赖常驻服务，也不伪装后台 Timer。

系统可能因为 Doze、勿扰或厂商省电策略延迟/阻止通知。接收时再次检查当前窗口和完成状态，迟到至 04:00 及之后的旧夜普通通知会丢弃；实际送达的普通提醒也保持最低间隔，不补发积压通知。
测试通知可在白天发，最多有两个不同测试槽位（10/30 秒），不计入“今晚已经提醒”的次数；完成任务后同样取消。

应用被系统“强行停止”后，需用户再次打开才能恢复提醒。重启后需正常解锁；不请求忽略电池优化，也不绕过系统限制。
通知渠道的声音/振动由系统最终控制。应用按四种声音/振动组合创建渠道；修改设置会选择相应渠道，用户在系统中对渠道的设置仍优先。
Android 没有公开接口枚举 AlarmManager 的所有 pending 闹钟；调试页准确标示为 **App 保存的登记信息**，不会声称读取了系统完整队列。

官方参考：[定时提醒](https://developer.android.com/develop/background-work/services/alarms)、[通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)。

## 在 Android Studio / Windows 构建

打开本目录 `android/`，使用 JDK 17、Android SDK 35 / Build Tools 35.0.0。Gradle Wrapper 8.10.2 已附带，首次构建下载依赖。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。Debug 使用 `.debug` 包名，可与分享版共存；本地 Debug 签名仅适合开发测试。

在已连接的测试设备或模拟器上运行集成测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

测试会清除 **Debug 包名**的数据，不会清除分享版。设备测试应使用 Android 13 或以上；CI 使用 Android 15 模拟器。
测试覆盖真实通知发布、PendingIntent 快捷打卡、重复操作、取消通知/续排、重启恢复、保存计划和首页启动。

仓库的 `Build and test Android APK` workflow 自动运行单元测试、Lint、APK 编译与 Android 15 模拟器测试，提供 APK 和报告供下载。
CI 的 Debug APK 用于验证；长期给朋友使用请下载 Releases 中固定签名的分享版。

## 分享版固定签名

`release` 构建读取三个环境变量：

- `SLEEPBIRD_KEYSTORE`：本机签名密钥文件的绝对路径。
- `SLEEPBIRD_STORE_PASSWORD`：密钥库密码。
- `SLEEPBIRD_KEY_PASSWORD`：别名 `sleepbird` 的密码。

设置后执行 `gradlew assembleRelease`；未设置时产物为未签名 Release，不能直接安装。
签名私钥/密码不提交到 Git，不嵌入 APK，不随源码或安装包分享。已忽略 `*.keystore`、`*.jks`、`signing.properties`。
当前分享版的固定密钥和本地凭据保存在开发电脑项目的 `.validation/android-signing/`（已忽略）。请自行安全备份该目录；以后覆盖更新必须使用同一密钥并提高 `versionCode`，丢失密钥无法为已安装版本签名更新。

项目使用 MIT 许可证，见仓库根目录 LICENSE。

真实构建、测试结果与尚需实体手机检查的项目见 [VALIDATION.md](VALIDATION.md)。
