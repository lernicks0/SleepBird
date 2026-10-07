# Android 验证

## 1.1.0 使用追踪更新

验证日期：2026-10-06，发布整理：2026-10-07。

- Windows 实际执行 `assembleRelease lintRelease assembleDebug assembleDebugAndroidTest` 成功；Release Lint 0 errors、7 个非阻塞 warning。
- 分享 APK 676,177 bytes，包名 `io.github.lernicks0.sleepbird`，versionCode 2、versionName 1.1.0，minSdk 26、targetSdk 35。`apksigner` 验证 v2 签名通过，签名证书与 1.0.0 一致，可覆盖更新；私钥未上传。
- `aapt dump badging` 确认新增 PACKAGE_USAGE_STATS、FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE，仍无 INTERNET 权限。使用情况访问需要用户主动在系统设置中授权；默认开关为关闭。
- [GitHub Actions 实际成功运行](https://github.com/lernicks0/SleepBird/actions/runs/37484792395)：**24 项 JVM 单元测试，0 失败、0 忽略；9 项 Android 15 设备测试，0 失败、0 跳过**。报告已经下载并核对。
- 新增单元测试覆盖 30 秒准备时间、亮屏/锁屏、排除自身/桌面/系统界面/键盘、准备时间前关闭其他 App、准备时间后打开再关闭、通知打卡后仍留在其他 App、空/后台事件、截止时刻、时钟回退、未来事件和 streak 回退。
- 在临时 Android 15 模拟器中实际授予 Debug 包名使用情况访问、启动可见前台服务、打开 Android 设置产生真实前台活动事件，验证记录撤销、追踪停止、新通知出现、最长 streak 回退和重新登记催睡闹钟。测试只为缩短耗时将准备时间起点调整到 31 秒前；30 秒边界由单元测试验证。
- 设备测试还验证缺少使用权限时保留打卡、不开始追踪，旧版 JSON 升级保留记录且不自动开启追踪，过期监测结果不能撤销较新的打卡。原通知快捷操作和重启恢复测试仍通过。
- 已查看本次模拟器手机浅色和平板深色截图，布局正常。

APK SHA256：`e5b105d239c92389191aa4437a6a2c623172241f63d77e710d72045d8441caca`

签名证书 SHA256：`f8c28c3751a0a79280df2eb9517507578806825ac27894cd27285e42aa493fdf`

尚无实体 Android 手机验证。厂商省电、真实长期锁屏、分屏、特殊系统界面及不同版本的使用事件延迟仍需手机试用。实际外部 App 检测使用 Android 设置产生前台事件；未声称已对所有第三方 App 实测。通知操作的基础打卡测试真实执行 PendingIntent；授权后的追踪服务从可见 Activity 启动测试，尚未单独实测锁屏通知打卡启动服务。iOS 版本未增加此功能。

## 1.0.0 MVP 验证记录

验证日期：2026-10-02。

## 已实际执行

- Windows 本机：JDK 17、Gradle 8.10.2、AGP 8.7.3、Kotlin 2.0.21，`assembleRelease lintRelease` 成功。发布版 Lint：0 errors，7 个非阻塞 warning。
- 固定私钥签名的 Release APK：664,505 bytes，包名 `io.github.lernicks0.sleepbird`，versionCode 1，versionName 1.0.0，minSdk 26、targetSdk 35。
- `apksigner verify --verbose --print-certs`：验证通过，APK Signature Scheme v2，RSA 3072；Android 8.0 起可安装。
- `aapt dump badging`：启动 Activity 和包信息正确；仅通知、开机恢复、振动与用户授权的准时提醒权限，没有 INTERNET 权限。
- [最终 GitHub Actions 工作流](https://github.com/lernicks0/SleepBird/actions/runs/36980041840)：整体成功，单元测试、Debug Lint、APK 编译、Android 15 模拟器设备测试、截图生成和附件上传均成功。
- **15 项 JVM 单元测试，0 失败**：00:30 归前一晚、04:00 边界、白天/同日窗口、夏令时、五级阈值、强度、自定义窗口、150 组随机计划的窗口/间隔约束、相邻夜偏移差异、75 条文案、跨年/闰日 streak、漏打卡、重复及过期通知操作。
- **5 项 Android 15 设备集成测试，0 失败**：首页启动、持久化计划、真实通知发布和 PendingIntent 快捷打卡、打卡后清除已送达通知/测试任务并续排下一夜、重复打卡、旧通知不误完成今天、重启恢复、已完成夜不再发送迟到提醒。
- 已实际查看云端临时模拟器的手机浅色、手机深色和横屏平板深色截图：首页大按钮和状态信息可读，平板主内容居中且限制宽度，手机页面可滚动。截图不来自用户个人设备。
- [公开下载地址](https://github.com/lernicks0/SleepBird/releases/download/android-v1.0.0/SleepBird-Android-1.0.0.apk)已重新下载，SHA256 与本机签名 APK 一致。

SHA256：`bdc11a661f9d000b96b45dfce91b66ce94ae3d38e5d20c5aebaf41a0ade1485c`

签名证书 SHA256：`f8c28c3751a0a79280df2eb9517507578806825ac27894cd27285e42aa493fdf`

## 尚需实体手机验证

没有连接实体 Android 手机。各厂商省电策略、锁屏/勿扰效果、真实长时间 Doze、设备重启广播与 10/30 秒测试通知的实际计时仍需实体手机试用。
设备测试模拟重启接收器恢复逻辑，不代表已经把实体手机重启过。通知操作测试在模拟器中真实执行 PendingIntent，测试提醒的送达由接收器直接触发，不伪称完成了实体手机十秒计时验证。

Windows 路径/缓存兼容性曾阻止本机 JVM 测试进程启动；没有将该失败说成通过。上面的 JVM 与设备测试通过结论来自实际成功的 Linux 云端工作流。本机完成了正式 Release 编译、Lint 和签名验证。

## 朋友安装后建议检查

1. 开启通知，按需授权“闹钟和提醒”。
2. 调试页发 10 秒测试通知，回桌面确认收到。
3. 在通知上点“我睡了 💤”，打开首页确认已完成且 streak +1。
4. 重复点击确认没有重复加一天；已完成状态下确认不再收到该夜的提醒。
5. 次日打开确认新夜恢复提醒；可根据系统省电设置允许后台运行。
