package io.github.thupapuha.macrotracker.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "food_entries", indices = [Index("date")])
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO date (yyyy-MM-dd) of the day the food was eaten. */
    val date: String,
    /** [MealType] name. */
    val meal: String,
    val name: String,
    val quantity: String = "",
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    /** "manual", "ai" or "recent". */
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis(),
)

val FoodEntry.macros: Macros get() = Macros(calories, protein, carbs, fat)

val FoodEntry.mealType: MealType get() = MealType.fromName(meal)

data class DayTotals(
    val date: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val entries: Int,
)

val DayTotals.macros: Macros get() = Macros(calories, protein, carbs, fat)

@Dao
interface FoodDao {
    @Query("SELECT * FROM food_entries WHERE date = :date ORDER BY createdAt")
    fun observeDay(date: String): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_entries WHERE date = :date ORDER BY createdAt")
    suspend fun getDay(date: String): List<FoodEntry>

    @Query(
        """
        SELECT date, SUM(calories) AS calories, SUM(protein) AS protein, SUM(carbs) AS carbs,
               SUM(fat) AS fat, COUNT(*) AS entries
        FROM food_entries
        WHERE date BETWEEN :from AND :to
        GROUP BY date
        ORDER BY date
        """,
    )
    fun observeTotals(from: String, to: String): Flow<List<DayTotals>>

    /** Latest entry for each distinct food name, newest first. */
    @Query(
        """
        SELECT * FROM food_entries
        WHERE id IN (SELECT MAX(id) FROM food_entries GROUP BY lower(name))
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<FoodEntry>>

    @Insert
    suspend fun insertAll(entries: List<FoodEntry>)

    @Update
    suspend fun update(entry: FoodEntry)

    @Delete
    suspend fun delete(entry: FoodEntry)
}

@Database(entities = [FoodEntry::class], version = 1, exportSchema = false)
abstract class FoodDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao

    companion object {
        fun build(context: Context): FoodDatabase =
            Room.databaseBuilder(context, FoodDatabase::class.java, "macro-tracker.db").build()
    }
}
