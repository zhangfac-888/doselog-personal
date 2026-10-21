# 智能用药管理 · iPhone 本机版

本工程是原有“只记时间”工程的升级。日常在“今日”页点击写有具体药品和剂量的按钮，保存实际点击时间；过去服过的药可以单独补记实际时间和剂量。数据保存在设备的 Application Support 中，没有账号、服务器或第三方依赖。

## 已实现

- 药品名称、规格、库存单位、低库存阈值与初次库存；未知库存显示“待盘点”，不冒充 0。已有服药记录后锁定药品名称与规格，仍可修改低库存阈值。
- 每日固定时间、按小时固定间隔、服药/停药日周期计划。计划保留医嘱依据、开始及停用时间。若规则改变，停用旧计划并新建，历史记录仍指向旧版本。
- 今日按药品和剂量展示应服任务；从对应卡片一键记录、部分剂量继续记录，也可补记或记录计划外服药。
- 每条记录保存药品 ID、实际数量、实际时间、录入时间与计划归属；左滑作废误记录。补记与作废会重新计算状态。
- 最近 168 小时回溯：应服但未确认、记录剂量超出该次计划、显式设置的提前/延后界限、最小间隔、间隔计划差异及周期停药日记录。间隔检查读取窗口开始前的相邻记录。
- 库存流水：初始库存、盘点、补货、损耗；实际服药记录扣库存，作废后恢复。盘点成为新的绝对基准，盘点前发生而后来补记的服药不重复扣减。跨越低库存阈值时安排本地通知，并显示未来 30 天按当前计划预计的首次不足时间。
- 本地通知：用户主动授权后，安排未来待服与未确认提醒；计划或记录改变后重新调度。通知默认不展示药品名称，以免锁屏暴露信息。
- 旧版无药品/剂量的时间记录保留在“药品待归属”，不自动猜测、匹配或扣库存。

## 使用顺序

1. “药品与计划”→右上角“+”添加药品。初次库存可以留空，之后使用“盘点/补货”。填写低库存阈值。
2. 在该药下选“新增计划”，如实输入医嘱中的每次剂量、时间或间隔、需要时的周期，以及医嘱依据。不确定的医学容差和最小间隔留空，系统不会自行判为安全。
3. 返回“今日”，核对每张卡片的药品、规格、数量和计划时间。**已经实际服药时**点击写有该药品和剂量的按钮；随后可到“记录与校验”查看结果。
4. 在“今日”页点击“开启本机提醒”授予 iOS 通知权限。提醒覆盖期限会直接显示在页面中。

## 数据与提醒的边界

“尚未确认”表示没有匹配的记录，不代表能够确认用户没吃药。出现时间或剂量偏差时，App 只指出已录入规则与记录的差异，不提供补服、加倍或调整剂量的建议。计划内容应按处方或药师说明输入。库存只根据记录推算，实物与账面不符时需要盘点。

系统会预先交给 iOS 安排有限数量的本地通知，打开 App 或更新数据时补充后续提醒。静音、专注模式、通知权限、设备关机及系统调度都可能影响实际提醒。后台运行也无法保证某一时刻执行校验；前台会重新回溯。

当前固定钟点和服药/停药周期按手机**当前当地日历**计算；按小时的间隔按真实经过小时数计算。跨时区旅行会改变固定钟点对应的绝对时间，出行前须核对具体医嘱及页面显示的计划。七天校验仅依据用户录入的规则与记录，不证明用药符合全部医嘱要求。

数据目录排除系统备份，没有导出/导入功能；卸载或设备损坏会丢失本机记录。需要长期使用之前应增加人工导出与恢复功能。免费 Personal Team 的签名通常需要定期续签，不能将未续签版本作为唯一提醒途径。[Apple 个人账号说明](https://developer.apple.com/help/account/basics/about-your-developer-account)

## 工程文件

```text
DoseLog.xcodeproj/             Xcode 工程与共享 scheme
DoseLog/DoseLogApp.swift       App 入口
DoseLog/DoseRecord.swift       药品、计划、记录、库存模型
DoseLog/PlanEngine.swift       计划生成、七天校验、库存计算
DoseLog/RecordStore.swift      原子写入、旧版迁移、业务操作
DoseLog/ReminderCoordinator.swift  本地通知调度
DoseLog/ContentView.swift      今日、药品/计划、历史/校验页面
DoseLogTests/RecordStoreTests.swift  真实业务类的独立临时目录测试
.github/workflows/build-iphone.yml  无 Mac 云端编译、模拟器测试与安装包打包
NO_MAC.md                     Windows 安装步骤与免费签名限制
COMPLETE_SOURCE.md             与工程文件同步生成的完整源码
VERIFICATION.md                本次实际检查结果与未运行项
```

存储仍采用单个原子替换的 JSON 文件，个人少量记录时可以把相关业务修改一次写入。现在没有迁移到 SQLite；如果后续要处理大量药品、批次和复杂查询，再迁移数据库。当前库存没有批次、效期和多单位换算；药品规格是显示与辨识用的信息，App 不会根据它自行换算剂量。

## 签名与运行

**没有 Mac 的 Windows 用户：**按 [NO_MAC.md](NO_MAC.md) 使用云端编译与 Windows 安装。首次云端编译及 13 个模拟器测试已通过；实体 iPhone 签名安装与通知投递尚未实测。

在 Mac 上用支持手机当前 iOS 版本的 Xcode 打开 `DoseLog.xcodeproj`。部署目标为 iOS 16.0。进入应用 target 的 **Signing & Capabilities**，开启 **Automatically manage signing**，选择自己的 Team，把 `com.example.personal.DoseLog` 改为唯一 Bundle Identifier。连接并信任 iPhone，按提示在手机开启开发者模式，选择手机作为运行目标后按 **⌘R**。首次签名需要 Apple Account 和网络；App 运行中的记录与通知不使用服务器。

Apple 参考：[运行到实体设备](https://developer.apple.com/documentation/Xcode/running-your-app-on-simulated-or-physical-devices)、[开启开发者模式](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)、[本地通知机制](https://developer.apple.com/documentation/usernotifications/scheduling-a-notification-locally-from-your-app)。

在 Mac 上选好一个可用的 iPhone 模拟器后可执行 `Product → Test`，或使用：

```sh
xcodebuild -project DoseLog.xcodeproj -scheme DoseLog \
  -destination 'platform=iOS Simulator,id=<SIMULATOR_UDID>' \
  -derivedDataPath /tmp/DoseLogDerivedData \
  CODE_SIGNING_ALLOWED=NO test
```

此处的 `SIMULATOR_UDID` 可用 `xcrun simctl list devices available` 查询。本轮尚无 macOS/Xcode 真机编译结果；具体状态见 `VERIFICATION.md`。
