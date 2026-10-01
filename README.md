# SleepBird

一个 Swift + SwiftUI + UserNotifications 的本地 iPhone / iPad 催睡 MVP。没有服务器、账号、第三方 SDK、付费 API 或网络请求。最低 iOS / iPadOS **18.0**，使用 Swift 5 语言模式，Xcode **16 或更新版本**（需对应 iOS SDK）。

采用 [MIT 开源许可证](LICENSE)。

## 在 Xcode 运行

**没有 Mac、只有 Windows？** 查看 [Windows 云端构建与安装步骤](WINDOWS.md)。工程附带 GitHub Actions，源码推送到 main 后自动运行，也可手动触发：在云端 Mac 编译并生成未签名 IPA，再从 Windows 签名安装到自己的设备。

1. 将整个目录复制到 Mac，或解压 `SleepBird-MVP.zip`。
2. 双击 **SleepBird.xcodeproj**。工程已生成，不需要 XcodeGen、CocoaPods、Swift Package 下载，也无需运行 Python。
3. 选择 **SleepBird** scheme 和 iPhone / iPad 模拟器，按 **⌘R**。
4. 真机运行：在 SleepBird target 的 **Signing & Capabilities** 中选择你的 Team；将默认 Bundle Identifier `com.example.SleepBird` 改成自己的唯一标识。测试 target 的标识也可相应修改。个人 Apple ID 可用于本机开发测试，不需要为通知购买服务。
5. 首页点 **开启通知**，接受系统授权。拒绝后可从设置页进入系统设置重新开启。首次启动不会未经点击直接弹权限窗口。
6. 设置 → **Developer / Debug Mode** → **10 秒后发送测试通知**。把 App 切到后台或锁屏，等待通知；长按通知，点 **我睡了 💤**。首页也能直接打卡。
7. 选择 Product → Test 或 **⌘U** 运行 XCTest。测试使用独立 UserDefaults suite，不会清除正式打卡记录。

终端构建示例（Mac，有完整 Xcode）：

```sh
xcodebuild -project SleepBird.xcodeproj -scheme SleepBird \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```

运行测试时，将 destination 改成你本机已有的模拟器名称：

```sh
xcodebuild -project SleepBird.xcodeproj -scheme SleepBird \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO test
```

## 功能与约定

- 默认窗口 **20:30 至次日 04:00**。第一条在开始后 5–18 分钟；后续每次重新随机间隔。低频 65–90 分钟、正常 32–55 分钟、高频 20–35 分钟。普通提醒严格落在 `[start, end)`，不会在 20:30 前或 04:00 及之后触发。
- 每晚计划持久化，同一晚重新打开不乱跳。相邻两晚首条相对开始的偏移必定不同，其余时间与文案随机。生成时排除上一条文案，各等级 15 条，共 **75 条原创风格中文文案**。
- 正常强度按 20:30 / 21:30 / 22:30 / 23:30 / 01:00 分为五级。温和降低一级、疯狂提高一级，限定在 1–5。自定义窗口按相同的阶段比例缩放，避免白天窗口出现等级倒退。
- 首页大按钮和通知操作共用打卡入口。先保存完成状态，再取消该睡眠夜的普通及测试 pending 通知，同时清理该夜已送达的通知。重复打卡不会重复增加 streak。
- 每个睡眠夜最多一条记录，保存逻辑日期和实际点击时间。streak 按连续完成的睡眠夜计算，当前夜未结束时保留上一夜连续值；漏掉一晚后，在下一逻辑日清零。最长连续值保留。当前 streak 从已持久化记录与当前逻辑日推导，避免存储一个跨天后过期的计数。
- “连续早睡”是产品习惯名称；MVP 依据用户主动打卡计数，不检测真实入睡，也没有额外的 23:00 等截止线。当天白天提前打卡也会完成即将到来的夜晚，取消该晚提醒。
- 历史记录按睡眠夜展示，同时显示实际打卡时间。Debug 页可查看全部计划和系统实际 pending 队列。
- SwiftUI 自适应布局；首页内容在 iPad 上居中并限制最大宽度，列表与设置支持横竖屏；使用系统语义色，支持深色模式、Dynamic Type 和 VoiceOver 标签。

## 跨午夜：SleepNight / logicalDate

采用本地时区的公历日期和日历加减天数，不使用固定 86400 秒作为一天。

| 实际时间（默认窗口） | 所属任务 | 行为 |
|---|---|---|
| 2026-10-01 20:29 | 2026-10-01 | 未到窗口，不发普通提醒；可提前打卡 |
| 2026-10-01 20:30 | 2026-10-01 | 窗口开始 |
| 2026-10-02 00:30 | 2026-10-01 | 完成前一晚任务 |
| 2026-10-02 03:59 | 2026-10-01 | 仍是前一晚 |
| 2026-10-02 04:00 | 2026-10-02 | 新逻辑日，前一晚窗口结束 |
| 2026-10-02 12:00 | 2026-10-02 | 可提前完成今晚任务 |

结束时间早于开始时间时跨到次日；结束时间晚于开始时间时使用同一日窗口。设置拒绝相同时间和不足 30 分钟的窗口。保存新的结束时间后，睡眠夜归属立即采用新窗口规则；改变午夜边界可能改变当前任务日期，但历史记录不会重新归属。

通知携带 `nightID`。如果用户对已经过期的旧夜通知执行“我睡了”，App 清除旧通知并补排计划，不会把今天误记为完成，也不会补写旧夜。真正跨午夜且仍在窗口中的操作可直接完成前一夜。

