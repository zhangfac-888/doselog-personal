# 当前验收状态

日期：2026-10-21（Asia/Shanghai）

| 状态 | 项目 | 证据 |
| --- | --- | --- |
| PASS | Xcode 工程结构静态检查 | Node 解析 `project.pbxproj` 的对象、引用、Debug/Release 配置、App/测试目标的源码归属；scheme XML 可读 |
| PASS | 文件完整性 | `COMPLETE_SOURCE.md` 直接从工程的源码与配置生成，没有手工省略 |
| PASS | 依赖范围检查 | 源码包含系统本地通知框架；没有 URLSession、CloudKit、HealthKit 或第三方包引用 |
| SOURCE_ONLY | 核心流程审查 | `RecordStore` 原子写入后才发布；计划与服药独立建模；库存由流水、有效记录与最后一次盘点计算；旧版时间记录保留但不猜药品 |
| SOURCE_ONLY | 提醒边界审查 | 到时与未确认提醒预先交给系统；记录变化重排；锁屏文案不展示药品；页面显示已安排的覆盖范围 |
| PASS | Xcode 编译与 13 个 XCTest 方法 | [GitHub Actions 首次运行](https://github.com/zhangfac-888/doselog-personal/actions/runs/39548049770)：Xcode 26.6，模拟器执行 13 个测试、0 失败；iPhoneOS 26.5 SDK 的 arm64 设备构建成功 |
| PASS | 无签名 iPhone 安装包 | 同一运行产出 `DoseLog-unsigned.ipa`；本地下载后核对 `Payload/DoseLog.app/Info.plist` 和可执行文件存在；SHA-256 为 `39ED898C2F63FBB03A3BF8430B0D2B6224BAACD414C3E42C944B7FEA6497DD71` |
| PASS | Windows 无 Mac 云端流程 | [私人仓库](https://github.com/zhangfac-888/doselog-personal)已上传工程，云端自动测试、设备构建、打包与下载完成 |
| NOT_RUN | 实体 iPhone 流程 | 没有连接的 iPhone、Apple 签名及设备授权，无法声称已安装或完成实际通知投递 |
| NOT_RUN | Windows 安装 | 安装说明已加入 `NO_MAC.md`；没有该用户的 Apple 账号与实体 iPhone，未通过 Sideloadly 签名或安装 |

当前 Swift 源码已在云端编译，已有自动测试在 iPhone 模拟器中通过；**真机安装、数据保存和提醒投递仍未验证**。本地 `node .tooling/verify-delivery.cjs` 只检查工程结构，云端结果才是本次 Swift 编译和自动测试的证据。

在真机核对：具体药品与剂量一键打卡、退出重启后的记录、补记和作废后的库存、跨天/时区、七天校验、通知授权与实际投递，以及免费签名续签后的数据保留。过程中须使用测试药品和隔离数据，不要以真实医嘱或真实服药操作作为首次验收数据。
