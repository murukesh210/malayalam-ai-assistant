package com.malayalam.assistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val task: String,
    val dueNote: String = "",
    val isCompleted: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "pending_reviews")
data class PendingReviewEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val safUriString: String,
    val title: String,
    val heardText: String,
    val category: String,
    val situationsCsv: String,
    val recommendedAppsCsv: String,
    val cachedVectorJson: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos ORDER BY isCompleted ASC, createdAtEpochMs DESC")
    fun observeTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE isCompleted = 0 ORDER BY createdAtEpochMs DESC LIMIT 15")
    suspend fun getActiveTodos(): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTodo(todo: TodoEntity): Long

    @Query("UPDATE todos SET isCompleted = :done WHERE id = :id")
    suspend fun setCompleted(id: Long, done: Boolean)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteTodo(id: Long)
}

@Dao
interface PendingReviewDao {
    @Query("SELECT * FROM pending_reviews ORDER BY createdAtEpochMs DESC")
    fun observePendingReviews(): Flow<List<PendingReviewEntity>>

    @Query("SELECT * FROM pending_reviews ORDER BY createdAtEpochMs ASC")
    suspend fun getAllPending(): List<PendingReviewEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PendingReviewEntity)

    @Query("DELETE FROM pending_reviews WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_reviews")
    suspend fun clearAll()
}

@Database(
    entities = [TodoEntity::class, PendingReviewEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun pendingReviewDao(): PendingReviewDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "malayalam_assistant.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