检测到系统时区变化时，下一次刷新会重建未来计划以适应新的当地时间。已完成的日期键保持原日期；MVP 不提供跨时区历史重算。日历操作也覆盖夏令时日长变化。

## 本地通知的系统边界

**纯本地、有限队列不能保证在无限期不打开 App 时，每天重新生成随机通知。** iOS 不保证后台定时执行，App 被挂起时也无法在某个时刻执行任意 Swift 代码。本项目不伪装后台 Timer，不使用不能保证运行的后台任务作为关键依赖。

实际策略：

1. 每次刷新生成当前夜和随后七晚的持久化计划。
2. 最多安排 **60 条**系统 pending 请求（包括最多 4 条测试通知），按触发时间由近到远优先安排。计划数量不等于全部已经进入系统队列。高频时可能只能覆盖约 2–3 晚；正常频率通常覆盖约 4 晚；确切覆盖时间可在 Debug 页查看。
3. 启动、回到前台、点击通知操作、保存设置、手动重排时补队列。前台每 20 秒更新状态，跨睡眠夜边界时重排。关闭 App 后，已排入系统的通知由 iOS 发送。
4. 打卡只取消该夜，保留其他未来夜。次日无需清除一个全局布尔值；它有独立日期键，天然恢复未完成状态。
5. 预排队列耗尽且没有任何用户交互时，提醒会停止，直到下一次打开 App。此行为是这个无服务器 MVP 的明确限制。

专注模式、通知摘要、静音设置、系统权限和电量策略可能影响显示、声音和实际送达时间。计划时间是目标时间，不是送达保证。

快捷操作使用官方 `UNNotificationAction`，没有 `.foreground` 选项。系统允许时，在后台启动 App 处理操作；冷启动由 AppDelegate 提前安装通知 delegate。没有通知服务扩展、远程 push 或额外 entitlement。

**震动：** 本地通知公开 API 不提供独立的通知震动开关。通知声音可通过 `content.sound` 开关；系统通知震动由用户的系统设置决定。设置页的触感开关仅控制 App 内按钮反馈；部分设备（尤其 iPad）没有对应触感硬件。

**提醒计数：** 首页显示 App 已观察到的系统送达数量并标注“已观察”。iOS 不能提供所有已经被用户清除的通知的完整历史，因此这是可验证的下限；不是把“计划时间已过”冒充“实际已提醒”。测试通知不计数。重新生成计划会清理旧计划的观察计数。

## 调试流程

- 10 秒 / 30 秒测试可以在白天或窗口外发送，只在 Debug 页显式触发；首页不把测试当作下一条正式提醒。
- 本睡眠夜已打卡时拒绝新的测试通知，先点 **清除今晚完成状态**。
- 收到测试通知后，长按 → 我睡了；检查当晚 pending 都消失、未来夜保留、streak 只增加一次。
- 清除今晚完成状态保留历史最长连续记录，再排剩余未来提醒；不会重发过去时间点。
- 清除 streak 同时清除全部打卡历史，避免剩余记录重新推导出旧 streak；有本地确认提示。
- 重新生成今晚计划不会撤销已经完成的状态；想重测已完成夜，应先清除完成。
- 测试快捷操作跨过逻辑日边界时会按过期夜处理；请重新发送当前夜测试。
- 自动化测试包含日期边界、文案数量、计划范围/间隔、首条偏移差异、重复打卡、streak、持久化、数据损坏保护、夏令时与 action 注册。

## 文件结构

```text
SleepBird.xcodeproj/           可直接打开的工程与共享 scheme
SleepBird/
  SleepBirdApp.swift           启动、依赖注入、通知冷启动 delegate
  ContentView.swift            三个标签、前台刷新与逻辑日更新
  Models/
    SleepRecord.swift          记录、SleepNight、设置、计划
    ReminderLevel.swift        五级时间策略
  Services/
    NotificationManager.swift 权限、类别/操作、系统队列、取消与测试
    NotificationMessageProvider.swift
    SleepTracker.swift        UserDefaults/Codable、streak 与日期状态
    ReminderScheduler.swift   半随机时间生成
  Views/
    HomeView.swift
    SettingsView.swift
    HistoryView.swift
    DebugView.swift
  Assets.xcassets/             原创简单睡鸟图标
  PrivacyInfo.xcprivacy        UserDefaults required-reason 声明
SleepBirdTests/                XCTest
scripts/                      可选的工程再生成与静态检查
VALIDATION.md                 验证记录和真机验收清单
```

所有数据存于 App 自己的 UserDefaults；卸载会丢失。无法读取的数据会保留原始内容并停止写入，不会静默覆盖；可在 Debug 页显式重置。

## 当前验证状态

创建环境为 **Windows，没有本机 Xcode / iOS SDK / Swift 编译器**。本地执行了工程结构、Swift 语法解析、资源/XML/隐私声明与源码逻辑静态检查。2026-10-01 已通过 GitHub 云端 Mac 的 **Xcode 16.4 构建、模拟器 XCTest 命令与真机 IPA 打包**；首次运行的最后产物上传步骤失败，已修正隐藏目录上传配置。实际可下载产物以 [GitHub Actions](https://github.com/lernicks0/SleepBird/actions) 中成功运行结果为准。**尚未执行真实 iPhone/iPad 的安装、UI 和通知交互验收**。详细结果见 [VALIDATION.md](VALIDATION.md)。

官方参考：[本地通知的安排和处理](https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/SchedulingandHandlingLocalNotifications.html)、[通知操作处理](https://developer.apple.com/documentation/usernotifications/handling-notifications-and-notification-related-actions)、[通知 Action](https://developer.apple.com/documentation/usernotifications/unnotificationaction)、[UserDefaults required-reason API](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)。
