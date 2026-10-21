# 完整源码与工程配置

以下内容从交付文件直接生成，无省略。运行请使用原始工程文件；说明见 README.md。

## DoseLog/DoseLogApp.swift

```swift
import SwiftUI

@main
@MainActor
struct DoseLogApp: App {
    @StateObject private var store = RecordStore()
    @StateObject private var reminders = ReminderCoordinator()

    var body: some Scene {
        WindowGroup {
            ContentView(store: store, reminders: reminders)
        }
    }
}
```

## DoseLog/DoseRecord.swift

```swift
import Foundation

// All quantities are in the medication's stock unit (tablet, capsule, or mL).
// Decimal keeps fractional liquid doses and stock balances exact.
enum StockUnit: String, Codable, CaseIterable, Identifiable {
    case tablet = "片"
    case capsule = "粒"
    case millilitre = "mL"
    var id: String { rawValue }
}

struct Medication: Identifiable, Codable, Equatable {
    var id = UUID()
    var name: String
    var strength: String
    var unit: StockUnit
    var lowStock: Decimal
    var createdAt = Date()
    var isArchived = false
    var stockKnown = false

    var label: String { strength.isEmpty ? name : "\(name) · \(strength)" }
}

enum ScheduleRule: Codable, Equatable {
    case daily(minutesOfDay: [Int])
    case interval(hours: Int, anchor: Date)
}

struct CycleRule: Codable, Equatable {
    var onDays: Int
    var offDays: Int
    var anchor: Date
}

struct DosePlan: Identifiable, Codable, Equatable {
    var id = UUID()
    var medicationID: UUID
    var amount: Decimal
    var rule: ScheduleRule
    var cycle: CycleRule?
    var activeFrom: Date
    var activeUntil: Date?
    // These are medical rules only when explicitly entered from the user's directions.
    var earlyMinutes: Int?
    var lateMinutes: Int?
    var minimumGapHours: Int?
    var directionSource: String
    // Notification preference; does not change medical time windows.
    var followUpMinutes: Int = 30
}

struct ScheduledDose: Identifiable, Equatable {
    var planID: UUID
    var medicationID: UUID
    var at: Date
    var amount: Decimal
    var id: String { "\(planID.uuidString):\(Int(at.timeIntervalSince1970))" }
}

struct DoseEvent: Identifiable, Codable, Equatable {
    var id = UUID()
    var medicationID: UUID
    var amount: Decimal
    var takenAt: Date
    var enteredAt: Date
    var planID: UUID?
    var scheduledAt: Date?
    var isBackfilled: Bool
    var isVoided = false
}

enum StockReason: String, Codable {
    case opening, replenishment, correction, waste
}

struct StockEvent: Identifiable, Codable, Equatable {
    var id = UUID()
    var medicationID: UUID
    // For .correction this is the counted absolute balance; otherwise a signed change.
    var delta: Decimal
    var at: Date
    var reason: StockReason
}

struct LegacyTimeRecord: Identifiable, Codable, Equatable {
    var id: UUID
    var takenAt: Date
}

struct LegacyArchive: Codable {
    var version: Int
    var records: [LegacyTimeRecord]
}

struct MedicationArchive: Codable {
    var version = 2
    var medications: [Medication] = []
    var plans: [DosePlan] = []
    var doses: [DoseEvent] = []
    var stock: [StockEvent] = []
    var legacyUnassigned: [LegacyTimeRecord] = []
}

extension Decimal {
    var doseText: String { NSDecimalNumber(decimal: self).stringValue }
}
```

## DoseLog/RecordStore.swift

```swift
import Combine
import Foundation

/// A personal-size atomic archive: one successful write commits the dose, plan link,
/// and stock calculation together. Failed reads never turn existing data into empty data.
@MainActor
final class RecordStore: ObservableObject {
    @Published private(set) var archive = MedicationArchive()
    @Published private(set) var canEdit = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var revision = 0
    @Published private(set) var newLowStock: [Medication] = []

    private let fileURL: URL

    static var defaultFileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DoseLog", isDirectory: true)
            .appendingPathComponent("records.json")
    }

    init(fileURL: URL? = nil) {
        self.fileURL = fileURL ?? Self.defaultFileURL
        reload()
    }

    func reload() {
        do {
            try prepareDirectory()
            let data: Data
            do {
                data = try Data(contentsOf: fileURL)
            } catch CocoaError.fileReadNoSuchFile {
                archive = MedicationArchive()
                canEdit = true
                errorMessage = nil
                revision += 1
                return
            }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .millisecondsSince1970
            let header = try decoder.decode(ArchiveVersion.self, from: data)
            let decoded: MedicationArchive
            switch header.version {
            case 1:
                let old = try decoder.decode(LegacyArchive.self, from: data)
                decoded = MedicationArchive(legacyUnassigned: old.records)
            case 2:
                decoded = try decoder.decode(MedicationArchive.self, from: data)
            default:
                throw StoreError.invalidArchive
            }
            guard valid(decoded) else { throw StoreError.invalidArchive }
            archive = decoded
            canEdit = true
            errorMessage = nil
            revision += 1
        } catch {
            canEdit = false
            errorMessage = "读取记录失败。为保护原文件，已停止记录；请核实存储后重试。"
        }
    }

    @discardableResult
    func addMedication(name: String, strength: String, unit: StockUnit,
                       opening: Decimal?, lowStock: Decimal, now: Date = Date()) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, (opening.map { $0 >= 0 } ?? true), lowStock >= 0 else {
            return reject("请填写药品名称，并使用非负的库存数量。")
        }
        var next = archive
        let medicine = Medication(name: trimmed,
            strength: strength.trimmingCharacters(in: .whitespacesAndNewlines),
            unit: unit, lowStock: lowStock, createdAt: now,
            stockKnown: opening != nil)
        next.medications.append(medicine)
        if let opening, opening > 0 {
            next.stock.append(StockEvent(medicationID: medicine.id,
                delta: opening, at: now, reason: .opening))
        }
        return commit(next)
    }

    @discardableResult
    func updateMedication(_ id: UUID, name: String, strength: String,
                          lowStock: Decimal) -> Bool {
        guard let index = archive.medications.firstIndex(where: { $0.id == id }),
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              lowStock >= 0 else { return reject("请核对药品名称和库存阈值。") }
        let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedStrength = strength.trimmingCharacters(in: .whitespacesAndNewlines)
        let original = archive.medications[index]
        if archive.doses.contains(where: { $0.medicationID == id }) &&
           (original.name != normalizedName || original.strength != normalizedStrength) {
            return reject("已有服药记录；为保留历史药品身份，只能修改库存阈值。换药请新建档案。")
        }
        var next = archive
        next.medications[index].name = normalizedName
        next.medications[index].strength = normalizedStrength
        next.medications[index].lowStock = lowStock
        return commit(next)
    }

    @discardableResult
    func addPlan(_ plan: DosePlan) -> Bool {
        guard archive.medications.contains(where: { $0.id == plan.medicationID && !$0.isArchived }),
              plan.amount > 0, (0...10080).contains(plan.followUpMinutes),
              !plan.directionSource.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              plan.earlyMinutes.map({ $0 >= 0 }) ?? true,
              plan.lateMinutes.map({ $0 >= 0 }) ?? true,
              plan.minimumGapHours.map({ $0 > 0 }) ?? true else {
            return reject("计划信息不完整，请核对药品、剂量和医嘱来源。")
        }
        switch plan.rule {
        case .daily(let minutes):
            guard !minutes.isEmpty, minutes.allSatisfy({ (0..<1440).contains($0) }) else {
                return reject("请输入有效的每日时间。")
            }
        case .interval(let hours, _):
            guard (1...168).contains(hours) else { return reject("间隔应在 1 至 168 小时之间。") }
        }
        if let cycle = plan.cycle,
           (cycle.onDays < 1 || cycle.onDays > 365 ||
            cycle.offDays < 0 || cycle.offDays > 365 ||
            cycle.onDays + cycle.offDays > 365) {
            return reject("请核对服药与停药的周期天数。")
        }
        var next = archive
        next.plans.append(plan)
        return commit(next)
    }

    @discardableResult
    func stopPlan(_ id: UUID, at time: Date = Date()) -> Bool {
        guard let index = archive.plans.firstIndex(where: { $0.id == id }),
              archive.plans[index].activeUntil == nil,
              archive.plans[index].activeFrom <= time else { return reject("无法停用该计划。") }
        var next = archive
        next.plans[index].activeUntil = time
        return commit(next)
    }

    @discardableResult
    func record(medicationID: UUID, amount: Decimal, takenAt: Date,
                scheduled: ScheduledDose? = nil, enteredAt: Date = Date(),
                backfilled: Bool = false) -> Bool {
        guard archive.medications.contains(where: { $0.id == medicationID }), amount > 0,
              takenAt <= enteredAt.addingTimeInterval(60) else {
            return reject("请核对药品、剂量和实际服药时间。")
        }
        if let scheduled {
            guard scheduled.medicationID == medicationID,
                  PlanEngine.scheduled(plans: archive.plans,
                    from: scheduled.at, through: scheduled.at).contains(scheduled) else {
                return reject("计划已改变，请重新选择本次应服药品。")
            }
        }
        var next = archive
        next.doses.append(DoseEvent(medicationID: medicationID,
            amount: amount, takenAt: takenAt, enteredAt: enteredAt,
            planID: scheduled?.planID, scheduledAt: scheduled?.at,
            isBackfilled: backfilled))
        return commit(next)
    }

    @discardableResult
    func voidDose(_ id: UUID) -> Bool {
        guard let index = archive.doses.firstIndex(where: { $0.id == id && !$0.isVoided }) else {
            return reject("找不到这条有效记录。")
        }
        var next = archive
        next.doses[index].isVoided = true
        return commit(next)
    }

    @discardableResult
    func deleteLegacy(_ id: UUID) -> Bool {
        guard archive.legacyUnassigned.contains(where: { $0.id == id }) else {
            return reject("找不到这条旧版记录。")
        }
        var next = archive
        next.legacyUnassigned.removeAll { $0.id == id }
        return commit(next)
    }

    @discardableResult
    func addStock(medicationID: UUID, delta: Decimal, reason: StockReason,
                  at time: Date = Date()) -> Bool {
        guard archive.medications.contains(where: { $0.id == medicationID && $0.stockKnown }),
              (reason == .replenishment && delta > 0) ||
              (reason == .waste && delta < 0) else {
            return reject("请核对药品和库存数量。")
        }
        var next = archive
        next.stock.append(StockEvent(medicationID: medicationID,
            delta: delta, at: time, reason: reason))
        return commit(next)
    }

    @discardableResult
    func countStock(medicationID: UUID, counted: Decimal,
                    at time: Date = Date()) -> Bool {
        guard counted >= 0,
              let index = archive.medications.firstIndex(where: { $0.id == medicationID }) else {
            return reject("请核对盘点数量。")
        }
        var next = archive
        next.medications[index].stockKnown = true
        next.stock.append(StockEvent(medicationID: medicationID,
            delta: counted, at: time, reason: .correction))
        return commit(next)
    }

    func balance(for medicationID: UUID) -> Decimal {
        PlanEngine.balance(for: medicationID, archive: archive)
    }

    func today(at now: Date = Date(), calendar: Calendar = .autoupdatingCurrent) -> [ScheduledDose] {
        guard let day = calendar.dateInterval(of: .day, for: now) else { return [] }
        return PlanEngine.scheduled(plans: archive.plans, from: day.start,
            through: day.end.addingTimeInterval(-0.001), calendar: calendar)
    }

    func findings(at now: Date = Date()) -> [AuditFinding] {
        PlanEngine.audit(archive, now: now)
    }

    private func commit(_ next: MedicationArchive) -> Bool {
        guard canEdit, valid(next) else { return reject("数据状态异常，未写入。") }
        do {
            try prepareDirectory()
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .millisecondsSince1970
            encoder.outputFormatting = [.sortedKeys]
            let bytes = try encoder.encode(next)
            try bytes.write(to: fileURL, options: [.atomic, .completeFileProtection])
            newLowStock = next.medications.filter { medication in
                guard medication.stockKnown else { return false }
                let previous = PlanEngine.balance(for: medication.id, archive: archive)
                let current = PlanEngine.balance(for: medication.id, archive: next)
                let wasKnown = archive.medications.first(where: {
                    $0.id == medication.id
                })?.stockKnown ?? false
                let oldThreshold = archive.medications.first(where: {
                    $0.id == medication.id
                })?.lowStock
                return (!wasKnown || oldThreshold != medication.lowStock ||
                        previous >= medication.lowStock) &&
                    current < medication.lowStock
            }
            archive = next
            errorMessage = nil
            revision += 1
            return true
        } catch {
            errorMessage = "保存失败，本次操作未生效；请检查设备空间后重试。"
            return false
        }
    }

    private func reject(_ message: String) -> Bool {
        errorMessage = message
        return false
    }

    private func prepareDirectory() throws {
        var directory = fileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
    }

    private func valid(_ archive: MedicationArchive) -> Bool {
        guard archive.version == 2 else { return false }
        let medications = Set(archive.medications.map(\.id))
        let plans = Set(archive.plans.map(\.id))
        return medications.count == archive.medications.count &&
            plans.count == archive.plans.count &&
            Set(archive.doses.map(\.id)).count == archive.doses.count &&
            Set(archive.stock.map(\.id)).count == archive.stock.count &&
            Set(archive.legacyUnassigned.map(\.id)).count == archive.legacyUnassigned.count &&
            archive.medications.allSatisfy { $0.lowStock >= 0 } &&
            archive.plans.allSatisfy { plan in
                guard medications.contains(plan.medicationID), plan.amount > 0,
                      !plan.directionSource.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      (plan.activeUntil == nil || plan.activeUntil! > plan.activeFrom),
                      (0...10080).contains(plan.followUpMinutes),
                      plan.earlyMinutes.map({ $0 >= 0 }) ?? true,
                      plan.lateMinutes.map({ $0 >= 0 }) ?? true,
                      plan.minimumGapHours.map({ $0 > 0 }) ?? true else { return false }
                if let cycle = plan.cycle {
                    guard cycle.onDays > 0, cycle.onDays <= 365,
                          cycle.offDays >= 0, cycle.offDays <= 365,
                          cycle.onDays + cycle.offDays <= 365 else { return false }
                }
                switch plan.rule {
                case .daily(let minutes):
                    return !minutes.isEmpty && minutes.allSatisfy { (0..<1440).contains($0) }
                case .interval(let hours, _):
                    return (1...168).contains(hours)
                }
            } &&
            archive.doses.allSatisfy { dose in
                medications.contains(dose.medicationID) && dose.amount > 0 &&
                (dose.planID == nil || archive.plans.contains { plan in
                    plan.id == dose.planID &&
                    plan.medicationID == dose.medicationID && dose.scheduledAt != nil
                })
            } &&
            archive.stock.allSatisfy { medications.contains($0.medicationID) }
    }

    private struct ArchiveVersion: Decodable { var version: Int }
    private enum StoreError: Error { case invalidArchive }
}
```

