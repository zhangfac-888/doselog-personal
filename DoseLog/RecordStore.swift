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
