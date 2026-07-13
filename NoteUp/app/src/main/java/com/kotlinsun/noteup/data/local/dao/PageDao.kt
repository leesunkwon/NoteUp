package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Embedded
import com.kotlinsun.noteup.data.local.entity.PageEntity
import kotlinx.coroutines.flow.Flow

data class NoteFirstPage(val noteId: Long, val pageId: Long)

private const val PAGE_WITH_BACKGROUND_SELECT = """
    SELECT pages.*,
        imported_pdfs.id AS pdfId,
        imported_pdfs.storageName AS pdfStorageName,
        imported_pdfs.displayName AS pdfDisplayName,
        pdf_page_backgrounds.sourcePageIndex AS pdfSourcePageIndex,
        pdf_page_backgrounds.widthPoints AS pdfWidthPoints,
        pdf_page_backgrounds.heightPoints AS pdfHeightPoints
    FROM pages
    LEFT JOIN pdf_page_backgrounds ON pdf_page_backgrounds.pageId = pages.id
    LEFT JOIN imported_pdfs ON imported_pdfs.id = pdf_page_backgrounds.pdfId
"""

data class PageWithBackgroundRow(
    @Embedded val page: PageEntity,
    val pdfId: Long?,
    val pdfStorageName: String?,
    val pdfDisplayName: String?,
    val pdfSourcePageIndex: Int?,
    val pdfWidthPoints: Int?,
    val pdfHeightPoints: Int?,
)

@Dao
interface PageDao {
    @Query(PAGE_WITH_BACKGROUND_SELECT + " WHERE pages.noteId = :noteId ORDER BY pages.pageIndex ASC LIMIT 1")
    fun observeFirstByNote(noteId: Long): Flow<PageWithBackgroundRow?>

    @Query(PAGE_WITH_BACKGROUND_SELECT + " WHERE pages.noteId = :noteId ORDER BY pages.pageIndex ASC")
    fun observeByNote(noteId: Long): Flow<List<PageWithBackgroundRow>>

    @Query("SELECT noteId, id AS pageId FROM pages WHERE pageIndex = 0")
    fun observeFirstPageIds(): Flow<List<NoteFirstPage>>

    @Query(PAGE_WITH_BACKGROUND_SELECT + " WHERE pages.id = :pageId LIMIT 1")
    suspend fun getById(pageId: Long): PageWithBackgroundRow?

    @Query(PAGE_WITH_BACKGROUND_SELECT + " WHERE pages.noteId = :noteId ORDER BY pages.pageIndex ASC")
    suspend fun getByNote(noteId: Long): List<PageWithBackgroundRow>

    @Query("SELECT * FROM pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    suspend fun getEntitiesByNote(noteId: Long): List<PageEntity>

    @Query("SELECT id FROM pages WHERE noteId IN (:noteIds)")
    suspend fun getIdsByNoteIds(noteIds: List<Long>): List<Long>

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