## DoseLog/ContentView.swift

```swift
import Combine
import SwiftUI

@MainActor
struct ContentView: View {
    @ObservedObject var store: RecordStore
    @ObservedObject var reminders: ReminderCoordinator
    @Environment(\.scenePhase) private var scenePhase
    @State private var now = Date()
    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView {
            NavigationStack {
                TodayPage(store: store, reminders: reminders, now: now)
            }
            .tabItem { Label("今日", systemImage: "checkmark.circle") }

            NavigationStack {
                MedicationsPage(store: store)
            }
            .tabItem { Label("药品与计划", systemImage: "pills") }

            NavigationStack {
                HistoryPage(store: store, now: now)
            }
            .tabItem { Label("记录与校验", systemImage: "list.bullet.clipboard") }
        }
        .onReceive(clock) { value in
            if scenePhase == .active { now = value }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                now = Date()
                if !store.canEdit { store.reload() }
                Task { await reminders.sync(store.archive) }
            }
        }
        .onChange(of: store.revision) { _ in
            Task {
                await reminders.sync(store.archive)
                for medication in store.newLowStock {
                    await reminders.notifyLowStock(for: medication)
                }
            }
        }
        .task { await reminders.sync(store.archive) }
    }
}

@MainActor
private struct TodayPage: View {
    @ObservedObject var store: RecordStore
    @ObservedObject var reminders: ReminderCoordinator
    let now: Date
    @State private var showExtra = false
    @State private var backfill: ScheduledDose?

    var body: some View {
        let medicines = Dictionary(uniqueKeysWithValues:
            store.archive.medications.map { ($0.id, $0) })
        let today = store.today(at: now)
        let completed = store.archive.doses.filter {
            !$0.isVoided && Calendar.autoupdatingCurrent.isDate($0.takenAt, inSameDayAs: now)
        }.sorted { $0.takenAt > $1.takenAt }

        List {
            if let message = store.errorMessage {
                Section { Text(message).foregroundStyle(.red) }
            }
            Section {
                if today.isEmpty {
                    Text("今天没有已设置的用药计划")
                        .foregroundStyle(.secondary)
                }
                ForEach(today) { dose in
                    if let medication = medicines[dose.medicationID] {
                        let recorded = PlanEngine.matchedEvents(for: dose,
                            in: store.archive.doses).reduce(Decimal.zero) { $0 + $1.amount }
                        VStack(alignment: .leading, spacing: 9) {
                            Text(medication.label).font(.headline)
                            Text("计划 \(dose.at.formatted(date: .omitted, time: .shortened)) · \(dose.amount.doseText) \(medication.unit.rawValue)")
                                .foregroundStyle(.secondary)
                            if recorded >= dose.amount {
                                Text("已记录 \(recorded.doseText) \(medication.unit.rawValue)")
                                    .foregroundStyle(.green)
                            } else {
                                let remaining = dose.amount - recorded
                                if recorded > 0 {
                                    Text("已记录 \(recorded.doseText) \(medication.unit.rawValue)，尚有 \(remaining.doseText) \(medication.unit.rawValue) 未确认")
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                                Button("已服 \(medication.name) \(remaining.doseText) \(medication.unit.rawValue)，记录现在") {
                                    store.record(medicationID: medication.id,
                                        amount: remaining, takenAt: Date(),
                                        scheduled: dose)
                                }
                                .buttonStyle(.borderedProminent)
                                .disabled(!store.canEdit)
                                Button("剂量不同或补记实际时间") { backfill = dose }
                                    .font(.footnote)
                            }
                        }
                        .padding(.vertical, 7)
                    }
                }
            } header: { Text("今日应服 · \(now.formatted(date: .abbreviated, time: .omitted))") }

            Section("今日实际记录") {
                if completed.isEmpty {
                    Text("今天还没有服药记录").foregroundStyle(.secondary)
                }
                ForEach(completed) { event in
                    if let medication = medicines[event.medicationID] {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(medication.label)
                                Text("\(event.amount.doseText) \(medication.unit.rawValue)")
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(event.takenAt, format: .dateTime.hour().minute().second())
                                .monospacedDigit()
                        }
                    }
                }
                .onDelete { positions in
                    for index in positions { store.voidDose(completed[index].id) }
                }
                Text("左滑可作废误记录；库存会同步恢复。")
                    .font(.footnote).foregroundStyle(.secondary)
            }

            Section {
                Button("记录额外服药或补记") { showExtra = true }
                    .disabled(store.archive.medications.isEmpty || !store.canEdit)
            }

            Section("提醒") {
                Text(reminders.status).font(.footnote)
                Button("开启本机提醒") {
                    Task { await reminders.enable(using: store.archive) }
                }
            }

            if !store.archive.legacyUnassigned.isEmpty {
                Section {
                    Text("有 \(store.archive.legacyUnassigned.count) 条旧版时间记录缺少药品与剂量，已保留且未计入库存。")
                        .font(.footnote)
                }
            }
        }
        .navigationTitle("今日用药")
        .sheet(isPresented: $showExtra) {
            DoseEntrySheet(store: store, scheduled: nil)
        }
        .sheet(item: $backfill) { value in
            DoseEntrySheet(store: store, scheduled: value)
        }
    }
}

@MainActor
private struct MedicationsPage: View {
    @ObservedObject var store: RecordStore
    @State private var showAdd = false
    @State private var planFor: Medication?
    @State private var stockFor: Medication?
    @State private var editFor: Medication?

    var body: some View {
        List {
            if let message = store.errorMessage {
                Section { Text(message).foregroundStyle(.red) }
            }
            ForEach(store.archive.medications.filter { !$0.isArchived }) { medicine in
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(medicine.label).font(.headline)
                        Text(medicine.stockKnown
                            ? "库存 \(store.balance(for: medicine.id).doseText) \(medicine.unit.rawValue) · 提醒阈值 \(medicine.lowStock.doseText)"
                            : "库存待盘点")
                            .foregroundStyle(medicine.stockKnown &&
                                store.balance(for: medicine.id) < medicine.lowStock ? Color.red : Color.secondary)
                        if let stockout = PlanEngine.expectedStockout(
                            for: medicine.id, archive: store.archive) {
                            Text("按当前计划，预计 \(stockout.formatted(date: .abbreviated, time: .shortened)) 库存不足")
                                .font(.footnote).foregroundStyle(.orange)
                        }
                        HStack {
                            Button("新增计划") { planFor = medicine }
                            Spacer()
                            Button("盘点/补货") { stockFor = medicine }
                        }
                        .buttonStyle(.bordered)
                        Button("编辑药品资料") { editFor = medicine }
                            .font(.footnote)
                    }
                    ForEach(store.archive.plans.filter {
                        $0.medicationID == medicine.id && $0.activeUntil == nil
                    }) { plan in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(planSummary(plan, unit: medicine.unit))
                            Text("依据：\(plan.directionSource)")
                                .font(.footnote).foregroundStyle(.secondary)
                            Button("停用此计划", role: .destructive) {
                                store.stopPlan(plan.id)
                            }
                            .font(.footnote)
                        }
                    }
                }
            }
            if store.archive.medications.isEmpty {
                Section { Text("先添加药品，再按医嘱设置计划。") }
            }
        }
        .navigationTitle("药品与计划")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showAdd = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("添加药品")
            }
        }
        .sheet(isPresented: $showAdd) { MedicationEditor(store: store) }
        .sheet(item: $planFor) { PlanEditor(store: store, medication: $0) }
        .sheet(item: $stockFor) { StockEditor(store: store, medication: $0) }
        .sheet(item: $editFor) { MedicationEditSheet(store: store, medication: $0) }
    }

    private func planSummary(_ plan: DosePlan, unit: StockUnit) -> String {
        let rule: String
        switch plan.rule {
        case .daily(let minutes):
            rule = minutes.map { String(format: "%02d:%02d", $0 / 60, $0 % 60) }
                .joined(separator: "、")
        case .interval(let hours, _):
            rule = "每隔 \(hours) 小时"
        }
        let cycle = plan.cycle.map { " · 服 \($0.onDays) 天/停 \($0.offDays) 天" } ?? ""
        return "\(rule) · \(plan.amount.doseText) \(unit.rawValue)\(cycle)"
    }
}

@MainActor
private struct HistoryPage: View {
    @ObservedObject var store: RecordStore
    let now: Date

    var body: some View {
        let medicines = Dictionary(uniqueKeysWithValues:
            store.archive.medications.map { ($0.id, $0) })
        let events = store.archive.doses.filter { !$0.isVoided }
            .sorted { $0.takenAt > $1.takenAt }
        let legacy = store.archive.legacyUnassigned.sorted { $0.takenAt > $1.takenAt }
        let findings = store.findings(at: now)
        List {
            Section("近七天规则校验") {
                if findings.isEmpty {
                    Text("没有发现已配置规则的偏差；未配置的规则无法判断。")
                        .foregroundStyle(.secondary)
                }
                ForEach(findings) { finding in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(medicines[finding.medicationID]?.name ?? "未知药品")：\(finding.title)")
                            .font(.headline)
                        Text(finding.detail).font(.footnote)
                    }
                }
            }
            Section("服药历史") {
                if events.isEmpty { Text("尚无具体药品的服药记录") }
                ForEach(events) { event in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(medicines[event.medicationID]?.label ?? "未知药品") · \(event.amount.doseText) \(medicines[event.medicationID]?.unit.rawValue ?? "")")
                        Text("实际：\(event.takenAt.formatted(date: .abbreviated, time: .standard))\(event.isBackfilled ? " · 补记" : "")")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .onDelete { positions in
                    for index in positions { store.voidDose(events[index].id) }
                }
            }
            if !legacy.isEmpty {
                Section("旧版记录 · 药品待归属") {
                    ForEach(legacy) { old in
                        Text(old.takenAt, format: .dateTime.year().month().day().hour().minute().second())
                    }
                    .onDelete { positions in
                        for index in positions { store.deleteLegacy(legacy[index].id) }
                    }
                }
            }
        }
        .navigationTitle("记录与校验")
    }
}

@MainActor
private struct MedicationEditor: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: RecordStore
    @State private var name = ""
    @State private var strength = ""
    @State private var unit: StockUnit = .tablet
    @State private var opening = ""
    @State private var lowStock = ""
    @State private var warning = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("药品") {
                    TextField("名称", text: $name)
                    TextField("规格，如每片含量", text: $strength)
                    Picker("库存单位", selection: $unit) {
                        ForEach(StockUnit.allCases) { Text($0.rawValue).tag($0) }
                    }
                }
                Section("库存") {
                    TextField("目前数量（可留空，稍后盘点）", text: $opening)
                        .keyboardType(.decimalPad)
                    TextField("低库存阈值", text: $lowStock)
                        .keyboardType(.decimalPad)
                }
                if !warning.isEmpty { Text(warning).foregroundStyle(.red) }
            }
            .navigationTitle("添加药品")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let initial = opening.isEmpty ? nil : parseAmount(opening)
                        guard let threshold = parseAmount(lowStock),
                              (opening.isEmpty || initial != nil),
                              !name.trimmingCharacters(in: .whitespaces).isEmpty else {
                            warning = "请核对药品名称和库存数字；阈值为必填。"
                            return
                        }
                        if store.addMedication(name: name, strength: strength,
                            unit: unit, opening: initial, lowStock: threshold) {
                            dismiss()
                        } else { warning = store.errorMessage ?? "保存失败" }
                    }
                }
            }
        }
    }
}

@MainActor
private struct MedicationEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: RecordStore
    let medication: Medication
    @State private var name: String
    @State private var strength: String
    @State private var threshold: String
    @State private var warning = ""

    init(store: RecordStore, medication: Medication) {
        self.store = store
        self.medication = medication
        _name = State(initialValue: medication.name)
        _strength = State(initialValue: medication.strength)
        _threshold = State(initialValue: medication.lowStock.doseText)
    }

    var body: some View {
        let hasHistory = store.archive.doses.contains { $0.medicationID == medication.id }
        NavigationStack {
            Form {
                Section("药品身份") {
                    TextField("名称", text: $name).disabled(hasHistory)
                    TextField("规格", text: $strength).disabled(hasHistory)
                    if hasHistory {
                        Text("已有服药记录，药品身份已锁定；换药请新增药品档案。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("库存提醒") {
                    TextField("低库存阈值", text: $threshold)
                        .keyboardType(.decimalPad)
                }
                if !warning.isEmpty { Text(warning).foregroundStyle(.red) }
            }
            .navigationTitle("编辑药品")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        guard let value = parseAmount(threshold) else {
                            warning = "请输入有效的库存阈值。"
                            return
                        }
                        if store.updateMedication(medication.id, name: name,
                            strength: strength, lowStock: value) { dismiss() }
                        else { warning = store.errorMessage ?? "保存失败" }
                    }
                }
            }
        }
    }
}

@MainActor
private struct PlanEditor: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: RecordStore
    let medication: Medication
    @State private var amount = ""
    @State private var schedule = 0
    @State private var dailyTime = ""
    @State private var intervalHours = ""
    @State private var anchor = Date()
    @State private var hasCycle = false
    @State private var onDays = ""
    @State private var offDays = ""
    @State private var cycleAnchor = Date()
    @State private var early = ""
    @State private var late = ""
    @State private var minimumGap = ""
    @State private var followUp = "30"
    @State private var source = ""
    @State private var warning = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("\(medication.label) 的医嘱") {
                    TextField("每次剂量（\(medication.unit.rawValue)）", text: $amount)
                        .keyboardType(.decimalPad)
                    TextField("依据：处方/药袋标注", text: $source)
                }
                Section("时间计划") {
                    Picker("规则", selection: $schedule) {
                        Text("每日固定时间").tag(0)
                        Text("固定间隔").tag(1)
                    }
                    if schedule == 0 {
                        TextField("时间 HH:mm，例如 08:30", text: $dailyTime)
                            .keyboardType(.numbersAndPunctuation)
                    } else {
                        TextField("间隔小时数", text: $intervalHours)
                            .keyboardType(.numberPad)
                        DatePicker("间隔起点", selection: $anchor)
                    }
                    Toggle("分服药/停药周期", isOn: $hasCycle)
                    if hasCycle {
                        TextField("连续服药天数", text: $onDays)
                            .keyboardType(.numberPad)
                        TextField("随后停药天数", text: $offDays)
                            .keyboardType(.numberPad)
                        DatePicker("周期起点", selection: $cycleAnchor,
                            displayedComponents: .date)
                    }
                }
                Section("医嘱校验 · 不明确请留空") {
                    TextField("最多允许提前分钟数", text: $early)
                        .keyboardType(.numberPad)
                    TextField("最多允许延后分钟数", text: $late)
                        .keyboardType(.numberPad)
                    TextField("两次之间最少间隔小时数", text: $minimumGap)
                        .keyboardType(.numberPad)
                }
                Section("通知偏好") {
                    TextField("未确认后再次提醒：分钟", text: $followUp)
                        .keyboardType(.numberPad)
                }
                if !warning.isEmpty { Text(warning).foregroundStyle(.red) }
            }
            .navigationTitle("新增计划")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                }
            }
        }
    }

    private func save() {
        guard let dose = parseAmount(amount), dose > 0,
              !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let earlyValue = optionalInt(early),
              let lateValue = optionalInt(late),
              let gapValue = optionalInt(minimumGap),
              let reminder = Int(followUp), (0...10080).contains(reminder),
              gapValue.map({ $0 > 0 }) ?? true else {
            warning = "请核对剂量、医嘱依据和规则数字。"
            return
        }
        let rule: ScheduleRule
        if schedule == 0 {
            let parts = dailyTime.split(separator: ":", omittingEmptySubsequences: false)
            guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]),
                  (0..<24).contains(h), (0..<60).contains(m) else {
                warning = "时间请使用 HH:mm 格式。"
                return
            }
            rule = .daily(minutesOfDay: [h * 60 + m])
        } else {
            guard let hours = Int(intervalHours), (1...168).contains(hours) else {
                warning = "请填写 1 至 168 小时的间隔。"
                return
            }
            rule = .interval(hours: hours, anchor: anchor)
        }
        var cycle: CycleRule?
        if hasCycle {
            guard let daysOn = Int(onDays), daysOn > 0,
                  let daysOff = Int(offDays), daysOff >= 0 else {
                warning = "请核对周期天数。"
                return
            }
            cycle = CycleRule(onDays: daysOn, offDays: daysOff,
                              anchor: cycleAnchor)
        }
        let plan = DosePlan(medicationID: medication.id, amount: dose,
            rule: rule, cycle: cycle, activeFrom: Date(), activeUntil: nil,
            earlyMinutes: earlyValue, lateMinutes: lateValue,
            minimumGapHours: gapValue,
            directionSource: source.trimmingCharacters(in: .whitespacesAndNewlines),
            followUpMinutes: reminder)
        if store.addPlan(plan) { dismiss() }
        else { warning = store.errorMessage ?? "保存失败" }
    }
}

@MainActor
private struct StockEditor: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: RecordStore
    let medication: Medication
    @State private var mode = 0
    @State private var quantity = ""
    @State private var warning = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("\(medication.label) · \(medication.unit.rawValue)") {
                    Text(medication.stockKnown
                        ? "当前账面库存：\(store.balance(for: medication.id).doseText)"
                        : "当前库存未知，请先盘点。")
                    Picker("操作", selection: $mode) {
                        Text("盘点后的实际数量").tag(0)
                        Text("新增入库").tag(1)
                        Text("破损或丢失").tag(2)
                    }
                    TextField("数量", text: $quantity).keyboardType(.decimalPad)
                }
                if !warning.isEmpty { Text(warning).foregroundStyle(.red) }
            }
            .navigationTitle("库存")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        guard let amount = parseAmount(quantity), amount >= 0 else {
                            warning = "请输入非负数量。"
                            return
                        }
                        let saved: Bool
                        switch mode {
                        case 0: saved = store.countStock(medicationID: medication.id, counted: amount)
                        case 1: saved = store.addStock(medicationID: medication.id,
                            delta: amount, reason: .replenishment)
                        default: saved = store.addStock(medicationID: medication.id,
                            delta: -amount, reason: .waste)
                        }
                        if saved { dismiss() }
                        else { warning = store.errorMessage ?? "保存失败" }
                    }
                }
            }
        }
    }
}

@MainActor
private struct DoseEntrySheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: RecordStore
    let scheduled: ScheduledDose?
    @State private var medicationID: UUID?
    @State private var quantity = ""
    @State private var takenAt = Date()
    @State private var warning = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("实际服药") {
                    if let scheduled,
                       let med = store.archive.medications.first(where: { $0.id == scheduled.medicationID }) {
                        Text(med.label)
                    } else {
                        Picker("药品", selection: $medicationID) {
                            Text("请选择药品").tag(nil as UUID?)
                            ForEach(store.archive.medications.filter { !$0.isArchived }) { med in
                                Text(med.label).tag(Optional(med.id))
                            }
                        }
                    }
                    TextField("实际剂量（药品库存单位）", text: $quantity)
                        .keyboardType(.decimalPad)
                    DatePicker("实际服用时间", selection: $takenAt,
                        in: ...Date().addingTimeInterval(60))
                }
                if !warning.isEmpty { Text(warning).foregroundStyle(.red) }
            }
            .navigationTitle("记录实际服药")
            .onAppear {
                if let scheduled {
                    medicationID = scheduled.medicationID
                    quantity = scheduled.amount.doseText
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        guard let medID = medicationID,
                              let amount = parseAmount(quantity), amount > 0 else {
                            warning = "请选择药品并填写实际服用剂量。"
                            return
                        }
                        let entered = Date()
                        if store.record(medicationID: medID, amount: amount,
                            takenAt: takenAt, scheduled: scheduled, enteredAt: entered,
                            backfilled: entered.timeIntervalSince(takenAt) > 60) {
                            dismiss()
                        } else { warning = store.errorMessage ?? "保存失败" }
                    }
                }
            }
        }
    }
}

private func parseAmount(_ text: String) -> Decimal? {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.range(of: #"^[0-9]{1,9}(\.[0-9]{1,3})?$"#,
                        options: .regularExpression) != nil else { return nil }
    return Decimal(string: trimmed, locale: Locale(identifier: "en_US_POSIX"))
}

// The outer optional distinguishes "left blank" from "invalid integer".
private func optionalInt(_ text: String) -> Int?? {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return .some(nil) }
    guard let value = Int(trimmed), value >= 0 else { return nil }
    return .some(value)
}
```

