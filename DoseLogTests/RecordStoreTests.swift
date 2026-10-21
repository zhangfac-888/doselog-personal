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
