import Foundation

struct AIError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// An image or file sent to Gemini as inline data, or a text part.
struct AIPart {
    var text: String? = nil
    var mimeType: String? = nil
    var data: Data? = nil

    static func text(_ value: String) -> AIPart { AIPart(text: value) }
    static func inline(_ mimeType: String, _ data: Data) -> AIPart { AIPart(mimeType: mimeType, data: data) }

    var json: JSONValue {
        if let text { return ["text": .string(text)] }
        return ["inlineData": ["mimeType": .string(mimeType ?? "application/octet-stream"), "data": .string(data?.base64EncodedString() ?? "")]]
    }
}

struct EstimatedFood: Decodable, Hashable {
    var name: String = ""
    var quantity: String = ""
    var calories: Double = 0
    var protein: Double = 0
    var carbs: Double = 0
    var fat: Double = 0

    var macros: Macros { Macros(calories: calories, protein: protein, carbs: carbs, fat: fat) }

    init(name: String, quantity: String, calories: Double, protein: Double, carbs: Double, fat: Double) {
        self.name = name
        self.quantity = quantity
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
    }

    init(from decoder: Decoder) throws {
        enum Keys: String, CodingKey { case name, quantity, calories, protein, carbs, fat }
        let c = try decoder.container(keyedBy: Keys.self)
        name = c.value(.name, "")
        quantity = c.value(.quantity, "")
        calories = c.value(.calories, 0)
        protein = c.value(.protein, 0)
        carbs = c.value(.carbs, 0)
        fat = c.value(.fat, 0)
    }

    private static func oneDecimal(_ value: Double) -> Double { (max(value, 0) * 10).rounded() / 10 }

    /// Rounds the values and fills in calories from the macros when the model left them out.
    var sanitized: EstimatedFood {
        let p = Self.oneDecimal(protein)
        let c = Self.oneDecimal(carbs)
        let f = Self.oneDecimal(fat)
        let energy = calories > 0 ? calories : Macros.caloriesFrom(protein: p, carbs: c, fat: f)
        return EstimatedFood(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            quantity: quantity.trimmingCharacters(in: .whitespacesAndNewlines),
            calories: energy.rounded(),
            protein: p,
            carbs: c,
            fat: f
        )
    }
}

struct MealEstimate: Decodable {
    var items: [EstimatedFood] = []
    var notes: String = ""

    init(items: [EstimatedFood], notes: String) {
        self.items = items
        self.notes = notes
    }

    init(from decoder: Decoder) throws {
        enum Keys: String, CodingKey { case items, notes }
        let c = try decoder.container(keyedBy: Keys.self)
        items = c.value(.items, [])
        notes = c.value(.notes, "")
    }
}

struct TargetsSuggestion: Decodable {
    var calories: Double = 0
    var protein: Double = 0
    var carbs: Double = 0
    var fat: Double = 0
    var foundInDocument: Bool = false
    var summary: String = ""

    init(from decoder: Decoder) throws {
        enum Keys: String, CodingKey {
            case calories, protein, carbs, fat, summary
            case foundInDocument = "found_in_document"
        }
        let c = try decoder.container(keyedBy: Keys.self)
        calories = c.value(.calories, 0)
        protein = c.value(.protein, 0)
        carbs = c.value(.carbs, 0)
        fat = c.value(.fat, 0)
        foundInDocument = c.value(.foundInDocument, false)
        summary = c.value(.summary, "")
    }

    func toTargets(sourceName: String) -> MacroTargets {
        var note = foundInDocument ? "Tomado de " : "Calculado a partir de "
        note += sourceName.isEmpty ? "tu documento" : sourceName
        if !summary.isEmpty { note += ". " + summary }
        return MacroTargets(calories: calories, protein: protein, carbs: carbs, fat: fat, note: note)
    }
}

/// Sends a raw `generateContent` body: through the server's `gemini` function (which holds the key),
/// or straight to Gemini with a key the user typed in.
enum GeminiTransport {
    case proxy(SupabaseClient)
    case direct(apiKey: String, model: String)

    func send(_ body: Data) async throws -> HTTPResult {
        switch self {
        case .proxy(let client):
            return try await client.invokeFunction("gemini", body: body)
        case .direct(let apiKey, let model):
            guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent") else {
                throw AIError(message: "Modelo de IA inválido")
            }
            var request = URLRequest(url: url, timeoutInterval: 180)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
            request.httpBody = body
            let (data, response) = try await URLSession.shared.data(for: request)
            return HTTPResult(status: (response as? HTTPURLResponse)?.statusCode ?? 0, data: data)
        }
    }
}

