# 不用 Mac：从 Windows 安装到自己的 iPhone

这套流程用 GitHub 的云端 Mac 编译，Windows 负责安装。**不需要你的 Mac，也不需要 Xcode。** 先用免费签名测试；它每 7 天必须续签，不能把这份测试版当成唯一的吃药提醒。

## 第一次安装

1. 在 GitHub 新建**私人仓库**，把本文件所在的 `DoseLog` 文件夹内容上传到仓库根目录。上传后应能在 GitHub 仓库首页看到 `DoseLog.xcodeproj` 和 `.github/workflows/build-iphone.yml`。不要上传个人服药数据或 Apple 账号密码。GitHub 私人仓库的云端运行额度可能收费，以你的账号页面为准。
2. 上传完成后，GitHub 会自动开始编译；也可以在 **Actions → Build iPhone app without a Mac → Run workflow** 手动启动。等 `build-and-test` 变绿；它会在云端编译并运行工程已有的自动测试。进入这次运行，在 **Artifacts** 下载 `DoseLog-unsigned-iPhone`，解压后得到 `DoseLog-unsigned.ipa`。这是**未签名**安装包，不能直接在 iPhone 上点开。
3. 在 Windows 上从 [Sideloadly 官网](https://sideloadly.io/)安装它所要求的 Windows 组件和 Sideloadly。用数据线连接 iPhone，在手机上点“信任这台电脑”。把 `DoseLog-unsigned.ipa` 拖入 Sideloadly，选择 **Apple ID sideload**，填入你自己的 Apple 账号，开始安装。安装后按手机提示开启“开发者模式”，并在“设置 → 通用 → VPN 与设备管理”信任该开发者。

Sideloadly 是第三方安装工具，需要你在它的界面输入 Apple 账号。只从其官网下载；我们不会代你输入、保存或上传账号信息。如果不愿使用第三方工具，改用 Apple 付费开发者账号和云端签名流程。

## 免费签名的限制

Apple 的免费个人签名有效期为 **7 天**。Sideloadly 可以在 Windows 电脑和手机能够连接时尝试自动续签；续签不是本 App 自己完成的。若续签失败，App 可能无法打开，提醒也不能作为可靠保障。重新安装时使用**同一个 Apple 账号和相同的 App 标识**，覆盖安装，**不要先卸载**，否则本机数据会丢失。当前工程没有数据导出或恢复功能，正式长期使用前必须补上。

## 安装后核对

只用虚构测试药品操作：添加药品和计划 → 点击该药的服药按钮 → 关掉并重新打开 App，核对记录和库存；再把测试提醒设到几分钟后，核对手机锁屏上是否真正收到。云端编译通过只能证明代码在模拟器里通过测试，不能代替这一步真机检查。

## 如果要长期使用

较稳妥的非上架路线是 Apple 付费开发者账号 + 云端 Mac 构建 + 针对自己设备签名的安装包。它仍需维护签名和安装，本工程目前**只提供免费测试版的无 Mac 流程**，尚未配置你的开发者证书，也没有完成 iPhone 真机验证。不要把未验证的 App 作为唯一服药提醒。

参考：[GitHub 云端 Mac 环境](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md)、[Apple 免费签名有效期](https://developer.apple.com/help/account/basics/about-your-developer-account)、[Sideloadly 安装及续签说明](https://sideloadly.io/faq)。