## DoseLog/PlanEngine.swift

```swift
import Foundation

struct AuditFinding: Identifiable, Equatable {
    var id: String
    var medicationID: UUID
    var title: String
    var detail: String
}

enum PlanEngine {
    static func scheduled(
        plans: [DosePlan], from: Date, through: Date,
        calendar: Calendar = .autoupdatingCurrent
    ) -> [ScheduledDose] {
        guard from <= through else { return [] }
        var result: [ScheduledDose] = []
        for plan in plans {
            let lower = max(from, plan.activeFrom)
            let upper = min(through, plan.activeUntil ?? through)
            guard lower <= upper, plan.amount > 0 else { continue }
            switch plan.rule {
            case .daily(let minutes):
                guard !minutes.isEmpty else { continue }
                var day = calendar.startOfDay(for: lower)
                var days = 0
                while day <= upper && days < 370 {
                    if cycleAllows(plan.cycle, on: day, calendar: calendar) {
                        for minute in Set(minutes).sorted() where (0..<1440).contains(minute) {
                            if let due = calendar.date(
                                bySettingHour: minute / 60, minute: minute % 60,
                                second: 0, of: day,
                                matchingPolicy: .nextTime,
                                repeatedTimePolicy: .first,
                                direction: .forward
                            ), due >= lower, due <= upper,
                               due < (plan.activeUntil ?? .distantFuture),
                               calendar.isDate(due, inSameDayAs: day) {
                                result.append(ScheduledDose(planID: plan.id,
                                    medicationID: plan.medicationID, at: due,
                                    amount: plan.amount))
                            }
                        }
                    }
                    guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
                    day = next
                    days += 1
                }
            case .interval(let hours, let anchor):
                guard hours > 0, hours <= 168 else { continue }
                let step = TimeInterval(hours) * 3600
                var index = max(0, Int(ceil(lower.timeIntervalSince(anchor) / step)))
                var count = 0
                while count < 10_000 {
                    let due = anchor.addingTimeInterval(TimeInterval(index) * step)
                    if due > upper { break }
                    if due >= lower && due < (plan.activeUntil ?? .distantFuture) &&
                       cycleAllows(plan.cycle, on: due, calendar: calendar) {
                        result.append(ScheduledDose(planID: plan.id,
                            medicationID: plan.medicationID, at: due,
                            amount: plan.amount))
                    }
                    index += 1
                    count += 1
                }
            }
        }
        return result.sorted { $0.at < $1.at }
    }

    static func cycleAllows(_ cycle: CycleRule?, on date: Date,
                            calendar: Calendar = .autoupdatingCurrent) -> Bool {
        guard let cycle else { return true }
        guard cycle.onDays > 0, cycle.onDays <= 365,
              cycle.offDays >= 0, cycle.offDays <= 365 else { return false }
        let length = cycle.onDays + cycle.offDays
        let anchorDay = calendar.startOfDay(for: cycle.anchor)
        let targetDay = calendar.startOfDay(for: date)
        let distance = calendar.dateComponents([.day], from: anchorDay, to: targetDay).day ?? -1
        guard distance >= 0 else { return false }
        return distance % length < cycle.onDays
    }

    static func matchedEvents(for scheduled: ScheduledDose,
                              in events: [DoseEvent]) -> [DoseEvent] {
        events.filter {
            !$0.isVoided && $0.planID == scheduled.planID &&
            $0.scheduledAt == scheduled.at
        }
    }

    static func balance(for medicationID: UUID, archive: MedicationArchive) -> Decimal {
        // The most recent physical count is an absolute baseline. Earlier doses,
        // including later-entered historical doses, are already reflected in it.
        let baselineIndex = archive.stock.lastIndex {
            $0.medicationID == medicationID && $0.reason == .correction
        }
        let baseline = baselineIndex.map { archive.stock[$0] }
        let stockFrom = baselineIndex.map { $0 + 1 } ?? 0
        let received = archive.stock.dropFirst(stockFrom)
            .filter { $0.medicationID == medicationID }
            .reduce(Decimal.zero) { $0 + $1.delta }
        let consumed = archive.doses.filter {
            guard $0.medicationID == medicationID && !$0.isVoided else { return false }
            guard let baseline else { return true }
            return $0.takenAt > baseline.at ||
                ($0.takenAt == baseline.at && $0.enteredAt > baseline.at)
        }.reduce(Decimal.zero) { $0 + $1.amount }
        return (baseline?.delta ?? 0) + received - consumed
    }

    static func expectedStockout(for medicationID: UUID,
                                 archive: MedicationArchive,
                                 now: Date = Date(),
                                 calendar: Calendar = .autoupdatingCurrent) -> Date? {
        guard archive.medications.contains(where: {
            $0.id == medicationID && $0.stockKnown
        }) else { return nil }
        var remaining = balance(for: medicationID, archive: archive)
        if remaining < 0 { return now }
        guard let end = calendar.date(byAdding: .day, value: 30, to: now) else { return nil }
        let future = scheduled(plans: archive.plans.filter {
            $0.medicationID == medicationID
        }, from: now.addingTimeInterval(1), through: end, calendar: calendar)
        for occurrence in future {
            remaining -= occurrence.amount
            if remaining < 0 { return occurrence.at }
        }
        return nil
    }

    static func audit(_ archive: MedicationArchive, now: Date = Date(),
                      calendar: Calendar = .autoupdatingCurrent) -> [AuditFinding] {
        let start = now.addingTimeInterval(-7 * 24 * 3600)
        let occurrences = scheduled(plans: archive.plans, from: start,
                                    through: now, calendar: calendar)
        var findings: [AuditFinding] = []
        let plans = Dictionary(uniqueKeysWithValues: archive.plans.map { ($0.id, $0) })

        for occurrence in occurrences {
            guard let plan = plans[occurrence.planID] else { continue }
            let matched = matchedEvents(for: occurrence, in: archive.doses)
            let total = matched.reduce(Decimal.zero) { $0 + $1.amount }
            if total < occurrence.amount,
               now >= occurrence.at.addingTimeInterval(TimeInterval(plan.followUpMinutes * 60)) {
                findings.append(AuditFinding(id: "unconfirmed:\(occurrence.id)",
                    medicationID: occurrence.medicationID, title: "服药尚未确认",
                    detail: "计划 \(occurrence.at.formatted(date: .abbreviated, time: .shortened))；\(total.doseText)/\(occurrence.amount.doseText) 已记录。"))
            }
            if total > occurrence.amount {
                findings.append(AuditFinding(id: "amount:\(occurrence.id)",
                    medicationID: occurrence.medicationID, title: "记录剂量超过该次计划",
                    detail: "计划 \(occurrence.amount.doseText)，记录 \(total.doseText)。"))
            }
        }

        for event in archive.doses where !event.isVoided && event.takenAt >= start && event.takenAt <= now {
            guard let planID = event.planID, let plan = plans[planID],
                  let due = event.scheduledAt else { continue }
            if let minutes = plan.earlyMinutes,
               event.takenAt < due.addingTimeInterval(TimeInterval(-minutes * 60)) {
                findings.append(AuditFinding(id: "early:\(event.id)",
                    medicationID: event.medicationID, title: "早于医嘱时间窗口",
                    detail: "实际 \(event.takenAt.formatted(date: .abbreviated, time: .shortened))，计划 \(due.formatted(date: .abbreviated, time: .shortened))。"))
            }
            if let minutes = plan.lateMinutes,
               event.takenAt > due.addingTimeInterval(TimeInterval(minutes * 60)) {
                findings.append(AuditFinding(id: "late:\(event.id)",
                    medicationID: event.medicationID, title: "晚于医嘱时间窗口",
                    detail: "实际 \(event.takenAt.formatted(date: .abbreviated, time: .shortened))，计划 \(due.formatted(date: .abbreviated, time: .shortened))。"))
            }
            if let cycle = plan.cycle,
               !cycleAllows(cycle, on: event.takenAt, calendar: calendar) {
                findings.append(AuditFinding(id: "cycle:\(event.id)",
                    medicationID: event.medicationID, title: "停药阶段存在服药记录",
                    detail: "实际 \(event.takenAt.formatted(date: .abbreviated, time: .shortened))，请核对医嘱和实际时间。"))
            }
        }

        for event in archive.doses where !event.isVoided && event.planID == nil &&
              event.takenAt >= start && event.takenAt <= now {
            let applicable = archive.plans.filter {
                $0.medicationID == event.medicationID &&
                $0.activeFrom <= event.takenAt &&
                ($0.activeUntil == nil || event.takenAt < $0.activeUntil!)
            }
            if applicable.count == 1,
               let cycle = applicable[0].cycle,
               !cycleAllows(cycle, on: event.takenAt, calendar: calendar) {
                findings.append(AuditFinding(id: "cycle-extra:\(event.id)",
                    medicationID: event.medicationID,
                    title: "停药阶段存在未归属服药记录",
                    detail: "实际 \(event.takenAt.formatted(date: .abbreviated, time: .shortened))，请核对。"))
            }
        }

        for medication in archive.medications {
            let history = archive.doses.filter {
                !$0.isVoided && $0.medicationID == medication.id && $0.takenAt <= now
            }.sorted { $0.takenAt < $1.takenAt }
            for pair in zip(history, history.dropFirst()) {
                let previous = pair.0
                let current = pair.1
                guard current.takenAt >= start,
                      let planID = current.planID,
                      let plan = plans[planID] else { continue }
                let elapsed = current.takenAt.timeIntervalSince(previous.takenAt)
                if let minimum = plan.minimumGapHours,
                   elapsed < TimeInterval(minimum * 3600) {
                    findings.append(AuditFinding(id: "gap:\(current.id)",
                        medicationID: medication.id, title: "服药间隔短于已录入下限",
                        detail: "上次 \(previous.takenAt.formatted(date: .abbreviated, time: .shortened))；本次 \(current.takenAt.formatted(date: .abbreviated, time: .shortened))。"))
                }
                if case .interval(let hours, _) = plan.rule,
                   previous.planID == current.planID,
                   abs(elapsed - TimeInterval(hours * 3600)) > 60 {
                    findings.append(AuditFinding(id: "interval:\(current.id)",
                        medicationID: medication.id, title: "实际间隔与计划不同，请核对",
                        detail: "计划间隔 \(hours) 小时；两次记录相距 \(Int(elapsed / 60)) 分钟。"))
                }
            }
        }
        return findings.sorted { $0.title < $1.title }
    }
}
```

