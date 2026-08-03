package com.kotlinsun.noteup.domain.repository

import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasTextDraft
import com.kotlinsun.noteup.domain.model.CanvasImage
import com.kotlinsun.noteup.domain.model.CanvasImageDraft
import com.kotlinsun.noteup.domain.model.CopiedCanvasElements
import com.kotlinsun.noteup.domain.model.CreatedPageText
import com.kotlinsun.noteup.domain.model.DeletedAssets
import com.kotlinsun.noteup.domain.model.PdfImportPage
import com.kotlinsun.noteup.domain.model.PageVersion
import com.kotlinsun.noteup.domain.model.PageVersionReason
import com.kotlinsun.noteup.domain.model.PageSnapshot
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun observeNotebooks(): Flow<List<Notebook>>
    fun observeNote(noteId: Long): Flow<Note?>
    fun observeAllNotes(query: String = ""): Flow<List<Note>>
    fun observeUnfiledNotes(query: String = ""): Flow<List<Note>>
    fun observeNotes(notebookId: Long, query: String = ""): Flow<List<Note>>
    fun observeTrashedNotes(query: String = ""): Flow<List<Note>>
    fun observePages(noteId: Long): Flow<List<Page>>
    fun observeFirstPage(noteId: Long): Flow<Page?>
    fun observeFirstPageIds(): Flow<Map<Long, Long>>
    fun observeStrokes(pageId: Long): Flow<List<Stroke>>
    fun observeTexts(pageId: Long): Flow<List<CanvasText>>
    fun observeImages(pageId: Long): Flow<List<CanvasImage>>
    fun observePageVersions(pageId: Long): Flow<List<PageVersion>>
    suspend fun createNotebook(name: String): Long
    suspend fun renameNotebook(notebookId: Long, name: String)
    suspend fun deleteNotebook(notebookId: Long)
    suspend fun createNote(
        title: String,
        notebookId: Long?,
        template: PageTemplate = PageTemplate.BLANK,
    ): Long
    suspend fun renameNote(noteId: Long, title: String)
    suspend fun moveNote(noteId: Long, notebookId: Long?)
    suspend fun moveNoteToTrash(noteId: Long)
    suspend fun restoreNote(noteId: Long)
    suspend fun permanentlyDeleteNote(noteId: Long): DeletedAssets
    suspend fun purgeExpiredNotes(cutoff: Long): DeletedAssets
    suspend fun createImportedPdfNote(
        title: String,
        storageName: String,
        displayName: String,
        pages: List<PdfImportPage>,
    ): Long
    suspend fun getReferencedPdfStorageNames(): Set<String>
    suspend fun getReferencedImageStorageNames(): Set<String>
    suspend fun createPage(noteId: Long, template: PageTemplate): Long
    suspend fun createPageWithText(
        noteId: Long,
        template: PageTemplate,
        draft: CanvasTextDraft,
    ): CreatedPageText
    suspend fun restorePageWithText(value: CreatedPageText): CreatedPageText
    suspend fun updatePageTemplate(pageId: Long, template: PageTemplate)
    suspend fun deletePage(noteId: Long, pageId: Long): DeletedAssets
    suspend fun reorderPages(noteId: Long, orderedPageIds: List<Long>)
    suspend fun getPage(pageId: Long): Page?
    suspend fun getPages(noteId: Long): List<Page>
    suspend fun getStrokes(pageId: Long): List<Stroke>
    suspend fun getTexts(pageId: Long): List<CanvasText>
    suspend fun getImages(pageId: Long): List<CanvasImage>
    suspend fun getPageVersion(versionId: Long): PageVersion?
    suspend fun getPageVersions(pageId: Long): List<PageVersion>
    suspend fun getAllPageVersions(): List<PageVersion>
    suspend fun addPageVersion(
        pageId: Long,
        createdAt: Long,
        reason: PageVersionReason,
        snapshotName: String,
        previewName: String,
        elementCount: Int,
    ): PageVersion
    suspend fun deletePageVersions(ids: List<Long>)
    suspend fun replacePageContent(noteId: Long, snapshot: PageSnapshot)
    suspend fun applyRecoveredStroke(
        operationId: String,
        noteId: Long,
        pageId: Long,
        stroke: StrokeDraft,
    ): Boolean
    suspend fun pruneAppliedRecoveryOperations(cutoff: Long)
    suspend fun saveStroke(noteId: Long, pageId: Long, stroke: StrokeDraft): Stroke
    suspend fun saveStrokes(noteId: Long, pageId: Long, strokes: List<StrokeDraft>): List<Stroke>
    suspend fun saveStrokesWithRecoveryIds(
        noteId: Long,
        pageId: Long,
        strokes: List<Pair<String, StrokeDraft>>,
    ): List<Stroke>
    suspend fun deleteStrokes(noteId: Long, strokes: List<Stroke>)
    suspend fun restoreStrokes(noteId: Long, strokes: List<Stroke>)
    suspend fun replaceStrokes(
        noteId: Long,
        removed: List<Stroke>,
        replacements: List<StrokeDraft>,
    ): List<Stroke>
    suspend fun clearStrokes(noteId: Long, pageId: Long)
    suspend fun addText(noteId: Long, pageId: Long, draft: CanvasTextDraft): CanvasText
    suspend fun addImage(noteId: Long, pageId: Long, draft: CanvasImageDraft): CanvasImage
    suspend fun updateStrokes(noteId: Long, strokes: List<Stroke>)
    suspend fun updateTexts(noteId: Long, texts: List<CanvasText>)
    suspend fun deleteTexts(noteId: Long, texts: List<CanvasText>)
    suspend fun restoreTexts(noteId: Long, texts: List<CanvasText>)
    suspend fun updateElements(noteId: Long, strokes: List<Stroke>, texts: List<CanvasText>)
    suspend fun deleteElements(noteId: Long, strokes: List<Stroke>, texts: List<CanvasText>)
    suspend fun restoreElements(noteId: Long, strokes: List<Stroke>, texts: List<CanvasText>)
    suspend fun copyElements(
        noteId: Long,
        pageId: Long,
        strokes: List<StrokeDraft>,
        texts: List<CanvasTextDraft>,
    ): Pair<List<Stroke>, List<CanvasText>>
    suspend fun updateElements(
        noteId: Long,
        strokes: List<Stroke>,
        texts: List<CanvasText>,
        images: List<CanvasImage>,
    )
    suspend fun deleteElements(
        noteId: Long,
        strokes: List<Stroke>,
        texts: List<CanvasText>,
        images: List<CanvasImage>,
    )
    suspend fun restoreElements(
        noteId: Long,
        strokes: List<Stroke>,
        texts: List<CanvasText>,
        images: List<CanvasImage>,
    )
    suspend fun copyElements(
        noteId: Long,
        pageId: Long,
        strokes: List<StrokeDraft>,
        texts: List<CanvasTextDraft>,
        images: List<CanvasImageDraft>,
    ): CopiedCanvasElements
}
