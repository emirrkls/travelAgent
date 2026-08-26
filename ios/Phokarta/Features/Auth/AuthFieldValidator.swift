import Foundation

enum AuthFieldValidator {
    static func identifier(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed.count <= 320
    }

    static func email(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.count <= 320 && trimmed.contains("@") && trimmed.contains(".")
    }

    static func username(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (3...32).contains(trimmed.count) else { return false }
        return trimmed.range(of: "^[a-zA-Z0-9_]+$", options: .regularExpression) != nil
    }

    static func displayName(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return (1...100).contains(trimmed.count)
    }

    static func password(_ value: String) -> Bool {
        (8...72).contains(value.count)
    }
}