## DoseLog/ReminderCoordinator.swift

```swift
import Combine
import Foundation
import UserNotifications

@MainActor
final class ReminderCoordinator: ObservableObject {
    @Published private(set) var status = "通知尚未启用"
    private let center = UNUserNotificationCenter.current()
    private let prefix = "DoseLog."
    private var isSyncing = false
    private var queuedArchive: MedicationArchive?

    func enable(using archive: MedicationArchive) async {
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            status = granted ? "正在安排提醒" : "通知未获授权；请在系统设置中开启"
            if granted {
                await sync(archive)
                for medication in archive.medications where medication.stockKnown &&
                      PlanEngine.balance(for: medication.id, archive: archive) < medication.lowStock {
                    await notifyLowStock(for: medication)
                }
            }
        } catch {
            status = "通知授权失败，请在系统设置中检查"
        }
    }

    func sync(_ archive: MedicationArchive, now: Date = Date()) async {
        if isSyncing {
            queuedArchive = archive
            return
        }
        isSyncing = true
        defer {
            isSyncing = false
            if let next = queuedArchive {
                queuedArchive = nil
                Task { await sync(next) }
            }
        }
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized ||
              settings.authorizationStatus == .provisional else {
            status = "通知未启用；记录功能仍可使用"
            return
        }

        let end = now.addingTimeInterval(7 * 24 * 3600)
        let due = PlanEngine.scheduled(plans: archive.plans,
            from: now.addingTimeInterval(-24 * 3600), through: end)
        let medicationByID = Dictionary(uniqueKeysWithValues:
            archive.medications.map { ($0.id, $0) })
        let planByID = Dictionary(uniqueKeysWithValues:
            archive.plans.map { ($0.id, $0) })
        var wanted: [DatedRequest] = []
        for occurrence in due {
            guard let medication = medicationByID[occurrence.medicationID],
                  !medication.isArchived,
                  let plan = planByID[occurrence.planID],
                  PlanEngine.matchedEvents(for: occurrence, in: archive.doses)
                    .reduce(Decimal.zero, { $0 + $1.amount }) < occurrence.amount else { continue }
            let atDue = content(title: "用药提醒", body: "请打开 App 查看本次待服药品。")
            if occurrence.at > now {
                wanted.append(request(id: "due:\(occurrence.id)", at: occurrence.at,
                                      content: atDue, now: now))
            }
            let followUp = occurrence.at.addingTimeInterval(TimeInterval(plan.followUpMinutes * 60))
            if plan.followUpMinutes > 0 && followUp > now && followUp <= end {
                wanted.append(request(id: "follow:\(occurrence.id)", at: followUp,
                    content: content(title: "服药尚未确认",
                        body: "若已服用，请打开 App 补记实际时间。"), now: now))
            }
        }

        // Leave room for other app notifications; expose the actual coverage below.
        wanted.sort { $0.triggerDate < $1.triggerDate }
        let selected = Array(wanted.prefix(48))
        let pendingBefore = await center.pendingNotificationRequests()
        let old = pendingBefore.filter {
                $0.identifier.hasPrefix(prefix + "due:") ||
                $0.identifier.hasPrefix(prefix + "follow:")
            }
        let desiredIDs = Set(selected.map(\.identifier))
        var failures = 0
        for notification in selected {
            do { try await center.add(notification.request) }
            catch { failures += 1 }
        }
        center.removePendingNotificationRequests(withIdentifiers:
            old.map(\.identifier).filter { !desiredIDs.contains($0) })
        let pendingAfter = await center.pendingNotificationRequests()
        let present = Set(pendingAfter.map(\.identifier))
        let confirmed = selected.filter { present.contains($0.identifier) }
        if failures > 0 {
            status = "有 \(failures) 条提醒安排失败，请重新打开 App 检查。"
        } else if confirmed.count < selected.count {
            status = "仅核实到 \(confirmed.count)/\(selected.count) 条待处理提醒，请检查系统通知设置。"
        } else if let last = confirmed.map({ $0.triggerDate }).max() {
            status = "已核实 \(confirmed.count) 条待处理提醒，覆盖至 \(last.formatted(date: .abbreviated, time: .shortened))"
        } else {
            status = "目前没有未来七天的待服提醒"
        }
    }

    func notifyLowStock(for medication: Medication) async {
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized else { return }
        let body = "有药品库存低于设置的安全阈值，请打开 App 核对。"
        let alert = request(id: "stock:\(medication.id.uuidString)",
            at: Date().addingTimeInterval(2),
            content: content(title: "库存不足", body: body), now: Date())
        try? await center.add(alert.request)
    }

    private func content(title: String, body: String) -> UNMutableNotificationContent {
        let value = UNMutableNotificationContent()
        value.title = title
        value.body = body
        value.sound = .default
        return value
    }

    private func request(id: String, at time: Date,
                         content: UNMutableNotificationContent,
                         now: Date) -> DatedRequest {
        let delay = max(1, time.timeIntervalSince(now))
        let request = UNNotificationRequest(identifier: prefix + id,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false))
        return DatedRequest(request: request, triggerDate: time)
    }

    private struct DatedRequest {
        var request: UNNotificationRequest
        var triggerDate: Date
        var identifier: String { request.identifier }
    }
}
```

