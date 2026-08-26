import Foundation

struct APIClient: Sendable {
    let config: AppConfig
    let transport: any HTTPTransport
    let authRetry: (any AuthRetryHandling)?

    init(
        config: AppConfig,
        transport: any HTTPTransport,
        authRetry: (any AuthRetryHandling)? = nil
    ) {
        self.config = config
        self.transport = transport
        self.authRetry = authRetry
    }

    func send<E: APIEndpoint>(_ endpoint: E) async throws -> E.Response {
        try await send(endpoint, retryOnUnauthorized: endpoint.allowsRetryAfterRefresh)
    }

    func sendWithoutRetry<E: APIEndpoint>(_ endpoint: E) async throws -> E.Response {
        try await send(endpoint, retryOnUnauthorized: false)
    }

    private func send<E: APIEndpoint>(
        _ endpoint: E,
        retryOnUnauthorized: Bool
    ) async throws -> E.Response {
        try Task.checkCancellation()
        if config.isPlaceholderHost {
            throw AppError.placeholderAPI
        }

        var request = try makeRequest(endpoint)
        let originalAccess: String?
        if endpoint.requiresAuthentication {
            guard let retry = authRetry, let token = await retry.accessToken(), !token.isEmpty else {
                throw AppError.unauthorized
            }
            originalAccess = token
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else {
            originalAccess = nil
        }

        logRequest(request)
        let (data, response) = try await perform(request)
        logResponse(response, path: endpoint.path)

        if response.statusCode == 401,
           retryOnUnauthorized,
           endpoint.requiresAuthentication,
           let retry = authRetry {
            let newAccess = try await retry.refreshAfterUnauthorized(failedAccessToken: originalAccess)
            var retryRequest = request
            retryRequest.setValue("Bearer \(newAccess)", forHTTPHeaderField: "Authorization")
            logRequest(retryRequest, note: "retry-once")
            let (retryData, retryResponse) = try await perform(retryRequest)
            logResponse(retryResponse, path: endpoint.path)
            return try decode(endpoint, data: retryData, response: retryResponse)
        }

        return try decode(endpoint, data: data, response: response)
    }

    func makeRequest<E: APIEndpoint>(_ endpoint: E) throws -> URLRequest {
        guard var components = URLComponents(url: config.apiBaseURL, resolvingAgainstBaseURL: false) else {
            throw AppError.invalidConfiguration("API base URL is not valid")
        }
        let relative = endpoint.path.hasPrefix("/") ? String(endpoint.path.dropFirst()) : endpoint.path
        let basePath = components.path.hasSuffix("/") ? components.path : components.path + "/"
        components.path = basePath + relative
        if !endpoint.queryItems.isEmpty {
            components.queryItems = endpoint.queryItems
        }
        guard let url = components.url else {
            throw AppError.invalidConfiguration("Unable to build request URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = endpoint.method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = endpoint.body {
            request.httpBody = try APIJSON.encoder.encode(body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            return try await transport.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as AppError {
            throw error
        } catch {
            throw AppError.networkUnavailable
        }
    }

    private func decode<E: APIEndpoint>(
        _ endpoint: E,
        data: Data,
        response: HTTPURLResponse
    ) throws -> E.Response {
        if (200..<300).contains(response.statusCode) {
            if E.Response.self == EmptyPayload.self {
                return EmptyPayload() as! E.Response
            }
            if data.isEmpty, response.statusCode == 204 {
                if E.Response.self == EmptyPayload.self {
                    return EmptyPayload() as! E.Response
                }
            }
            do {
                return try APIJSON.decoder.decode(E.Response.self, from: data)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                AppLog.network.error("decode failed status=\(response.statusCode, privacy: .public) path=\(endpoint.path, privacy: .public)")
                throw AppError.decoding
            }
        }

        let dto = APIErrorMapper.decodeErrorBody(data)
        if let requestId = dto?.requestId {
            AppLog.network.error(
                "api error status=\(response.statusCode, privacy: .public) code=\(dto?.code ?? "-", privacy: .public) requestId=\(requestId, privacy: .public) path=\(endpoint.path, privacy: .public)"
            )
        } else {
            AppLog.network.error(
                "api error status=\(response.statusCode, privacy: .public) code=\(dto?.code ?? "-", privacy: .public) path=\(endpoint.path, privacy: .public)"
            )
        }
        throw APIErrorMapper.map(status: response.statusCode, dto: dto)
    }

    private func logRequest(_ request: URLRequest, note: String = "") {
        let method = request.httpMethod ?? "?"
        let path = request.url?.path ?? "?"
        let suffix = note.isEmpty ? "" : " \(note)"
        AppLog.network.debug("request \(method, privacy: .public) \(path, privacy: .public)\(suffix, privacy: .public)")
    }

    private func logResponse(_ response: HTTPURLResponse, path: String) {
        let requestId = response.value(forHTTPHeaderField: "X-Request-Id") ?? "-"
        AppLog.network.debug(
            "response status=\(response.statusCode, privacy: .public) path=\(path, privacy: .public) requestId=\(requestId, privacy: .public)"
        )
    }
}
