package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.PdfPageBackgroundEntity

@Dao
interface PdfPageBackgroundDao {
    @Insert
    suspend fun insertAll(values: List<PdfPageBackgroundEntity>)

    @Query("SELECT * FROM pdf_page_backgrounds WHERE pageId = :pageId LIMIT 1")
    suspend fun getByPage(pageId: Long): PdfPageBackgroundEntity?
}
