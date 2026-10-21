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