## DoseLogTests/RecordStoreTests.swift

```swift
import XCTest
@testable import DoseLog

final class RecordStoreTests: XCTestCase {
    @MainActor
    private func withStore(_ test: (RecordStore, URL) throws -> Void) throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("DoseLogTests-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("records.json")
        try test(RecordStore(fileURL: url), url)
    }

    private func date(_ text: String) -> Date {
        ISO8601DateFormatter().date(from: text)!
    }

    private func plan(medicationID: UUID, at time: Date, amount: Decimal = 1,
                      gap: Int? = nil) -> DosePlan {
        DosePlan(medicationID: medicationID, amount: amount,
            rule: .interval(hours: 24, anchor: time), cycle: nil,
            activeFrom: time, activeUntil: nil,
            earlyMinutes: 0, lateMinutes: 0, minimumGapHours: gap,
            directionSource: "测试专用医嘱", followUpMinutes: 30)
    }

    @MainActor
    func testDoseIdentifiesMedicationAmountTimeAndPersistsStock() throws {
        try withStore { store, url in
            let at = date("2026-10-20T08:00:00Z")
            XCTAssertTrue(store.addMedication(name: "药品 A", strength: "规格 A",
                unit: .tablet, opening: 10, lowStock: 2, now: at))
            let med = try XCTUnwrap(store.archive.medications.first)
            let order = plan(medicationID: med.id, at: at)
            XCTAssertTrue(store.addPlan(order))
            let occurrence = try XCTUnwrap(PlanEngine.scheduled(
                plans: store.archive.plans, from: at, through: at).first)
            XCTAssertTrue(store.record(medicationID: med.id, amount: 1,
                takenAt: at, scheduled: occurrence, enteredAt: at))
            let reopened = RecordStore(fileURL: url)
            let saved = try XCTUnwrap(reopened.archive.doses.first)
            XCTAssertEqual(saved.medicationID, med.id)
            XCTAssertEqual(saved.amount, 1)
            XCTAssertEqual(saved.takenAt, at)
            XCTAssertEqual(saved.planID, order.id)
            XCTAssertEqual(reopened.balance(for: med.id), 9)
        }
    }

    @MainActor
    func testVoidingOneOfTwoSameTimeEventsRestoresOneUnit() throws {
        try withStore { store, _ in
            let at = date("2026-10-20T08:00:00Z")
            XCTAssertTrue(store.addMedication(name: "A", strength: "",
                unit: .tablet, opening: 4, lowStock: 1, now: at))
            let med = try XCTUnwrap(store.archive.medications.first)
            XCTAssertTrue(store.record(medicationID: med.id, amount: 1,
                takenAt: at, enteredAt: at))
            XCTAssertTrue(store.record(medicationID: med.id, amount: 1,
                takenAt: at, enteredAt: at))
            XCTAssertEqual(store.balance(for: med.id), 2)
            XCTAssertEqual(Set(store.archive.doses.map(\.id)).count, 2)
            XCTAssertTrue(store.voidDose(store.archive.doses[0].id))
            XCTAssertEqual(store.balance(for: med.id), 3)
            XCTAssertEqual(store.archive.doses.filter { !$0.isVoided }.count, 1)
        }
    }

    @MainActor
    func testUnknownStockAndCountThenReplenishment() throws {
        try withStore { store, _ in
            let at = date("2026-10-20T08:00:00Z")
            XCTAssertTrue(store.addMedication(name: "A", strength: "",
                unit: .millilitre, opening: nil, lowStock: 5, now: at))
            let med = try XCTUnwrap(store.archive.medications.first)
            XCTAssertFalse(med.stockKnown)
            XCTAssertFalse(store.addStock(medicationID: med.id, delta: 2,
                reason: .replenishment, at: at))
            XCTAssertTrue(store.countStock(medicationID: med.id, counted: 7.5, at: at))
            XCTAssertTrue(store.addStock(medicationID: med.id, delta: 2.5,
                reason: .replenishment, at: at))
            XCTAssertEqual(store.balance(for: med.id), 10)
        }
    }

    @MainActor
    func testBackfillBeforeCountDoesNotDoubleDeductOrRestore() throws {
        try withStore { store, _ in
            let earlier = date("2026-10-19T08:00:00Z")
            let countAt = date("2026-10-20T08:00:00Z")
            XCTAssertTrue(store.addMedication(name: "A", strength: "",
                unit: .tablet, opening: 10, lowStock: 2, now: earlier))
            let med = try XCTUnwrap(store.archive.medications.first)
            XCTAssertTrue(store.countStock(medicationID: med.id, counted: 7, at: countAt))
            XCTAssertTrue(store.record(medicationID: med.id, amount: 1,
                takenAt: earlier, enteredAt: countAt.addingTimeInterval(60), backfilled: true))
            XCTAssertEqual(store.balance(for: med.id), 7)
            XCTAssertTrue(store.voidDose(store.archive.doses[0].id))
            XCTAssertEqual(store.balance(for: med.id), 7)
        }
    }

    @MainActor
    func testLowStockOnlyOnThresholdCrossing() throws {
        try withStore { store, _ in
            let at = date("2026-10-20T08:00:00Z")
            XCTAssertTrue(store.addMedication(name: "A", strength: "",
                unit: .tablet, opening: 3, lowStock: 2, now: at))
            let med = try XCTUnwrap(store.archive.medications.first)
            XCTAssertTrue(store.record(medicationID: med.id, amount: 2,
                takenAt: at, enteredAt: at))
            XCTAssertEqual(store.newLowStock.map(\.id), [med.id])
            XCTAssertTrue(store.record(medicationID: med.id, amount: 1,
                takenAt: at, enteredAt: at))
            XCTAssertTrue(store.newLowStock.isEmpty)
        }
    }

    @MainActor
    func testMedicineIdentityIsLockedAfterDoseButThresholdCanChange() throws {
        try withStore { store, _ in
            let at = date("2026-10-20T08:00:00Z")
            XCTAssertTrue(store.addMedication(name: "A", strength: "规格1",
                unit: .tablet, opening: 5, lowStock: 1, now: at))
            let med = try XCTUnwrap(store.archive.medications.first)
            XCTAssertTrue(store.record(medicationID: med.id, amount: 1,
                takenAt: at, enteredAt: at))
            XCTAssertFalse(store.updateMedication(med.id, name: "B",
                strength: "规格2", lowStock: 2))
            XCTAssertTrue(store.updateMedication(med.id, name: "A",
                strength: "规格1", lowStock: 6))
            XCTAssertEqual(store.archive.medications[0].name, "A")
            XCTAssertEqual(store.newLowStock.map(\.id), [med.id])
        }
    }

    @MainActor
    func testLegacyTimesRemainUnassignedAfterUpgrade() throws {
        try withStore { store, url in
            let at = date("2026-10-20T08:00:00Z")
            let old = LegacyArchive(version: 1,
                records: [LegacyTimeRecord(id: UUID(), takenAt: at)])
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .millisecondsSince1970
            try encoder.encode(old).write(to: url)
            store.reload()
            XCTAssertTrue(store.canEdit)
            XCTAssertEqual(store.archive.legacyUnassigned.count, 1)
            XCTAssertTrue(store.archive.doses.isEmpty)
            XCTAssertTrue(store.addMedication(name: "B", strength: "",
                unit: .capsule, opening: 3, lowStock: 1, now: at))
            let reopened = RecordStore(fileURL: url)
            XCTAssertEqual(reopened.archive.legacyUnassigned.count, 1)
            XCTAssertTrue(reopened.archive.doses.isEmpty)
        }
    }

    @MainActor
    func testCorruptArchiveIsNotOverwritten() throws {
        try withStore { store, url in
            let original = Data("invalid data".utf8)
            try original.write(to: url)
            store.reload()
            XCTAssertFalse(store.canEdit)
            XCTAssertFalse(store.addMedication(name: "A", strength: "",
                unit: .tablet, opening: 5, lowStock: 1))
            XCTAssertEqual(try Data(contentsOf: url), original)
        }
    }

    func testCycleAndIntervalGenerateExactOccurrences() {
        let at = date("2026-10-20T08:00:00Z")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var order = plan(medicationID: UUID(), at: at)
        order.cycle = CycleRule(onDays: 1, offDays: 1, anchor: at)
        let result = PlanEngine.scheduled(plans: [order], from: at,
            through: at.addingTimeInterval(3 * 86400), calendar: calendar)
        XCTAssertEqual(result.map(\.at), [at, at.addingTimeInterval(2 * 86400)])
    }

    func testDaylightSavingUsesCalendarDaysForDailyRule() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        let anchor = date("2026-03-07T08:00:00Z")
        var order = plan(medicationID: UUID(), at: anchor)
        order.rule = .daily(minutesOfDay: [8 * 60])
        let dates = PlanEngine.scheduled(plans: [order], from: anchor,
            through: anchor.addingTimeInterval(3 * 86400), calendar: calendar)
            .map(\.at)
        XCTAssertEqual(dates.count, 3)
        XCTAssertEqual(dates[1].timeIntervalSince(dates[0]), 23 * 3600)
    }

    func testSevenDayAuditIncludesPreWindowEventForGap() {
        let now = date("2026-10-20T12:00:00Z")
        let earlier = now.addingTimeInterval(-7 * 86400 - 1800)
        let later = now.addingTimeInterval(-7 * 86400 + 1800)
        let med = Medication(name: "A", strength: "", unit: .tablet, lowStock: 0)
        let order = plan(medicationID: med.id, at: earlier, gap: 2)
        var archive = MedicationArchive()
        archive.medications = [med]
        archive.plans = [order]
        archive.doses = [
            DoseEvent(medicationID: med.id, amount: 1,
                takenAt: earlier, enteredAt: earlier, planID: order.id,
                scheduledAt: earlier, isBackfilled: false),
            DoseEvent(medicationID: med.id, amount: 1,
                takenAt: later, enteredAt: later, planID: order.id,
                scheduledAt: later, isBackfilled: false)
        ]
        XCTAssertTrue(PlanEngine.audit(archive, now: now)
            .contains { $0.id.hasPrefix("gap:") })
    }

    func testUnconfirmedIsNotLabeledConfirmedMissedDose() {
        let at = date("2026-10-20T08:00:00Z")
        let med = Medication(name: "A", strength: "", unit: .tablet, lowStock: 0)
        var archive = MedicationArchive()
        archive.medications = [med]
        archive.plans = [plan(medicationID: med.id, at: at)]
        let result = PlanEngine.audit(archive, now: at.addingTimeInterval(31 * 60))
        XCTAssertTrue(result.contains { $0.title == "服药尚未确认" })
        XCTAssertFalse(result.contains { $0.title.contains("确认漏服") })
    }

    func testExtraDoseInOffCycleIsFlaggedWithoutInventingPlanMatch() {
        let at = date("2026-10-20T08:00:00Z")
        let nextDay = at.addingTimeInterval(86400)
        let med = Medication(name: "A", strength: "", unit: .tablet, lowStock: 0)
        var order = plan(medicationID: med.id, at: at)
        order.cycle = CycleRule(onDays: 1, offDays: 1, anchor: at)
        var archive = MedicationArchive()
        archive.medications = [med]
        archive.plans = [order]
        archive.doses = [DoseEvent(medicationID: med.id, amount: 1,
            takenAt: nextDay, enteredAt: nextDay, planID: nil,
            scheduledAt: nil, isBackfilled: false)]
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        XCTAssertTrue(PlanEngine.audit(archive,
            now: nextDay.addingTimeInterval(60), calendar: calendar)
            .contains { $0.id.hasPrefix("cycle-extra:") })
        XCTAssertNil(archive.doses[0].planID)
    }
}
```

