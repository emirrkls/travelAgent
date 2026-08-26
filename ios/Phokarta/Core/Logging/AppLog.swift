import os

enum AppLog {
    static let subsystem = "com.emirrkls.phokarta"
    static let network = Logger(subsystem: subsystem, category: "network")
    static let auth = Logger(subsystem: subsystem, category: "auth")
    static let session = Logger(subsystem: subsystem, category: "session")
}
