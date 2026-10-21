# 当前验收状态

日期：2026-10-20（Asia/Shanghai）

| 状态 | 项目 | 证据 |
| --- | --- | --- |
| PASS | Xcode 工程结构静态检查 | Node 解析 `project.pbxproj` 的对象、引用、Debug/Release 配置、App/测试目标的源码归属；scheme XML 可读 |
| PASS | 文件完整性 | `COMPLETE_SOURCE.md` 直接从工程的源码与配置生成，没有手工省略 |
| PASS | 依赖范围检查 | 源码包含系统本地通知框架；没有 URLSession、CloudKit、HealthKit 或第三方包引用 |
| SOURCE_ONLY | 核心流程审查 | `RecordStore` 原子写入后才发布；计划与服药独立建模；库存由流水、有效记录与最后一次盘点计算；旧版时间记录保留但不猜药品 |
| SOURCE_ONLY | 提醒边界审查 | 到时与未确认提醒预先交给系统；记录变化重排；锁屏文案不展示药品；页面显示已安排的覆盖范围 |
| NOT_RUN | Xcode 编译与 13 个 XCTest 方法 | 当前是 Windows 环境，没有 macOS Xcode/iOS SDK。测试文件已写入共享 scheme，尚未执行 |
| NOT_RUN | iPhone 模拟器和真机流程 | 当前无可操作的 Xcode/模拟器/签名设备，无法声称已编译、安装或完成实际通知投递 |
| SOURCE_ONLY | Windows 无 Mac 流程 | 已加入 GitHub macOS 26 工作流：先运行现有 XCTest，再编译无签名 iPhone 安装包；工作流尚未上传 GitHub，也未运行 |
| NOT_RUN | Windows 安装 | 安装说明已加入 `NO_MAC.md`；没有该用户的 Apple 账号与实体 iPhone，未通过 Sideloadly 签名或安装 |

当前可审查、可交给云端 Xcode 的工程已生成；**原生应用功能尚未经编译和运行验证**。`node .tooling/verify-delivery.cjs` 只检查工程结构，不能替代 Swift 编译。

进入 macOS 环境后，应先运行 Xcode `Product → Test`，再在真机核对：具体药品与剂量一键打卡、退出重启后的记录、补记和作废后的库存、跨天/时区、七天校验、通知授权与实际投递，以及免费签名续签后的数据保留。过程中须使用测试药品和隔离数据，不要以真实医嘱或真实服药操作作为首次验收数据。
