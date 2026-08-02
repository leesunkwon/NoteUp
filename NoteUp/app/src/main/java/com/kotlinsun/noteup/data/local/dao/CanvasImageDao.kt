package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kotlinsun.noteup.data.local.entity.CanvasImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanvasImageDao {
    @Query("SELECT * FROM canvas_images WHERE pageId = :pageId ORDER BY elementIndex")
    fun observeByPage(pageId: Long): Flow<List<CanvasImageEntity>>

    @Query("SELECT * FROM canvas_images WHERE pageId = :pageId ORDER BY elementIndex")
    suspend fun getByPage(pageId: Long): List<CanvasImageEntity>

    @Query("SELECT COALESCE(MAX(elementIndex), -1) FROM canvas_images WHERE pageId = :pageId")
    suspend fun maximumIndex(pageId: Long): Int

    @Query("SELECT DISTINCT storageName FROM canvas_images")
    suspend fun getAllStorageNames(): List<String>

    @Query("SELECT DISTINCT storageName FROM canvas_images WHERE pageId IN (:pageIds)")
    suspend fun getStorageNamesByPageIds(pageIds: List<Long>): List<String>

    @Query("SELECT COUNT(*) FROM canvas_images WHERE storageName = :storageName")
    suspend fun referenceCount(storageName: String): Int

    @Insert suspend fun insert(value: CanvasImageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(values: List<CanvasImageEntity>)

    @Update suspend fun updateAll(values: List<CanvasImageEntity>)

    @Query("DELETE FROM canvas_images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM canvas_images WHERE pageId = :pageId")
    suspend fun deleteByPage(pageId: Long)
}
