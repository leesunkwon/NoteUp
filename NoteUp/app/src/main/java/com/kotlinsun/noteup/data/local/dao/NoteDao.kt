package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE id = :noteId AND deletedAt IS NULL LIMIT 1")
    fun observeById(noteId: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND instr(lower(title), lower(:query)) > 0 ORDER BY updatedAt DESC, id DESC")
    fun observeAll(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND notebookId IS NULL AND instr(lower(title), lower(:query)) > 0 ORDER BY updatedAt DESC, id DESC")
    fun observeUnfiled(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL AND notebookId = :notebookId AND instr(lower(title), lower(:query)) > 0 ORDER BY updatedAt DESC, id DESC")
    fun observeByNotebook(notebookId: Long, query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL AND instr(lower(title), lower(:query)) > 0 ORDER BY deletedAt DESC, id DESC")
    fun observeTrash(query: String): Flow<List<NoteEntity>>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :noteId AND deletedAt IS NULL")
    suspend fun rename(noteId: Long, title: String, updatedAt: Long)

    @Query("UPDATE notes SET notebookId = :notebookId, updatedAt = :updatedAt WHERE id = :noteId AND deletedAt IS NULL")
    suspend fun move(noteId: Long, notebookId: Long?, updatedAt: Long)

    @Query("UPDATE notes SET deletedAt = :deletedAt WHERE id = :noteId AND deletedAt IS NULL")
    suspend fun moveToTrash(noteId: Long, deletedAt: Long): Int

    @Query("UPDATE notes SET deletedAt = NULL, updatedAt = :restoredAt WHERE id = :noteId AND deletedAt IS NOT NULL")
    suspend fun restore(noteId: Long, restoredAt: Long): Int

    @Query("SELECT id FROM notes WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun getExpiredIds(cutoff: Long): List<Long>

    @Query("DELETE FROM notes WHERE id IN (:noteIds) AND deletedAt IS NOT NULL")
    suspend fun deleteTrashedByIds(noteIds: List<Long>): Int

    @Query("UPDATE notes SET updatedAt = :updatedAt WHERE id = :noteId AND deletedAt IS NULL")
    suspend fun touch(noteId: Long, updatedAt: Long)
}
