package io.github.thupapuha.macrotracker.core.ai

import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.net.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import kotlin.math.roundToInt

@Serializable
data class EstimatedFood(
    val name: String = "",
    val quantity: String = "",
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
) {
    val macros: Macros get() = Macros(calories, protein, carbs, fat)
}

@Serializable
data class MealEstimate(
    val items: List<EstimatedFood> = emptyList(),
    val notes: String = "",
)

@Serializable
data class TargetsSuggestion(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    @SerialName("found_in_document") val foundInDocument: Boolean = false,
    val summary: String = "",
) {
    fun toTargets(sourceName: String): MacroTargets = MacroTargets(
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        note = buildString {
            append(if (foundInDocument) "Tomado de " else "Calculado a partir de ")
            append(sourceName.ifBlank { "tu documento" })
            if (summary.isNotBlank()) append(". ").append(summary)
        },
    )
}

/** An image or file that is sent to Gemini as inline data. */
class Attachment(val mimeType: String, val bytes: ByteArray) {
    fun toPart() = Part(inlineData = InlineData(mimeType, Base64.getEncoder().encodeToString(bytes)))
}

class AiService(private val gemini: GeminiClient) {

    suspend fun estimateMeal(description: String, image: Attachment? = null): MealEstimate {
        require(description.isNotBlank() || image != null) { "Describe la comida o agrega una foto" }
        val parts = buildList {
            image?.let { add(it.toPart()) }
            add(
                Part(
                    text = if (description.isBlank()) {
                        "Identifica y estima los alimentos de la foto."
                    } else {
                        "Comida a estimar: ${description.trim()}"
                    },
                ),
            )
        }
        val estimate = decode<MealEstimate>(gemini.generateJson(Prompts.MEAL, parts, Schemas.meal))
        val items = estimate.items.map { it.sanitized() }.filter { it.name.isNotBlank() }
        if (items.isEmpty()) throw AiException("La IA no reconoció alimentos. Agrega más detalle.")
        return estimate.copy(items = items)
    }

    suspend fun targetsFromDocument(documentParts: List<Part>, userNote: String = ""): TargetsSuggestion {
        val parts = documentParts + Part(
            text = buildString {
                append("Determina mis objetivos diarios de calorías y macronutrientes a partir del documento.")
                if (userNote.isNotBlank()) append("\nInformación adicional del usuario: ").append(userNote.trim())
            },
        )
        val suggestion = decode<TargetsSuggestion>(gemini.generateJson(Prompts.TARGETS, parts, Schemas.targets))
        val fixed = suggestion.copy(
            calories = suggestion.calories.takeIf { it > 0 }
                ?: Macros.caloriesFrom(suggestion.protein, suggestion.carbs, suggestion.fat),
        )
        if (fixed.calories !in 800.0..8000.0 || fixed.protein <= 0) {
            throw AiException(
                "No se encontraron objetivos válidos en el documento. " +
                    "Agrega en la nota tu peso, altura, edad y objetivo.",
            )
        }
        return fixed.copy(
            calories = fixed.calories.roundToInt().toDouble(),
            protein = fixed.protein.roundToInt().toDouble(),
            carbs = fixed.carbs.coerceAtLeast(0.0).roundToInt().toDouble(),
            fat = fixed.fat.coerceAtLeast(0.0).roundToInt().toDouble(),
        )
    }

    private inline fun <reified T> decode(json: JsonElement): T = try {
        AppJson.decodeFromJsonElement<T>(json)
    } catch (e: SerializationException) {
        throw AiException("La IA devolvió datos incompletos. Intenta de nuevo.", e)
    } catch (e: IllegalArgumentException) {
        throw AiException("La IA devolvió datos incompletos. Intenta de nuevo.", e)
    }
}

private fun Double.oneDecimal(): Double = (coerceAtLeast(0.0) * 10).roundToInt() / 10.0

internal fun EstimatedFood.sanitized(): EstimatedFood {
    val p = protein.oneDecimal()
    val c = carbs.oneDecimal()
    val f = fat.oneDecimal()
    val kcal = calories.takeIf { it > 0 } ?: Macros.caloriesFrom(p, c, f)
    return copy(
        name = name.trim(),
        quantity = quantity.trim(),
        calories = kcal.roundToInt().toDouble(),
        protein = p,
        carbs = c,
        fat = f,
    )
}

internal object Prompts {
    const val MEAL = """Eres un nutricionista experto en estimar el contenido nutricional de comidas.
Analiza la descripción y/o la foto y divide la comida en alimentos individuales.
- Si no se indica la cantidad, asume una porción típica y escríbela en "quantity" (por ejemplo "150 g", "1 taza", "2 unidades").
- Si la foto es una etiqueta nutricional, usa sus valores para la porción indicada.
- Para cada alimento da calorías (kcal), proteína, carbohidratos y grasas en gramos para esa cantidad.
- Usa nombres cortos en español.
- En "notes" escribe un comentario breve (máximo una frase) sobre supuestos importantes, o deja el texto vacío."""

    const val TARGETS = """Eres un nutricionista experto. El usuario adjunta un documento: puede ser un plan de alimentación,
una dieta de su nutricionista, un análisis de composición corporal (InBody, bioimpedancia), resultados médicos u otro.
Tu tarea es determinar sus objetivos DIARIOS de calorías (kcal), proteína, carbohidratos y grasas (gramos).
- Si el documento indica explícitamente calorías o macros diarios, úsalos (found_in_document = true).
- Si el documento es un menú o plan con comidas, suma el total de un día típico.
- Si no hay objetivos explícitos, calcúlalos con los datos disponibles (peso, altura, edad, sexo, nivel de actividad, objetivo)
  usando Mifflin-St Jeor y una distribución de macros adecuada al objetivo (found_in_document = false).
- En "summary" explica en español, en una o dos frases, de dónde salen los valores."""
}

internal object Schemas {
    private fun field(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun obj(properties: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
        putJsonArray("required") { properties.keys.forEach { add(it) } }
    }

    val meal: JsonObject = obj(
        mapOf(
            "items" to buildJsonObject {
                put("type", "ARRAY")
                put(
                    "items",
                    obj(
                        mapOf(
                            "name" to field("STRING", "Nombre del alimento en español"),
                            "quantity" to field("STRING", "Cantidad o porción, por ejemplo '150 g'"),
                            "calories" to field("NUMBER", "Calorías en kcal"),
                            "protein" to field("NUMBER", "Proteína en gramos"),
                            "carbs" to field("NUMBER", "Carbohidratos en gramos"),
                            "fat" to field("NUMBER", "Grasas en gramos"),
                        ),
                    ),
                )
            },
            "notes" to field("STRING", "Comentario breve sobre los supuestos"),
        ),
    )

    val targets: JsonObject = obj(
        mapOf(
            "calories" to field("NUMBER", "Calorías diarias objetivo en kcal"),
            "protein" to field("NUMBER", "Proteína diaria en gramos"),
            "carbs" to field("NUMBER", "Carbohidratos diarios en gramos"),
            "fat" to field("NUMBER", "Grasas diarias en gramos"),
            "found_in_document" to field("BOOLEAN", "true si los valores aparecen explícitamente en el documento"),
            "summary" to field("STRING", "Explicación breve en español del origen de los valores"),
        ),
    )
}
