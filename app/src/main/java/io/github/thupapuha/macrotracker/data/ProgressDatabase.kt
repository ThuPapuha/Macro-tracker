package io.github.thupapuha.macrotracker.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A routine and its current target sets, as a JSON list of `ExerciseLog`. */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val exercisesJson: String,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A finished workout. [synced]/[deleted] track what still has to reach the groups' server. */
@Entity(tableName = "workouts", indices = [Index("date")])
data class WorkoutEntity(
    @PrimaryKey val id: String,
    /** ISO date (yyyy-MM-dd). */
    val date: String,
    val title: String,
    val routineId: String? = null,
    val exercisesJson: String,
    val prCount: Int = 0,
    val durationMin: Int? = null,
    val finishedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val deleted: Boolean = false,
)

/** One weigh-in per day, in kg. */
@Entity(tableName = "weights")
data class WeightEntity(
    @PrimaryKey val date: String,
    val kg: Double,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val deleted: Boolean = false,
)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM routines ORDER BY position, name")
    fun observeRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY position, name")
    suspend fun allRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun routine(id: String): RoutineEntity?

    @Upsert
    suspend fun upsertRoutine(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutine(id: String)

    @Query("SELECT * FROM workouts WHERE deleted = 0 ORDER BY date DESC, finishedAt DESC")
    fun observeWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE deleted = 0 ORDER BY date, finishedAt")
    suspend fun allWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun workout(id: String): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE synced = 0")
    suspend fun pendingWorkouts(): List<WorkoutEntity>

    @Upsert
    suspend fun upsertWorkout(workout: WorkoutEntity)

    /** Only if it was not edited again while uploading. */
    @Query("UPDATE workouts SET synced = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markWorkoutSynced(id: String, updatedAt: Long)

    @Query("DELETE FROM workouts WHERE id = :id AND deleted = 1")
    suspend fun purgeWorkout(id: String)

    @Query("SELECT * FROM weights WHERE deleted = 0 ORDER BY date")
    fun observeWeights(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights WHERE deleted = 0 ORDER BY date")
    suspend fun allWeights(): List<WeightEntity>

    @Query("SELECT * FROM weights WHERE synced = 0")
    suspend fun pendingWeights(): List<WeightEntity>

    @Upsert
    suspend fun upsertWeight(weight: WeightEntity)

    @Query("UPDATE weights SET synced = 1 WHERE date = :date AND updatedAt = :updatedAt")
    suspend fun markWeightSynced(date: String, updatedAt: Long)

    @Query("DELETE FROM weights WHERE date = :date AND deleted = 1")
    suspend fun purgeWeight(date: String)
}

/** Gym and body weight data. Kept apart from the food database so adding it needed no migration. */
@Database(entities = [RoutineEntity::class, WorkoutEntity::class, WeightEntity::class], version = 1, exportSchema = false)
abstract class ProgressDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

    companion object {
        fun build(context: Context): ProgressDatabase =
            Room.databaseBuilder(context, ProgressDatabase::class.java, "progress.db").build()
    }
}