## DoseLog.xcodeproj/project.pbxproj

```text
// !$*UTF8*$!
{
	archiveVersion = 1;
	classes = {};
	objectVersion = 56;
	objects = {

/* Begin PBXBuildFile section */
		B10000000000000000000001 /* DoseLogApp.swift in Sources */ = {isa = PBXBuildFile; fileRef = F10000000000000000000001 /* DoseLogApp.swift */; };
		B10000000000000000000002 /* DoseRecord.swift in Sources */ = {isa = PBXBuildFile; fileRef = F10000000000000000000002 /* DoseRecord.swift */; };
		B10000000000000000000003 /* RecordStore.swift in Sources */ = {isa = PBXBuildFile; fileRef = F10000000000000000000003 /* RecordStore.swift */; };
		B10000000000000000000004 /* ContentView.swift in Sources */ = {isa = PBXBuildFile; fileRef = F10000000000000000000004 /* ContentView.swift */; };
		B10000000000000000000005 /* RecordStoreTests.swift in Sources */ = {isa = PBXBuildFile; fileRef = F10000000000000000000005 /* RecordStoreTests.swift */; };
		B10000000000000000000008 /* PlanEngine.swift in Sources */ = {isa = PBXBuildFile; fileRef = F10000000000000000000008 /* PlanEngine.swift */; };
		B10000000000000000000009 /* ReminderCoordinator.swift in Sources */ = {isa = PBXBuildFile; fileRef = F10000000000000000000009 /* ReminderCoordinator.swift */; };
/* End PBXBuildFile section */

/* Begin PBXContainerItemProxy section */
		D10000000000000000000001 = {
			isa = PBXContainerItemProxy;
			containerPortal = A10000000000000000000000;
			proxyType = 1;
			remoteGlobalIDString = A10000000000000000000001;
			remoteInfo = DoseLog;
		};
/* End PBXContainerItemProxy section */

/* Begin PBXFileReference section */
		F10000000000000000000001 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = DoseLogApp.swift; sourceTree = "<group>"; };
		F10000000000000000000002 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = DoseRecord.swift; sourceTree = "<group>"; };
		F10000000000000000000003 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = RecordStore.swift; sourceTree = "<group>"; };
		F10000000000000000000004 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ContentView.swift; sourceTree = "<group>"; };
		F10000000000000000000005 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = RecordStoreTests.swift; sourceTree = "<group>"; };
		F10000000000000000000006 = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = DoseLog.app; sourceTree = BUILT_PRODUCTS_DIR; };
		F10000000000000000000007 = {isa = PBXFileReference; explicitFileType = wrapper.cfbundle; includeInIndex = 0; path = DoseLogTests.xctest; sourceTree = BUILT_PRODUCTS_DIR; };
		F10000000000000000000008 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = PlanEngine.swift; sourceTree = "<group>"; };
		F10000000000000000000009 = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ReminderCoordinator.swift; sourceTree = "<group>"; };
/* End PBXFileReference section */

/* Begin PBXFrameworksBuildPhase section */
		E10000000000000000000002 = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
		E10000000000000000000005 = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
		C10000000000000000000000 = {
			isa = PBXGroup;
			children = (C10000000000000000000001, C10000000000000000000002, C10000000000000000000003,);
			sourceTree = "<group>";
		};
		C10000000000000000000001 = {
			isa = PBXGroup;
			children = (F10000000000000000000001, F10000000000000000000002, F10000000000000000000003, F10000000000000000000004, F10000000000000000000008, F10000000000000000000009,);
			path = DoseLog;
			sourceTree = "<group>";
		};
		C10000000000000000000002 = {
			isa = PBXGroup;
			children = (F10000000000000000000005,);
			path = DoseLogTests;
			sourceTree = "<group>";
		};
		C10000000000000000000003 = {
			isa = PBXGroup;
			children = (F10000000000000000000006, F10000000000000000000007,);
			name = Products;
			sourceTree = "<group>";
		};
/* End PBXGroup section */

/* Begin PBXNativeTarget section */
		A10000000000000000000001 = {
			isa = PBXNativeTarget;
			buildConfigurationList = A20000000000000000000001;
			buildPhases = (E10000000000000000000001, E10000000000000000000002, E10000000000000000000003,);
			buildRules = ();
			dependencies = ();
			name = DoseLog;
			productName = DoseLog;
			productReference = F10000000000000000000006;
			productType = "com.apple.product-type.application";
		};
		A10000000000000000000002 = {
			isa = PBXNativeTarget;
			buildConfigurationList = A20000000000000000000002;
			buildPhases = (E10000000000000000000004, E10000000000000000000005, E10000000000000000000006,);
			buildRules = ();
			dependencies = (D10000000000000000000002,);
			name = DoseLogTests;
			productName = DoseLogTests;
			productReference = F10000000000000000000007;
			productType = "com.apple.product-type.bundle.unit-test";
		};
/* End PBXNativeTarget section */

/* Begin PBXProject section */
		A10000000000000000000000 = {
			isa = PBXProject;
			attributes = {
				BuildIndependentTargetsInParallel = YES;
				LastSwiftUpdateCheck = 1500;
				LastUpgradeCheck = 1500;
				TargetAttributes = {
					A10000000000000000000001 = {CreatedOnToolsVersion = 15.0; ProvisioningStyle = Automatic; };
					A10000000000000000000002 = {CreatedOnToolsVersion = 15.0; ProvisioningStyle = Automatic; TestTargetID = A10000000000000000000001; };
				};
			};
			buildConfigurationList = A20000000000000000000000;
			compatibilityVersion = "Xcode 14.0";
			developmentRegion = "zh-Hans";
			hasScannedForEncodings = 0;
			knownRegions = ("zh-Hans", en, Base,);
			mainGroup = C10000000000000000000000;
			productRefGroup = C10000000000000000000003;
			projectDirPath = "";
			projectRoot = "";
			targets = (A10000000000000000000001, A10000000000000000000002,);
		};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
		E10000000000000000000003 = {isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
		E10000000000000000000006 = {isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
		E10000000000000000000001 = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (B10000000000000000000001, B10000000000000000000002, B10000000000000000000003, B10000000000000000000004, B10000000000000000000008, B10000000000000000000009,); runOnlyForDeploymentPostprocessing = 0; };
		E10000000000000000000004 = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (B10000000000000000000005,); runOnlyForDeploymentPostprocessing = 0; };
/* End PBXSourcesBuildPhase section */

/* Begin PBXTargetDependency section */
		D10000000000000000000002 = {isa = PBXTargetDependency; target = A10000000000000000000001; targetProxy = D10000000000000000000001; };
/* End PBXTargetDependency section */

/* Begin XCBuildConfiguration section */
		A30000000000000000000001 = {
			isa = XCBuildConfiguration;
			buildSettings = {
				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				DEBUG_INFORMATION_FORMAT = dwarf;
				ENABLE_TESTABILITY = YES;
				GCC_C_LANGUAGE_STANDARD = gnu17;
				GCC_OPTIMIZATION_LEVEL = 0;
				GCC_PREPROCESSOR_DEFINITIONS = ("DEBUG=1", "$(inherited)",);
				IPHONEOS_DEPLOYMENT_TARGET = 16.0;
				ONLY_ACTIVE_ARCH = YES;
				SDKROOT = iphoneos;
				SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;
				SWIFT_OPTIMIZATION_LEVEL = "-Onone";
			};
			name = Debug;
		};
		A30000000000000000000002 = {
			isa = XCBuildConfiguration;
			buildSettings = {
				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";
				GCC_C_LANGUAGE_STANDARD = gnu17;
				IPHONEOS_DEPLOYMENT_TARGET = 16.0;
				SDKROOT = iphoneos;
				SWIFT_COMPILATION_MODE = wholemodule;
				SWIFT_OPTIMIZATION_LEVEL = "-O";
				VALIDATE_PRODUCT = YES;
			};
			name = Release;
		};
		A30000000000000000000003 = {
			isa = XCBuildConfiguration;
			buildSettings = {
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				DEVELOPMENT_TEAM = "";
				GENERATE_INFOPLIST_FILE = YES;
				INFOPLIST_KEY_CFBundleDisplayName = "吃药记录";
				INFOPLIST_KEY_LSApplicationCategoryType = "public.app-category.healthcare-fitness";
				INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES;
				INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents = YES;
				INFOPLIST_KEY_UILaunchScreen_Generation = YES;
				INFOPLIST_KEY_UISupportedInterfaceOrientations = UIInterfaceOrientationPortrait;
				LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks",);
				MARKETING_VERSION = 1.0;
				PRODUCT_BUNDLE_IDENTIFIER = com.example.personal.DoseLog;
				PRODUCT_NAME = "$(TARGET_NAME)";
				SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";
				SUPPORTS_MACCATALYST = NO;
				SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = NO;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = 1;
			};
			name = Debug;
		};
		A30000000000000000000004 = {
			isa = XCBuildConfiguration;
			buildSettings = {
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				DEVELOPMENT_TEAM = "";
				GENERATE_INFOPLIST_FILE = YES;
				INFOPLIST_KEY_CFBundleDisplayName = "吃药记录";
				INFOPLIST_KEY_LSApplicationCategoryType = "public.app-category.healthcare-fitness";
				INFOPLIST_KEY_UIApplicationSceneManifest_Generation = YES;
				INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents = YES;
				INFOPLIST_KEY_UILaunchScreen_Generation = YES;
				INFOPLIST_KEY_UISupportedInterfaceOrientations = UIInterfaceOrientationPortrait;
				LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks",);
				MARKETING_VERSION = 1.0;
				PRODUCT_BUNDLE_IDENTIFIER = com.example.personal.DoseLog;
				PRODUCT_NAME = "$(TARGET_NAME)";
				SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";
				SUPPORTS_MACCATALYST = NO;
				SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = NO;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = 1;
			};
			name = Release;
		};
		A30000000000000000000005 = {
			isa = XCBuildConfiguration;
			buildSettings = {
				BUNDLE_LOADER = "$(TEST_HOST)";
				CODE_SIGN_STYLE = Automatic;
				DEVELOPMENT_TEAM = "";
				GENERATE_INFOPLIST_FILE = YES;
				LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks",);
				PRODUCT_BUNDLE_IDENTIFIER = com.example.personal.DoseLogTests;
				PRODUCT_NAME = "$(TARGET_NAME)";
				SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = 1;
				TEST_HOST = "$(BUILT_PRODUCTS_DIR)/DoseLog.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/DoseLog";
			};
			name = Debug;
		};
		A30000000000000000000006 = {
			isa = XCBuildConfiguration;
			buildSettings = {
				BUNDLE_LOADER = "$(TEST_HOST)";
				CODE_SIGN_STYLE = Automatic;
				DEVELOPMENT_TEAM = "";
				GENERATE_INFOPLIST_FILE = YES;
				LD_RUNPATH_SEARCH_PATHS = ("$(inherited)", "@executable_path/Frameworks", "@loader_path/Frameworks",);
				PRODUCT_BUNDLE_IDENTIFIER = com.example.personal.DoseLogTests;
				PRODUCT_NAME = "$(TARGET_NAME)";
				SUPPORTED_PLATFORMS = "iphoneos iphonesimulator";
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = 1;
				TEST_HOST = "$(BUILT_PRODUCTS_DIR)/DoseLog.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/DoseLog";
			};
			name = Release;
		};
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
		A20000000000000000000000 = {isa = XCConfigurationList; buildConfigurations = (A30000000000000000000001, A30000000000000000000002,); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
		A20000000000000000000001 = {isa = XCConfigurationList; buildConfigurations = (A30000000000000000000003, A30000000000000000000004,); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
		A20000000000000000000002 = {isa = XCConfigurationList; buildConfigurations = (A30000000000000000000005, A30000000000000000000006,); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
/* End XCConfigurationList section */
	};
	rootObject = A10000000000000000000000;
}
```

