package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.ImportedPdfEntity

@Dao
interface ImportedPdfDao {
    @Insert
    suspend fun insert(value: ImportedPdfEntity): Long

    @Query("SELECT storageName FROM imported_pdfs WHERE noteId IN (:noteIds)")
    suspend fun getStorageNamesByNoteIds(noteIds: List<Long>): List<String>

    @Query("SELECT storageName FROM imported_pdfs")
    suspend fun getAllStorageNames(): List<String>

    @Query("SELECT COUNT(*) FROM pdf_page_backgrounds WHERE pdfId = :pdfId")
    suspend fun backgroundCount(pdfId: Long): Int

    @Query("DELETE FROM imported_pdfs WHERE id = :pdfId")
    suspend fun delete(pdfId: Long)
}
