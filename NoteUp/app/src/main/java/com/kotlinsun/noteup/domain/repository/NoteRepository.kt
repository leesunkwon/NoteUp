package com.kotlinsun.noteup.domain.repository

import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokeDraft
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
    suspend fun createNotebook(name: String): Long
    suspend fun renameNotebook(notebookId: Long, name: String)
    suspend fun deleteNotebook(notebookId: Long)
    suspend fun createNote(title: String, notebookId: Long?): Long
    suspend fun renameNote(noteId: Long, title: String)
    suspend fun moveNote(noteId: Long, notebookId: Long?)
    suspend fun moveNoteToTrash(noteId: Long)
    suspend fun restoreNote(noteId: Long)
    suspend fun permanentlyDeleteNote(noteId: Long): List<Long>
    suspend fun purgeExpiredNotes(cutoff: Long): List<Long>
    suspend fun createPage(noteId: Long, template: PageTemplate): Long
    suspend fun updatePageTemplate(pageId: Long, template: PageTemplate)
    suspend fun deletePage(noteId: Long, pageId: Long)
    suspend fun reorderPages(noteId: Long, orderedPageIds: List<Long>)
    suspend fun getPage(pageId: Long): Page?
    suspend fun getPages(noteId: Long): List<Page>
    suspend fun getStrokes(pageId: Long): List<Stroke>
    suspend fun saveStroke(noteId: Long, pageId: Long, stroke: StrokeDraft): Stroke
    suspend fun saveStrokes(noteId: Long, pageId: Long, strokes: List<StrokeDraft>): List<Stroke>
    suspend fun deleteStrokes(noteId: Long, strokes: List<Stroke>)
    suspend fun restoreStrokes(noteId: Long, strokes: List<Stroke>)
    suspend fun replaceStrokes(
        noteId: Long,
        removed: List<Stroke>,
        replacements: List<StrokeDraft>,
    ): List<Stroke>
    suspend fun clearStrokes(noteId: Long, pageId: Long)
}