struct AIService {
    /// Picks the transport for each request (the user may add or remove their own key).
    let transport: () throws -> GeminiTransport

    func estimateMeal(description: String, image: AIPart? = nil) async throws -> MealEstimate {
        let text = description.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || image != nil else { throw AIError(message: "Describe la comida o agrega una foto") }
        var parts: [AIPart] = []
        if let image { parts.append(image) }
        parts.append(.text(text.isEmpty ? "Identifica y estima los alimentos de la foto." : "Comida a estimar: \(text)"))
        let estimate: MealEstimate = try await generate(system: Prompts.meal, parts: parts, schema: Schemas.meal)
        let items = estimate.items.map(\.sanitized).filter { !$0.name.isEmpty }
        if items.isEmpty { throw AIError(message: "La IA no reconoció alimentos. Agrega más detalle.") }
        return MealEstimate(items: items, notes: estimate.notes)
    }

    func targetsFromDocument(_ documentParts: [AIPart], userNote: String = "") async throws -> TargetsSuggestion {
        var request = "Determina mis objetivos diarios de calorías y macronutrientes a partir del documento."
        let note = userNote.trimmingCharacters(in: .whitespacesAndNewlines)
        if !note.isEmpty { request += "\nInformación adicional del usuario: " + note }
        var suggestion: TargetsSuggestion = try await generate(system: Prompts.targets, parts: documentParts + [.text(request)], schema: Schemas.targets)
        if suggestion.calories <= 0 {
            suggestion.calories = Macros.caloriesFrom(protein: suggestion.protein, carbs: suggestion.carbs, fat: suggestion.fat)
        }
        guard (800.0...8000.0).contains(suggestion.calories), suggestion.protein > 0 else {
            throw AIError(message: "No se encontraron objetivos válidos en el documento. Agrega en la nota tu peso, altura, edad y objetivo.")
        }
        suggestion.calories = suggestion.calories.rounded()
        suggestion.protein = suggestion.protein.rounded()
        suggestion.carbs = max(suggestion.carbs, 0).rounded()
        suggestion.fat = max(suggestion.fat, 0).rounded()
        return suggestion
    }

    private func generate<T: Decodable>(system: String, parts: [AIPart], schema: JSONValue) async throws -> T {
        let body: JSONValue = [
            "contents": [["role": "user", "parts": .array(parts.map(\.json))]],
            "systemInstruction": ["parts": [["text": .string(system)]]],
            "generationConfig": ["responseMimeType": "application/json", "responseSchema": schema],
        ]
        let result: HTTPResult
        do {
            result = try await transport().send(body.data)
        } catch let error as BackendError {
            throw AIError(message: error.message)
        } catch let error as AIError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw AIError(message: "No se pudo conectar con la IA. Revisa tu conexión a internet.")
        }
        guard result.isSuccessful else { throw AIError(message: Self.describeError(result)) }

        guard let response = try? JSONDecoder().decode(GeminiResponse.self, from: result.data) else {
            throw AIError(message: "Respuesta inesperada de la IA")
        }
        guard let text = response.text else {
            if let reason = response.promptFeedback?.blockReason { throw AIError(message: "La IA rechazó la solicitud (\(reason))") }
            throw AIError(message: "La IA no devolvió resultados. Intenta de nuevo con más detalle.")
        }
        let json = try Self.parseJSONText(text)
        do {
            return try JSONDecoder().decode(T.self, from: json)
        } catch {
            throw AIError(message: "La IA devolvió datos incompletos. Intenta de nuevo.")
        }
    }

    static func describeError(_ result: HTTPResult) -> String {
        let detail = extractErrorMessage(result.data) ?? String(String(data: result.data, encoding: .utf8)?.prefix(200) ?? "")
        switch result.status {
        case 400: return "Solicitud inválida para la IA: \(detail)"
        case 401, 403: return "Sin permiso para usar la IA: \(detail)"
        case 404: return "No se encontró el servicio o modelo de IA. ¿Está desplegada la función en Supabase? (\(detail))"
        case 413: return "El archivo es demasiado grande para la IA"
        case 429: return "Se alcanzó el límite de uso de la IA. Intenta más tarde. (\(detail))"
        case 500...599: return "La IA no está disponible en este momento (\(result.status)). Intenta de nuevo."
        default: return "Error de la IA (\(result.status)): \(detail)"
        }
    }

    /// Parses model output as JSON, tolerating markdown code fences or text around the object.
    static func parseJSONText(_ text: String) throws -> Data {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["```json", "```JSON", "```"] where trimmed.hasPrefix(prefix) {
            trimmed = String(trimmed.dropFirst(prefix.count))
            break
        }
        if trimmed.hasSuffix("```") { trimmed = String(trimmed.dropLast(3)) }
        trimmed = trimmed.trimmingCharacters(in: .whitespacesAndNewlines)
        if let data = trimmed.data(using: .utf8), (try? JSONSerialization.jsonObject(with: data)) != nil { return data }
        if let start = trimmed.firstIndex(of: "{"), let end = trimmed.lastIndex(of: "}"), start < end {
            let slice = String(trimmed[start...end])
            if let data = slice.data(using: .utf8), (try? JSONSerialization.jsonObject(with: data)) != nil { return data }
        }
        throw AIError(message: "La IA devolvió un formato que no se pudo leer")
    }
}

