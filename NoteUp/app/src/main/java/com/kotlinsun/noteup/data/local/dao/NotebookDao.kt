package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Insert
    suspend fun insert(notebook: NotebookEntity): Long

    @Query("UPDATE notebooks SET name = :name, updatedAt = :updatedAt WHERE id = :notebookId")
    suspend fun rename(notebookId: Long, name: String, updatedAt: Long)

    @Query("DELETE FROM notebooks WHERE id = :notebookId")
    suspend fun delete(notebookId: Long)
}