## DoseLog.xcodeproj/xcshareddata/xcschemes/DoseLog.xcscheme

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1500" version="1.3">
   <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">
            <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="A10000000000000000000001" BuildableName="DoseLog.app" BlueprintName="DoseLog" ReferencedContainer="container:DoseLog.xcodeproj"/>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES">
      <Testables>
         <TestableReference skipped="NO">
            <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="A10000000000000000000002" BuildableName="DoseLogTests.xctest" BlueprintName="DoseLogTests" ReferencedContainer="container:DoseLog.xcodeproj"/>
         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES">
      <BuildableProductRunnable runnableDebuggingMode="0">
         <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="A10000000000000000000001" BuildableName="DoseLog.app" BlueprintName="DoseLog" ReferencedContainer="container:DoseLog.xcodeproj"/>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES">
      <BuildableProductRunnable runnableDebuggingMode="0">
         <BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="A10000000000000000000001" BuildableName="DoseLog.app" BlueprintName="DoseLog" ReferencedContainer="container:DoseLog.xcodeproj"/>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction buildConfiguration="Debug"/>
   <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
```

## .gitignore

```text
.DS_Store
DerivedData/
*.xcuserstate
xcuserdata/
*.xcresult
output/
```

## .github/workflows/build-iphone.yml

```yaml
name: Build iPhone app without a Mac