private struct GeminiResponse: Decodable {
    struct Part: Decodable {
        let text: String?
        let thought: Bool?
    }

    struct Content: Decodable { let parts: [Part]? }
    struct Candidate: Decodable { let content: Content? }
    struct Feedback: Decodable { let blockReason: String? }

    let candidates: [Candidate]?
    let promptFeedback: Feedback?

    /// Concatenated answer text, skipping "thought" parts.
    var text: String? {
        let joined = (candidates?.first?.content?.parts ?? [])
            .filter { $0.thought != true }
            .compactMap(\.text)
            .joined()
        return joined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : joined
    }
}

enum Prompts {
    static let meal = """
    Eres un nutricionista experto en estimar el contenido nutricional de comidas.
    Analiza la descripción y/o la foto y divide la comida en alimentos individuales.
    - Si no se indica la cantidad, asume una porción típica y escríbela en "quantity" (por ejemplo "150 g", "1 taza", "2 unidades").
    - Si la foto es una etiqueta nutricional, usa sus valores para la porción indicada.
    - Para cada alimento da calorías (kcal), proteína, carbohidratos y grasas en gramos para esa cantidad.
    - Usa nombres cortos en español.
    - En "notes" escribe un comentario breve (máximo una frase) sobre supuestos importantes, o deja el texto vacío.
    """

    static let targets = """
    Eres un nutricionista experto. El usuario adjunta un documento: puede ser un plan de alimentación,
    una dieta de su nutricionista, un análisis de composición corporal (InBody, bioimpedancia), resultados médicos u otro.
    Tu tarea es determinar sus objetivos DIARIOS de calorías (kcal), proteína, carbohidratos y grasas (gramos).
    - Si el documento indica explícitamente calorías o macros diarios, úsalos (found_in_document = true).
    - Si el documento es un menú o plan con comidas, suma el total de un día típico.
    - Si no hay objetivos explícitos, calcúlalos con los datos disponibles (peso, altura, edad, sexo, nivel de actividad, objetivo)
      usando Mifflin-St Jeor y una distribución de macros adecuada al objetivo (found_in_document = false).
    - En "summary" explica en español, en una o dos frases, de dónde salen los valores.
    """
}

enum Schemas {
    private static func field(_ type: String, _ description: String) -> JSONValue {
        ["type": .string(type), "description": .string(description)]
    }

    private static func object(_ properties: [(String, JSONValue)]) -> JSONValue {
        [
            "type": "OBJECT",
            "properties": .object(Dictionary(properties, uniquingKeysWith: { a, _ in a })),
            "required": .array(properties.map { .string($0.0) }),
        ]
    }

    static let meal: JSONValue = object([
        ("items", [
            "type": "ARRAY",
            "items": object([
                ("name", field("STRING", "Nombre del alimento en español")),
                ("quantity", field("STRING", "Cantidad o porción, por ejemplo '150 g'")),
                ("calories", field("NUMBER", "Calorías en kcal")),
                ("protein", field("NUMBER", "Proteína en gramos")),
                ("carbs", field("NUMBER", "Carbohidratos en gramos")),
                ("fat", field("NUMBER", "Grasas en gramos")),
            ]),
        ]),
        ("notes", field("STRING", "Comentario breve sobre los supuestos")),
    ])

    static let targets: JSONValue = object([
        ("calories", field("NUMBER", "Calorías diarias objetivo en kcal")),
        ("protein", field("NUMBER", "Proteína diaria en gramos")),
        ("carbs", field("NUMBER", "Carbohidratos diarios en gramos")),
        ("fat", field("NUMBER", "Grasas diarias en gramos")),
        ("found_in_document", field("BOOLEAN", "true si los valores aparecen explícitamente en el documento")),
        ("summary", field("STRING", "Explicación breve en español del origen de los valores")),
    ])
}
