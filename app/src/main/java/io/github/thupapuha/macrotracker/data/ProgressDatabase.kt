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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A section the user groups routines in, like "Hipertrofia" or "Fuerza". */
@Entity(tableName = "routine_sections")
data class SectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int = 0,
)

/** A routine and its current target sets, as a JSON list of `ExerciseLog`. */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val exercisesJson: String,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** [SectionEntity.id], or null when it is in no section. */
    val sectionId: String? = null,
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
    /** Name of the routine's section when it was done. */
    val section: String? = null,
)

/** One weigh-in per day, in kg, with the body composition if it was measured. */
@Entity(tableName = "weights")
data class WeightEntity(
    @PrimaryKey val date: String,
    val kg: Double,
    val fatPercent: Double? = null,
    val musclePercent: Double? = null,
    val boneKg: Double? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val deleted: Boolean = false,
)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM routine_sections ORDER BY position, name")
    fun observeSections(): Flow<List<SectionEntity>>

    @Query("SELECT * FROM routine_sections ORDER BY position, name")
    suspend fun allSections(): List<SectionEntity>

    @Query("SELECT * FROM routine_sections WHERE id = :id")
    suspend fun section(id: String): SectionEntity?

    @Upsert
    suspend fun upsertSection(section: SectionEntity)

    @Query("DELETE FROM routine_sections WHERE id = :id")
    suspend fun deleteSection(id: String)

    /** Routines of a deleted section stay, without a section. */
    @Query("UPDATE routines SET sectionId = NULL WHERE sectionId = :sectionId")
    suspend fun clearSection(sectionId: String)

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

    @Query("DELETE FROM workouts WHERE deleted = 1")
    suspend fun purgeDeletedWorkouts()

    @Query("UPDATE workouts SET synced = 0")
    suspend fun markWorkoutsUnsynced()

    @Query("DELETE FROM weights WHERE deleted = 1")
    suspend fun purgeDeletedWeights()

    @Query("UPDATE weights SET synced = 0")
    suspend fun markWeightsUnsynced()

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

/** Version 2 adds body composition to the weigh-ins. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `weights` ADD COLUMN `fatPercent` REAL")
        db.execSQL("ALTER TABLE `weights` ADD COLUMN `musclePercent` REAL")
        db.execSQL("ALTER TABLE `weights` ADD COLUMN `boneKg` REAL")
    }
}

/** Version 3 adds sections of routines and the section of each workout. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `routine_sections` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("ALTER TABLE `routines` ADD COLUMN `sectionId` TEXT")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `section` TEXT")
    }
}

/** Gym and body data. Kept apart from the food database so adding it needed no migration there. */
@Database(
    entities = [SectionEntity::class, RoutineEntity::class, WorkoutEntity::class, WeightEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ProgressDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

    companion object {
        fun build(context: Context): ProgressDatabase =
            Room.databaseBuilder(context, ProgressDatabase::class.java, "progress.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