on:
  workflow_dispatch:
  push:
    branches: [main, master]
    paths:
      - 'DoseLog/**'
      - 'DoseLogTests/**'
      - 'DoseLog.xcodeproj/**'
      - '.github/workflows/build-iphone.yml'

permissions:
  contents: read

jobs:
  build-and-test:
    runs-on: macos-26
    timeout-minutes: 60
    steps:
      - name: Get source
        uses: actions/checkout@v4

      - name: Show build environment
        run: |
          xcodebuild -version
          xcrun --sdk iphoneos --show-sdk-version

      - name: Run existing tests in an iPhone simulator
        shell: bash
        run: |
          set -euo pipefail
          simulator_id="$(xcrun simctl list devices available -j | /usr/bin/python3 -c 'import json,sys; devices=json.load(sys.stdin)["devices"]; print(next(d["udid"] for group in devices.values() for d in group if d["name"].startswith("iPhone")))')"
          xcodebuild test \
            -project DoseLog.xcodeproj \
            -scheme DoseLog \
            -destination "platform=iOS Simulator,id=${simulator_id}" \
            -derivedDataPath "$RUNNER_TEMP/DoseLogSimulator" \
            CODE_SIGNING_ALLOWED=NO

      - name: Build an unsigned iPhone app
        shell: bash
        run: |
          set -euo pipefail
          xcodebuild build \
            -project DoseLog.xcodeproj \
            -scheme DoseLog \
            -configuration Release \
            -sdk iphoneos \
            -destination 'generic/platform=iOS' \
            -derivedDataPath "$RUNNER_TEMP/DoseLogDevice" \
            CODE_SIGNING_ALLOWED=NO

          app="$RUNNER_TEMP/DoseLogDevice/Build/Products/Release-iphoneos/DoseLog.app"
          test -d "$app"
          executable="$(/usr/libexec/PlistBuddy -c 'Print CFBundleExecutable' "$app/Info.plist")"
          file "$app/$executable"
          /usr/bin/lipo -archs "$app/$executable" | grep -qw arm64
          mkdir -p "$RUNNER_TEMP/package/Payload" output
          cp -R "$app" "$RUNNER_TEMP/package/Payload/DoseLog.app"
          (cd "$RUNNER_TEMP/package" && /usr/bin/zip -qry "$GITHUB_WORKSPACE/output/DoseLog-unsigned.ipa" Payload)
          shasum -a 256 output/DoseLog-unsigned.ipa

      - name: Make the iPhone installation file available
        uses: actions/upload-artifact@v4
        with:
          name: DoseLog-unsigned-iPhone
          path: output/DoseLog-unsigned.ipa
          if-no-files-found: error
          retention-days: 7
```
