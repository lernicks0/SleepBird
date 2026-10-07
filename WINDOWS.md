# 没有 Mac：从 Windows 构建并安装 SleepBird

你不需要购买 Mac。这里使用 **GitHub 的 macOS 构建机**编译 Swift，再用 **Windows 上的 Sideloadly**签名安装到你自己的 iPhone / iPad。App 安装后的催睡逻辑仍然完全本地运行。

需要：Windows 电脑、GitHub 账号、Apple ID、运行 iOS/iPadOS 18 或更新版本的设备和首次连接用的 USB 数据线。

这是一条个人测试路径。免费 Apple ID 的安装签名有效期为 **7 天**，需要重新签名；Sideloadly 提供自动刷新功能，但电脑和设备需要满足连接条件。它不是永久安装或 App Store / TestFlight 发布方案。[Sideloadly 官方说明](https://sideloadly.io/)

## 1. 将项目放进 GitHub 仓库

1. 解压更新后的 `SleepBird-MVP.zip`。
2. 在 GitHub 网站新建一个仓库，名字例如 `SleepBird`。
3. 上传解压目录**内部的项目内容**到仓库根目录。根目录应直接有 `SleepBird.xcodeproj`、`SleepBird`、`SleepBirdTests`、`scripts` 和 **`.github/workflows/build-ios.yml`**，不能再隔着一个 `SleepBird-MVP` 父目录。
4. 可以在网页使用 Add file → Upload files，拖入文件与文件夹；也可用 GitHub Desktop 上传。浏览器没有上传 `.github` 时，用 Add file → Create new file，输入 `.github/workflows/build-ios.yml`，粘贴本机同名文件内容后保存。
5. 不要上传 `.validation`、`.git`、压缩包、`build` 或本机缓存。交付的压缩包已经排除了这些内容。

仓库可自行选择公开或私有。**公开仓库会公开你的代码**，标准 GitHub 托管 runner 的运行免费；私有仓库使用账号免费额度，超额可能收费。向 main 推送 App 源码、测试或构建配置会自动构建，单独修改文档不会触发；也可手动运行，产物保留 7 天。[GitHub runner 和费用说明](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)

## 2. 在网页启动云端编译

1. 首次上传源码后会自动构建。打开仓库 → **Actions** → **Build SleepBird for Windows users** 查看进度。
2. 点 **Run workflow**。`run_tests` 默认勾选，会在可用的 iPhone 模拟器运行 XCTest，再编译真机版本。
3. 等待运行完成。绿色只表示该次 CI 成功；取消测试选项时不会执行 XCTest。
4. 打开那次运行页面，在 **Artifacts** 下载 `SleepBird-iOS-数字`。
5. 解压产物，找到 **`SleepBird-unsigned.ipa`**。

这个 IPA 还没有 Apple 签名，不能直接点击安装。整个云端流程不需要 Apple ID、证书或密码，也不需要 GitHub Secrets。

构建失败时，产物仍会上传 `build.log` / `tests.log` / `FAILED.txt` 等诊断文件。如果测试失败，流程不会继续打包 IPA；不要把这个状态当作 App 已通过测试。你可以提供这些日志继续修复。

## 3. 在 Windows 签名安装

1. 从 [Sideloadly 官网](https://sideloadly.io/) 下载 Windows 版本。
2. 按官网要求安装它支持的 Apple iTunes / iCloud Windows 组件；官网目前要求网页安装版，Microsoft Store 版的兼容要求以官网为准。
3. USB 连接并解锁 iPhone / iPad，点设备上的“信任此电脑”。
4. 打开 Sideloadly，选中设备，把 `SleepBird-unsigned.ipa` 拖入。
5. 在 Sideloadly 中填写你的 Apple ID，点 Start，根据提示完成登录与双重认证。**不用把 Apple ID 或密码放到 GitHub，也不用发到此聊天。**
6. 按设备提示信任开发者签名。常见位置：设置 → 通用 → VPN 与设备管理。若系统要求开发者模式，在设置 → 隐私与安全 → 开发者模式中开启，并按设备提示重启确认。
7. 打开 SleepBird，点“开启通知”，到设置 → Developer / Debug Mode 发 10 秒测试通知。

登录、设备驱动或签名故障请先看 Sideloadly 安装日志；云端编译成功不等于这部分已经在你的电脑上验证。

## 4. 保持能用

- 免费签名 7 天到期后需要刷新。可以设置 Sideloadly 自动刷新，或在到期前重新连接设备安装同一个 App。
- 保持同一 Apple ID 和 App 标识；避免卸载后重装，以免丢失本机记录。App 更新与续签仍应检查记录是否保留。
- 第一次建议先用 USB 跑通，再按 Sideloadly 的官方说明设置 Wi-Fi 安装/自动刷新。
- 不要在安装工具里强行降低最低系统版本，代码要求 iOS/iPadOS 18。
- 不需要越狱，也不需要购买 Apple Developer 会员来做这种个人测试。

## 当前状态

2026-10-07：iOS/iPadOS 1.1.0 云端构建成功，20 个 XCTest 全部通过。[下载 1.1.0 未签名 IPA](https://github.com/lernicks0/SleepBird/releases/download/ios-v1.1.0/SleepBird-iOS-1.1.0-unsigned.ipa)，通过 Sideloadly 以原 Apple ID、原 App 标识签名覆盖安装。不要先卸载，以保留记录。[本次构建与测试](https://github.com/lernicks0/SleepBird/actions/runs/37577906813)。

本版同步更有冲劲的 75 条催睡文案，保留已有提醒时间、打卡记录和 streak。**不包含 Android 版的入睡后跨 App 使用追踪**。该功能的 iOS 官方路径需要 Family Controls + DeviceActivity 和支持该能力的开发者签名，免费 Personal Team 不支持。[苹果能力表](https://developer.apple.com/help/account/reference/supported-capabilities-ios/)。本次未在实体设备验证更新安装或通知展示。
