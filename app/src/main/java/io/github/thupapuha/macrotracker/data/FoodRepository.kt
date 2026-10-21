package io.github.thupapuha.macrotracker.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDate

class FoodRepository(private val dao: FoodDao) {

    private val changes = MutableSharedFlow<LocalDate>(extraBufferCapacity = 64)

    /** Emits the day whose entries were just added, edited or removed. */
    val changedDays: SharedFlow<LocalDate> = changes

    fun observeDay(date: LocalDate): Flow<List<FoodEntry>> = dao.observeDay(date.toString())

    fun observeTotals(from: LocalDate, to: LocalDate): Flow<List<DayTotals>> =
        dao.observeTotals(from.toString(), to.toString())

    fun observeRecent(): Flow<List<FoodEntry>> = dao.observeRecent(60)

    suspend fun entriesFor(date: LocalDate): List<FoodEntry> = dao.getDay(date.toString())

    suspend fun allEntries(): List<FoodEntry> = dao.getAll()

    suspend fun add(entries: List<FoodEntry>) {
        if (entries.isEmpty()) return
        dao.insertAll(entries)
        entries.map { it.date }.distinct().forEach { changes.emit(LocalDate.parse(it)) }
    }

    suspend fun update(old: FoodEntry, updated: FoodEntry) {
        dao.update(updated)
        changes.emit(LocalDate.parse(updated.date))
        if (old.date != updated.date) changes.emit(LocalDate.parse(old.date))
    }

    suspend fun delete(entry: FoodEntry) {
        dao.delete(entry)
        changes.emit(LocalDate.parse(entry.date))
    }
}
