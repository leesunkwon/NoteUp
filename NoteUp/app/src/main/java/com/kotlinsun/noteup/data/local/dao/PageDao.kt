package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.PageEntity
import kotlinx.coroutines.flow.Flow

data class NoteFirstPage(val noteId: Long, val pageId: Long)

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE noteId = :noteId ORDER BY pageIndex ASC LIMIT 1")
    fun observeFirstByNote(noteId: Long): Flow<PageEntity?>

    @Query("SELECT * FROM pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    fun observeByNote(noteId: Long): Flow<List<PageEntity>>

    @Query("SELECT noteId, id AS pageId FROM pages WHERE pageIndex = 0")
    fun observeFirstPageIds(): Flow<List<NoteFirstPage>>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun getById(pageId: Long): PageEntity?

    @Query("SELECT * FROM pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    suspend fun getByNote(noteId: Long): List<PageEntity>

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Query("SELECT COALESCE(MAX(pageIndex), -1) + 1 FROM pages WHERE noteId = :noteId")
    suspend fun nextPageIndex(noteId: Long): Int

    @Query("UPDATE pages SET templateType = :templateType, updatedAt = :updatedAt WHERE id = :pageId")
    suspend fun updateTemplate(pageId: Long, templateType: String, updatedAt: Long)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun delete(pageId: Long)

    @Query("UPDATE pages SET pageIndex = -(pageIndex + 1) WHERE noteId = :noteId")
    suspend fun moveIndexesToTemporaryRange(noteId: Long)

    @Query("UPDATE pages SET pageIndex = :pageIndex, updatedAt = :updatedAt WHERE id = :pageId")
    suspend fun updateIndex(pageId: Long, pageIndex: Int, updatedAt: Long)
}
