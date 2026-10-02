# 验证记录

创建日期：2026-09-30（Asia/Shanghai）。验证环境：Windows。本机没有 Xcode、Apple iOS SDK、Swift 编译器或模拟器。

2026-10-02 核验结果：GitHub 云端构建完整成功，**19 个 XCTest 全部通过，0 failures**，Release 真机编译、IPA 打包与 artifact 上传均成功。构建使用 macOS 15.7.9 / Xcode 16.4，测试日志提供每个测试用例的通过记录。

源码仓库：`lernicks0/SleepBird`。成功运行：[Actions #2](https://github.com/lernicks0/SleepBird/actions/runs/36868051656)。对应源码提交：`1e69c2b1ec21ca0f3eb304d08ef7f109d6883278`。下载产物：[SleepBird-iOS-2](https://github.com/lernicks0/SleepBird/actions/runs/36868051656/artifacts/11166675286)，其中包含 `SleepBird-unsigned.ipa`、`BUILD-INFO.txt`、`tests.log`、`build.log` 与 XCTest result bundle。首次运行的隐藏目录上传问题已修复。**真机安装、iPad 布局与真实通知交互仍需设备验证。**

GitHub artifact SHA256：`c23e89f5bf86052850e2465a081dcdbc32e44d8b5178d4cbbd1a9ccdb05f002d`。这对应完整下载 ZIP，而非其中的 IPA。

2026-10-02 已下载 artifact 到本机并验证 SHA256；ZIP 完整性检查通过。提取的 IPA SHA256 为 `3a5990d1665910affc82d508a1c8bbfc08dd9ea5115293c7ab9ce86d49e34335`。包中 `Payload/SleepBird.app` 含有效真机 Mach-O 可执行文件；Info.plist 的 Bundle ID 为 `com.example.SleepBird`，MinimumOSVersion 为 `18.0`，UIDeviceFamily 为 `[1, 2]`；包未签名，需安装工具签名后使用。编译日志明确显示 `BUILD SUCCEEDED`，测试日志明确显示 `Executed 19 tests, with 0 failures`。

## 已执行

运行 `scripts/check_project.py`，**48/48 项静态检查通过**：

- 12 个 App Swift 文件和 1 个 XCTest Swift 文件均通过 tree-sitter Swift 语法解析，未出现 ERROR 或缺失节点。
- Xcode project 对象标识唯一、全部引用存在；13 个 Swift 文件均列入相应 target 的 Sources。
- 部署版本为 iOS/iPadOS 18.0，device family 含 iPhone 和 iPad。
- 共享 scheme、workspace XML、asset JSON、PrivacyInfo plist 可解析。
- AppIcon 为 1024 × 1024 不透明 RGB PNG。
- 五级文案各有 15 条不重复内容，共 75 条。
- 源码检查了官方通知权限请求、后台 action 声明、按夜取消、旧夜操作保护、60 条队列预算和异步重入同步循环。

这些检查只验证结构、语法和特定源码约束。**它们不构成 Swift 类型检查、Apple SDK 编译、链接或运行成功的证明。**

## 已做源码审查

| 需求 | 实现检查 | 尚需验证 |
|---|---|---|
| 普通提醒时间 | 非重复 calendar trigger；随机首条；随机最小间隔；`start <= fireDate < end` | 真机实际展示、专注模式影响 |
| 权限 | 用户点击后请求 alert/sound/badge；拒绝后打开系统设置 | 第一次授权、拒绝、重新开启 |
| 跨午夜 | SleepNight 在默认 04:00 切日；00:30 归前夜；日历加减天；相关 XCTest 已通过 | 真机运行状态切日 |
| 通知取消 | 按 nightID 过滤 pending，包括当夜测试；先保存，再取消；未来夜保留 | 真机 pending 清空与并发操作 |
| 快捷操作 | 启动时安装 delegate；后台 action；调用 completionHandler；旧夜不误打卡 | App 未运行时的冷启动操作 |
| Streak | 按唯一夜日期连续计算；重复打卡无效；漏夜清零；最长值保留；相关 XCTest 已通过 | 真实用户连续使用 |
| 持久化 | Codable/UserDefaults；数据损坏保护；时区变化重排未来计划 | 杀 App、重启、修改时区 |
| iPad | device family 1,2；首页限宽；系统 List/Form；四方向 | 横竖屏、分屏、深色与大字体 |

## XCTest：19/19 已通过

19 个用例包含：午夜归属、精确结束点、同日窗口、窗口有效性、自定义白天窗口等级递增、默认等级边界、强度限制、300 轮随机计划约束、75 条文案、相邻夜首条偏移差异、计划刷新稳定性、幂等打卡、连续与漏夜、跨年与闰日、设置修改保留完成、UserDefaults 恢复、损坏数据保护、夏令时、通知 action 注册。

这些用例已在云端 iPhone 模拟器运行通过；后续修改可用 CI 或 Mac/Xcode **⌘U** 重跑。随机计划用例检查不变量，不依赖某个随机样本“碰巧不同”；相邻夜偏移差异由生成算法强制保证。

## Mac / 真机验收步骤

1. 用 `README.md` 的命令构建 iOS Simulator target；Xcode ⌘U 跑全部 XCTest。
2. iPhone 首次授权允许通知；再次测试拒绝授权和从设置重新开启。未授权时首页应显示明确的授权状态。
3. 发 10 秒 / 30 秒测试，分别验证前台 banner、后台通知和锁屏通知。
4. App 退出后长按通知 → 我睡了：应记录一次、当夜 pending 清空、未来夜仍保留。连续点击不能使 streak 重复增加。
5. 首页我睡了也做相同检查；清除今晚状态后仅重排未来时间，不补发过去点。
6. 用模拟器系统时间或等待边界验证 00:30、03:59、04:00 的归属。通知操作来自旧夜时不得完成新夜。
7. 修改开始/结束、强度、频率、声音后点保存；旧计划请求消失，新请求时间落在新窗口，已完成夜不入队。
8. 在 iPad 横屏、竖屏和 Split View 检查首页、设置、历史和长 Debug 列表；切深色与最大 Dynamic Type，确认能滚动并操作按钮。
9. 真机检查声音关闭、系统静音/震动设置与 App 内触感开关。模拟器不能证明触感效果。
10. 多次开关 App，确保计划稳定、记录保存。查看 Debug 队列最后时间；长期不互动时有限预排耗尽后停止是预期限制。

## 可重跑的静态检查

Python 3.12；图标尺寸检查需要 Pillow。语法解析可选安装：

```sh
python -m pip install Pillow
python -m pip install --target .validation tree-sitter tree-sitter-swift
python scripts/check_project.py
```

没有语法解析库时会明确显示 SKIP；其余结构检查仍运行。这些 Python 依赖只用于可选开发检查，App/Xcode 工程不依赖它们。
