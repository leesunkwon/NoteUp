package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kotlinsun.noteup.data.local.entity.CanvasTextEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanvasTextDao {
    @Query("SELECT * FROM canvas_texts WHERE pageId = :pageId ORDER BY elementIndex")
    fun observeByPage(pageId: Long): Flow<List<CanvasTextEntity>>

    @Query("SELECT * FROM canvas_texts WHERE pageId = :pageId ORDER BY elementIndex")
    suspend fun getByPage(pageId: Long): List<CanvasTextEntity>

    @Query("SELECT COALESCE(MAX(elementIndex), -1) FROM canvas_texts WHERE pageId = :pageId")
    suspend fun maximumIndex(pageId: Long): Int

    @Insert suspend fun insert(value: CanvasTextEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<CanvasTextEntity>)
    @Update suspend fun updateAll(values: List<CanvasTextEntity>)
    @Query("DELETE FROM canvas_texts WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)
}
