import Foundation

/// Any JSON value: used to build RPC arguments and the Gemini schemas.
enum JSONValue: Codable, Hashable, ExpressibleByStringLiteral, ExpressibleByIntegerLiteral,
    ExpressibleByFloatLiteral, ExpressibleByBooleanLiteral, ExpressibleByArrayLiteral,
    ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case array([JSONValue])
    case object([String: JSONValue])
    case null

    init(stringLiteral value: String) { self = .string(value) }
    init(integerLiteral value: Int) { self = .int(value) }
    init(floatLiteral value: Double) { self = .double(value) }
    init(booleanLiteral value: Bool) { self = .bool(value) }
    init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
    init(nilLiteral: ()) { self = .null }

    static func of(_ value: String?) -> JSONValue { value.map { .string($0) } ?? .null }
    static func of(_ value: Double?) -> JSONValue { value.map { .double($0) } ?? .null }
    static func of(_ value: Int?) -> JSONValue { value.map { .int($0) } ?? .null }

    /// Encodes any Encodable value (e.g. a list of exercises) as JSON.
    static func encoded<T: Encodable>(_ value: T) -> JSONValue {
        guard let data = try? JSONEncoder().encode(value),
              let decoded = try? JSONDecoder().decode(JSONValue.self, from: data) else { return .null }
        return decoded
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let value = try? c.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? c.decode(Int.self) {
            self = .int(value)
        } else if let value = try? c.decode(Double.self) {
            self = .double(value)
        } else if let value = try? c.decode(String.self) {
            self = .string(value)
        } else if let value = try? c.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try c.decode([String: JSONValue].self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let value): try c.encode(value)
        case .int(let value): try c.encode(value)
        case .double(let value): try c.encode(value)
        case .bool(let value): try c.encode(value)
        case .array(let value): try c.encode(value)
        case .object(let value): try c.encode(value)
        case .null: try c.encodeNil()
        }
    }

    var data: Data { (try? JSONEncoder().encode(self)) ?? Data("null".utf8) }
}

/// Decodes a list skipping the elements that cannot be read (e.g. a post type from a newer app).
struct Lossy<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

/// Pulls a human readable message out of the error bodies returned by Gemini, PostgREST and GoTrue.
func extractErrorMessage(_ data: Data) -> String? {
    guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
    let candidates: [String?] = [
        object["message"] as? String,
        object["msg"] as? String,
        object["error_description"] as? String,
        (object["error"] as? [String: Any])?["message"] as? String,
        object["error"] as? String,
    ]
    return candidates.compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
}
