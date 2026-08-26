import Foundation

enum AppConfigError: Error, Equatable, Sendable {
    case missingBaseURL
    case invalidURL(String)
    case insecureURLNotAllowed(String)
    case missingHost(String)
}

struct AppConfig: Equatable, Sendable {
    let apiBaseURL: URL
    let allowsInsecureHTTP: Bool

    var isPlaceholderHost: Bool {
        guard let host = apiBaseURL.host?.lowercased() else { return false }
        return host == "invalid" || host.hasSuffix(".invalid")
    }

    static func fromBundle(_ bundle: Bundle = .main) throws -> AppConfig {
        let raw = (bundle.object(forInfoDictionaryKey: "PHOKARTA_API_BASE_URL") as? String) ?? ""
        let allows = parseAllowsInsecureHTTP(
            bundle.object(forInfoDictionaryKey: "PHOKARTA_ALLOWS_INSECURE_HTTP") as? String
        )
        return try parse(baseURLString: raw, allowsInsecureHTTP: allows)
    }

    static func parse(baseURLString: String, allowsInsecureHTTP: Bool) throws -> AppConfig {
        let trimmed = baseURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw AppConfigError.missingBaseURL }

        let normalized = Self.normalizedBaseURLString(trimmed)
        guard let url = URL(string: normalized), url.scheme != nil else {
            throw AppConfigError.invalidURL(trimmed)
        }
        guard url.host != nil, url.host?.isEmpty == false else {
            throw AppConfigError.missingHost(trimmed)
        }

        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "https" {
            return AppConfig(apiBaseURL: url, allowsInsecureHTTP: allowsInsecureHTTP)
        }
        if scheme == "http" {
            if allowsInsecureHTTP {
                return AppConfig(apiBaseURL: url, allowsInsecureHTTP: true)
            }
            throw AppConfigError.insecureURLNotAllowed(trimmed)
        }
        throw AppConfigError.invalidURL(trimmed)
    }

    static func normalizedBaseURLString(_ raw: String) -> String {
        raw.hasSuffix("/") ? raw : raw + "/"
    }

    private static func parseAllowsInsecureHTTP(_ raw: String?) -> Bool {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "YES", "TRUE", "1":
            return true
        default:
            return false
        }
    }
}
