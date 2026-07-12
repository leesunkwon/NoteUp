package com.kotlinsun.noteup.data.repository

import androidx.room.withTransaction
import com.kotlinsun.noteup.data.local.NoteUpDatabase
import com.kotlinsun.noteup.data.local.entity.NoteEntity
import com.kotlinsun.noteup.data.local.entity.NotebookEntity
import com.kotlinsun.noteup.data.local.entity.PageEntity
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalNoteRepository(
    private val database: NoteUpDatabase,
) : NoteRepository {

    private val notebookDao = database.notebookDao()
    private val noteDao = database.noteDao()
    private val pageDao = database.pageDao()

    override fun observeNotebooks(): Flow<List<Notebook>> =
        notebookDao.observeAll().map { notebooks -> notebooks.map { it.toDomain() } }

    override fun observeAllNotes(): Flow<List<Note>> =
        noteDao.observeAll().map { notes -> notes.map { it.toDomain() } }

    override fun observeUnfiledNotes(): Flow<List<Note>> =
        noteDao.observeUnfiled().map { notes -> notes.map { it.toDomain() } }

    override fun observeNotes(notebookId: Long): Flow<List<Note>> =
        noteDao.observeByNotebook(notebookId).map { notes -> notes.map { it.toDomain() } }

    override fun observePages(noteId: Long): Flow<List<Page>> =
        pageDao.observeByNote(noteId).map { pages -> pages.map { it.toDomain() } }

    override suspend fun createNotebook(name: String): Long {
        val now = System.currentTimeMillis()
        return notebookDao.insert(
            NotebookEntity(name = name, createdAt = now, updatedAt = now),
        )
    }

    override suspend fun renameNotebook(notebookId: Long, name: String) {
        notebookDao.rename(notebookId, name, System.currentTimeMillis())
    }

    override suspend fun deleteNotebook(notebookId: Long) {
        notebookDao.delete(notebookId)
    }

    override suspend fun createNote(title: String, notebookId: Long?): Long =
        database.withTransaction {
            val now = System.currentTimeMillis()
            val noteId = noteDao.insert(
                NoteEntity(
                    notebookId = notebookId,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            pageDao.insert(
                PageEntity(
                    noteId = noteId,
                    pageIndex = 0,
                    templateType = PageTemplate.BLANK.name,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            noteId
        }

    override suspend fun renameNote(noteId: Long, title: String) {
        noteDao.rename(noteId, title, System.currentTimeMillis())
    }

    override suspend fun moveNote(noteId: Long, notebookId: Long?) {
        noteDao.move(noteId, notebookId, System.currentTimeMillis())
    }

    override suspend fun deleteNote(noteId: Long) {
        noteDao.delete(noteId)
    }

    override suspend fun createPage(noteId: Long, template: PageTemplate): Long =
        database.withTransaction {
            val now = System.currentTimeMillis()
            val pageId = pageDao.insert(
                PageEntity(
                    noteId = noteId,
                    pageIndex = pageDao.nextPageIndex(noteId),
                    templateType = template.name,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            noteDao.touch(noteId, now)
            pageId
        }

    override suspend fun updatePageTemplate(pageId: Long, template: PageTemplate) {
        pageDao.updateTemplate(pageId, template.name, System.currentTimeMillis())
    }

    override suspend fun deletePage(pageId: Long) {
        pageDao.delete(pageId)
    }

    private fun NotebookEntity.toDomain() = Notebook(
        id = id,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun NoteEntity.toDomain() = Note(
        id = id,
        notebookId = notebookId,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun PageEntity.toDomain() = Page(
        id = id,
        noteId = noteId,
        pageIndex = pageIndex,
        templateType = PageTemplate.valueOf(templateType),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
