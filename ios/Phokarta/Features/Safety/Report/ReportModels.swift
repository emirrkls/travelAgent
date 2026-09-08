import Foundation

struct ReportTarget: Equatable, Sendable {
    let targetType: ReportTargetType
    let targetId: UUID
    let displayContext: String

    static func user(id: UUID, displayName: String) -> ReportTarget {
        ReportTarget(targetType: .user, targetId: id, displayContext: displayName)
    }

    static func visit(id: UUID, placeName: String) -> ReportTarget {
        ReportTarget(targetType: .visit, targetId: id, displayContext: placeName)
    }
}
