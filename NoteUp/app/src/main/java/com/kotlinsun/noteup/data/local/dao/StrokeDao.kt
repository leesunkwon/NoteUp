package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.StrokeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StrokeDao {
    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY strokeIndex ASC")
    fun observeByPage(pageId: Long): Flow<List<StrokeEntity>>

    @Query("SELECT COALESCE(MAX(strokeIndex), -1) + 1 FROM strokes WHERE pageId = :pageId")
    suspend fun nextStrokeIndex(pageId: Long): Int

    @Insert
    suspend fun insert(stroke: StrokeEntity): Long

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteByPage(pageId: Long)
}
