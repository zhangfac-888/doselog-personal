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
