package io.github.thupapuha.macrotracker.core.gym

/**
 * One day's body measurements: weight in kg and, if measured (scale, InBody...), body fat %,
 * muscle % and bone mass in kg.
 */
data class BodyLog(
    val day: String,
    val kg: Double,
    val fatPercent: Double? = null,
    val musclePercent: Double? = null,
    val boneKg: Double? = null,
) {
    val hasComposition: Boolean get() = fatPercent != null || musclePercent != null || boneKg != null

    /** Kilos of fat and of everything else, when body fat is known. */
    val fatMassKg: Double? get() = fatPercent?.let { kg * it / 100 }
    val leanMassKg: Double? get() = fatMassKg?.let { kg - it }
}

/** The measurements that can be charted, with the range the server accepts. */
enum class BodyMetric(val label: String, val unit: String, val range: ClosedFloatingPointRange<Double>) {
    WEIGHT("Peso", "kg", 20.0..400.0),
    FAT("Grasa", "%", 1.0..80.0),
    MUSCLE("Músculo", "%", 5.0..90.0),
    BONE("Hueso", "kg", 0.2..15.0);

    fun of(log: BodyLog): Double? = when (this) {
        WEIGHT -> log.kg
        FAT -> log.fatPercent
        MUSCLE -> log.musclePercent
        BONE -> log.boneKg
    }

    /** Whether going up is good news (more muscle) or bad news (more fat). Null when neutral. */
    val upIsGood: Boolean?
        get() = when (this) {
            FAT -> false
            MUSCLE -> true
            else -> null
        }
}

/** Latest value of a measurement and the one before it. */
data class MetricTrend(val current: Double, val previous: Double?) {
    val change: Double? get() = previous?.let { current - it }
}

object Body {
    /** Values of [metric] in [logs] (any order), oldest first, skipping days it was not measured. */
    fun series(logs: List<BodyLog>, metric: BodyMetric): List<Pair<String, Double>> =
        logs.sortedBy { it.day }.mapNotNull { log -> metric.of(log)?.let { log.day to it } }

    fun trend(logs: List<BodyLog>, metric: BodyMetric): MetricTrend? {
        val values = series(logs, metric)
        val last = values.lastOrNull() ?: return null
        return MetricTrend(last.second, values.getOrNull(values.size - 2)?.second)
    }

    /** Metrics with at least [minPoints] measurements, to offer them in the chart. */
    fun chartable(logs: List<BodyLog>, minPoints: Int = 2): List<BodyMetric> =
        BodyMetric.entries.filter { metric -> logs.count { metric.of(it) != null } >= minPoints }
}
