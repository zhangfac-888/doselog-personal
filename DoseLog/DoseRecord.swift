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
