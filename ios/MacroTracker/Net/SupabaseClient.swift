import Foundation

struct AuthSession: Codable, Hashable {
    var accessToken: String
    var refreshToken: String
    /// Epoch seconds.
    var expiresAt: Int64
    var userId: String
}

struct BackendError: LocalizedError {
    let message: String
    var status: Int = 0
    var errorDescription: String? { message }
}

struct HTTPResult {
    let status: Int
    let data: Data
    var isSuccessful: Bool { (200...299).contains(status) }
}

protocol SessionStoring: AnyObject {
    func loadSession() -> AuthSession?
    func saveSession(_ session: AuthSession?)
}

/// Minimal Supabase client: anonymous auth with token refresh, PostgREST RPC calls and Edge Function
/// invocation. Every install gets its own anonymous user, like on Android.
actor SupabaseClient {
    let baseURL: String
    let anonKey: String
    private let store: SessionStoring
    private let http: URLSession
    private var cached: AuthSession?
    private var renewing: Task<AuthSession, Error>?

    init(baseURL: String, anonKey: String, store: SessionStoring, http: URLSession = .shared) {
        self.baseURL = baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL
        self.anonKey = anonKey
        self.store = store
        self.http = http
    }

    /// Returns a valid session, creating an anonymous user on first use. Pass [rejectedToken] after a
    /// 401 to force a refresh (only if nobody refreshed meanwhile).
    func session(rejectedToken: String? = nil) async throws -> AuthSession {
        if let renewing { return try await renewing.value }
        let current = cached ?? store.loadSession()
        let now = Int64(Date().timeIntervalSince1970)
        if let current, rejectedToken != current.accessToken, current.expiresAt - now >= 60 {
            cached = current
            return current
        }
        let task = Task { () throws -> AuthSession in
            if let current { return try await self.refresh(current) }
            return try await self.signUpAnonymously()
        }
        renewing = task
        defer { renewing = nil }
        let session = try await task.value
        cached = session
        store.saveSession(session)
        return session
    }

    /// Puts back a session saved in a backup.
    func replaceSession(_ session: AuthSession) {
        cached = session
        store.saveSession(session)
    }

    /// Forgets the session (after deleting the account).
    func clearSession() {
        cached = nil
        store.saveSession(nil)
    }

    func userId() async throws -> String { try await session().userId }

    func rpc(_ function: String, _ args: [String: JSONValue] = [:]) async throws -> Data {
        let result = try await authorized(path: "/rest/v1/rpc/\(function)", body: JSONValue.object(args).data)
        guard result.isSuccessful else {
            throw BackendError(message: extractErrorMessage(result.data) ?? "Error del servidor (\(result.status))", status: result.status)
        }
        return result.data
    }

    func invokeFunction(_ name: String, body: Data) async throws -> HTTPResult {
        try await authorized(path: "/functions/v1/\(name)", body: body)
    }

    private func authorized(path: String, body: Data) async throws -> HTTPResult {
        var session = try await session()
        var result = try await send(path: path, body: body, token: session.accessToken)
        if result.status == 401 {
            session = try await self.session(rejectedToken: session.accessToken)
            result = try await send(path: path, body: body, token: session.accessToken)
        }
        return result
    }

    private func send(path: String, body: Data, token: String?) async throws -> HTTPResult {
        guard let url = URL(string: baseURL + path) else { throw BackendError(message: "Dirección del servidor inválida") }
        var request = URLRequest(url: url, timeoutInterval: 120)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(anonKey, forHTTPHeaderField: "apikey")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.httpBody = body
        do {
            let (data, response) = try await http.data(for: request)
            return HTTPResult(status: (response as? HTTPURLResponse)?.statusCode ?? 0, data: data)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw BackendError(message: "No se pudo conectar con el servidor. Revisa tu conexión.")
        }
    }

    private func signUpAnonymously() async throws -> AuthSession {
        let result = try await send(path: "/auth/v1/signup", body: JSONValue.object(["data": [:]]).data, token: nil)
        guard result.isSuccessful else {
            let detail = extractErrorMessage(result.data) ?? "código \(result.status)"
            let body = String(data: result.data, encoding: .utf8) ?? ""
            let hint = body.lowercased().contains("anonymous")
                ? " Activa \"Allow anonymous sign-ins\" en Supabase (Authentication → Sign In / Providers)." : ""
            throw BackendError(message: "No se pudo crear la sesión: \(detail).\(hint)", status: result.status)
        }
        return try parseSession(result.data)
    }

    private func refresh(_ current: AuthSession) async throws -> AuthSession {
        let body = JSONValue.object(["refresh_token": .string(current.refreshToken)]).data
        let result = try await send(path: "/auth/v1/token?grant_type=refresh_token", body: body, token: nil)
        if result.isSuccessful { return try parseSession(result.data) }
        // The refresh token is no longer valid: start over with a new anonymous user.
        if (400...499).contains(result.status) && result.status != 429 { return try await signUpAnonymously() }
        throw BackendError(message: "El servidor no está disponible (\(result.status))", status: result.status)
    }

    private struct TokenResponse: Decodable {
        struct User: Decodable { let id: String }
        let access_token: String
        let refresh_token: String
        let expires_in: Int64?
        let expires_at: Int64?
        let user: User
    }

    private func parseSession(_ data: Data) throws -> AuthSession {
        guard let token = try? JSONDecoder().decode(TokenResponse.self, from: data) else {
            throw BackendError(message: "Respuesta de sesión inválida")
        }
        let now = Int64(Date().timeIntervalSince1970)
        return AuthSession(
            accessToken: token.access_token,
            refreshToken: token.refresh_token,
            expiresAt: token.expires_at ?? (now + (token.expires_in ?? 3600)),
            userId: token.user.id
        )
    }
}
