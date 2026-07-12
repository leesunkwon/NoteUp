package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE notebookId IS NULL ORDER BY updatedAt DESC, id DESC")
    fun observeUnfiled(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE notebookId = :notebookId ORDER BY updatedAt DESC, id DESC")
    fun observeByNotebook(notebookId: Long): Flow<List<NoteEntity>>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun rename(noteId: Long, title: String, updatedAt: Long)

    @Query("UPDATE notes SET notebookId = :notebookId, updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun move(noteId: Long, notebookId: Long?, updatedAt: Long)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun delete(noteId: Long)

    @Query("UPDATE notes SET updatedAt = :updatedAt WHERE id = :noteId")
    suspend fun touch(noteId: Long, updatedAt: Long)
}
