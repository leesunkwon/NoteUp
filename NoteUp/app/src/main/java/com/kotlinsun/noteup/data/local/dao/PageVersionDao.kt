package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.PageVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageVersionDao {
    @Query("SELECT * FROM page_versions WHERE pageId = :pageId ORDER BY createdAt DESC")
    fun observeByPage(pageId: Long): Flow<List<PageVersionEntity>>

    @Query("SELECT * FROM page_versions WHERE pageId = :pageId ORDER BY createdAt DESC")
    suspend fun getByPage(pageId: Long): List<PageVersionEntity>

    @Query("SELECT * FROM page_versions WHERE id = :versionId LIMIT 1")
    suspend fun getById(versionId: Long): PageVersionEntity?

    @Query("SELECT * FROM page_versions")
    suspend fun getAll(): List<PageVersionEntity>

    @Insert
    suspend fun insert(value: PageVersionEntity): Long

    @Query("DELETE FROM page_versions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
