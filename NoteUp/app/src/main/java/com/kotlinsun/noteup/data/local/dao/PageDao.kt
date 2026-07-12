package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    fun observeByNote(noteId: Long): Flow<List<PageEntity>>

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Query("SELECT COALESCE(MAX(pageIndex), -1) + 1 FROM pages WHERE noteId = :noteId")
    suspend fun nextPageIndex(noteId: Long): Int

    @Query("UPDATE pages SET templateType = :templateType, updatedAt = :updatedAt WHERE id = :pageId")
    suspend fun updateTemplate(pageId: Long, templateType: String, updatedAt: Long)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun delete(pageId: Long)
}
