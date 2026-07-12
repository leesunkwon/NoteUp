package com.kotlinsun.noteup.data.repository

import androidx.room.withTransaction
import com.kotlinsun.noteup.data.local.NoteUpDatabase
import com.kotlinsun.noteup.data.local.codec.StrokePointCodec
import com.kotlinsun.noteup.data.local.entity.NoteEntity
import com.kotlinsun.noteup.data.local.entity.NotebookEntity
import com.kotlinsun.noteup.data.local.entity.PageEntity
import com.kotlinsun.noteup.data.local.entity.StrokeEntity
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook
import com.kotlinsun.noteup.domain.model.Page
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.Stroke
import com.kotlinsun.noteup.domain.model.StrokeDraft
import com.kotlinsun.noteup.domain.model.StrokeTool
import com.kotlinsun.noteup.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalNoteRepository(
    private val database: NoteUpDatabase,
) : NoteRepository {

    private val notebookDao = database.notebookDao()
    private val noteDao = database.noteDao()
    private val pageDao = database.pageDao()
    private val strokeDao = database.strokeDao()

    override fun observeNotebooks(): Flow<List<Notebook>> =
        notebookDao.observeAll().map { notebooks -> notebooks.map { it.toDomain() } }

    override fun observeNote(noteId: Long): Flow<Note?> =
        noteDao.observeById(noteId).map { it?.toDomain() }

    override fun observeAllNotes(): Flow<List<Note>> =
        noteDao.observeAll().map { notes -> notes.map { it.toDomain() } }

    override fun observeUnfiledNotes(): Flow<List<Note>> =
        noteDao.observeUnfiled().map { notes -> notes.map { it.toDomain() } }

    override fun observeNotes(notebookId: Long): Flow<List<Note>> =
        noteDao.observeByNotebook(notebookId).map { notes -> notes.map { it.toDomain() } }

    override fun observePages(noteId: Long): Flow<List<Page>> =
        pageDao.observeByNote(noteId).map { pages -> pages.map { it.toDomain() } }

    override fun observeFirstPage(noteId: Long): Flow<Page?> =
        pageDao.observeFirstByNote(noteId).map { it?.toDomain() }

    override fun observeStrokes(pageId: Long): Flow<List<Stroke>> =
        strokeDao.observeByPage(pageId).map { strokes ->
            strokes.mapNotNull { it.toDomainOrNull() }
        }

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

    override suspend fun saveStroke(
        noteId: Long,
        pageId: Long,
        stroke: StrokeDraft,
    ): Stroke = database.withTransaction {
        require(stroke.points.size >= 2) { "A stroke requires at least two points" }
        val now = System.currentTimeMillis()
        val strokeIndex = strokeDao.nextStrokeIndex(pageId)
        val entity = StrokeEntity(
            pageId = pageId,
            strokeIndex = strokeIndex,
            toolType = stroke.tool.name,
            colorArgb = stroke.colorArgb,
            strokeWidth = stroke.width,
            points = StrokePointCodec.encode(stroke.points),
            createdAt = now,
        )
        val strokeId = strokeDao.insert(entity)
        noteDao.touch(noteId, now)
        entity.copy(id = strokeId).toDomainOrNull()
            ?: error("Saved stroke could not be decoded")
    }

    override suspend fun deleteStrokes(noteId: Long, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        database.withTransaction {
            strokeDao.deleteByIds(strokes.map(Stroke::id).distinct())
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun restoreStrokes(noteId: Long, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        database.withTransaction {
            val restoredIds = strokeDao.insertAll(
                strokes.distinctBy(Stroke::id).map { it.toEntity() },
            )
            check(restoredIds.none { it == -1L }) { "A stroke could not be restored" }
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun clearStrokes(noteId: Long, pageId: Long) {
        database.withTransaction {
            strokeDao.deleteByPage(pageId)
            noteDao.touch(noteId, System.currentTimeMillis())
        }
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

    private fun StrokeEntity.toDomainOrNull(): Stroke? = runCatching {
        Stroke(
            id = id,
            pageId = pageId,
            strokeIndex = strokeIndex,
            tool = StrokeTool.valueOf(toolType),
            colorArgb = colorArgb,
            width = strokeWidth,
            points = StrokePointCodec.decode(points),
            createdAt = createdAt,
        )
    }.getOrNull()

    private fun Stroke.toEntity() = StrokeEntity(
        id = id,
        pageId = pageId,
        strokeIndex = strokeIndex,
        toolType = tool.name,
        colorArgb = colorArgb,
        strokeWidth = width,
        points = StrokePointCodec.encode(points),
        createdAt = createdAt,
    )
}
