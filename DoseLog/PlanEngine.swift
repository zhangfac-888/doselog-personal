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
