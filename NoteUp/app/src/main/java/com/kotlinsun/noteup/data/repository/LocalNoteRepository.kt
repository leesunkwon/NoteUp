package com.kotlinsun.noteup.data.repository

import androidx.room.withTransaction
import com.kotlinsun.noteup.data.local.NoteUpDatabase
import com.kotlinsun.noteup.data.local.codec.StrokePointCodec
import com.kotlinsun.noteup.data.local.entity.NoteEntity
import com.kotlinsun.noteup.data.local.entity.NotebookEntity
import com.kotlinsun.noteup.data.local.entity.PageEntity
import com.kotlinsun.noteup.data.local.entity.StrokeEntity
import com.kotlinsun.noteup.data.local.entity.CanvasTextEntity
import com.kotlinsun.noteup.domain.model.CanvasText
import com.kotlinsun.noteup.domain.model.CanvasTextDraft
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
    private val canvasTextDao = database.canvasTextDao()

    override fun observeNotebooks(): Flow<List<Notebook>> =
        notebookDao.observeAll().map { notebooks -> notebooks.map { it.toDomain() } }

    override fun observeNote(noteId: Long): Flow<Note?> =
        noteDao.observeById(noteId).map { it?.toDomain() }

    override fun observeAllNotes(query: String): Flow<List<Note>> =
        noteDao.observeAll(query).map { notes -> notes.map { it.toDomain() } }

    override fun observeUnfiledNotes(query: String): Flow<List<Note>> =
        noteDao.observeUnfiled(query).map { notes -> notes.map { it.toDomain() } }

    override fun observeNotes(notebookId: Long, query: String): Flow<List<Note>> =
        noteDao.observeByNotebook(notebookId, query).map { notes -> notes.map { it.toDomain() } }

    override fun observeTrashedNotes(query: String): Flow<List<Note>> =
        noteDao.observeTrash(query).map { notes -> notes.map { it.toDomain() } }

    override fun observePages(noteId: Long): Flow<List<Page>> =
        pageDao.observeByNote(noteId).map { pages -> pages.map { it.toDomain() } }

    override fun observeFirstPage(noteId: Long): Flow<Page?> =
        pageDao.observeFirstByNote(noteId).map { it?.toDomain() }

    override fun observeFirstPageIds(): Flow<Map<Long, Long>> =
        pageDao.observeFirstPageIds().map { rows -> rows.associate { it.noteId to it.pageId } }

    override fun observeStrokes(pageId: Long): Flow<List<Stroke>> =
        strokeDao.observeByPage(pageId).map { strokes ->
            strokes.mapNotNull { it.toDomainOrNull() }
        }

    override fun observeTexts(pageId: Long): Flow<List<CanvasText>> =
        canvasTextDao.observeByPage(pageId).map { values -> values.map { it.toDomain() } }

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

    override suspend fun moveNoteToTrash(noteId: Long) {
        check(noteDao.moveToTrash(noteId, System.currentTimeMillis()) == 1)
    }

    override suspend fun restoreNote(noteId: Long) {
        check(noteDao.restore(noteId, System.currentTimeMillis()) == 1)
    }

    override suspend fun permanentlyDeleteNote(noteId: Long): List<Long> =
        database.withTransaction {
            val pageIds = pageDao.getIdsByNoteIds(listOf(noteId))
            check(noteDao.deleteTrashedByIds(listOf(noteId)) == 1)
            pageIds
        }

    override suspend fun purgeExpiredNotes(cutoff: Long): List<Long> =
        database.withTransaction {
            val noteIds = noteDao.getExpiredIds(cutoff)
            if (noteIds.isEmpty()) return@withTransaction emptyList()
            val pageIds = pageDao.getIdsByNoteIds(noteIds)
            noteDao.deleteTrashedByIds(noteIds)
            pageIds
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

    override suspend fun deletePage(noteId: Long, pageId: Long) = database.withTransaction {
        val pages = pageDao.getByNote(noteId)
        require(pages.size > 1) { "The last page cannot be deleted" }
        require(pages.any { it.id == pageId }) { "Page does not belong to note" }
        pageDao.delete(pageId)
        val now = System.currentTimeMillis()
        pageDao.moveIndexesToTemporaryRange(noteId)
        pages.filterNot { it.id == pageId }.forEachIndexed { index, page ->
            pageDao.updateIndex(page.id, index, now)
        }
        noteDao.touch(noteId, now)
    }

    override suspend fun reorderPages(noteId: Long, orderedPageIds: List<Long>) =
        database.withTransaction {
            val pages = pageDao.getByNote(noteId)
            require(orderedPageIds.size == pages.size &&
                orderedPageIds.toSet() == pages.map { it.id }.toSet())
            val now = System.currentTimeMillis()
            pageDao.moveIndexesToTemporaryRange(noteId)
            orderedPageIds.forEachIndexed { index, pageId ->
                pageDao.updateIndex(pageId, index, now)
            }
            noteDao.touch(noteId, now)
        }

    override suspend fun getPage(pageId: Long): Page? = pageDao.getById(pageId)?.toDomain()

    override suspend fun getPages(noteId: Long): List<Page> =
        pageDao.getByNote(noteId).map { it.toDomain() }

    override suspend fun getStrokes(pageId: Long): List<Stroke> =
        strokeDao.getByPage(pageId).mapNotNull { it.toDomainOrNull() }

    override suspend fun getTexts(pageId: Long): List<CanvasText> =
        canvasTextDao.getByPage(pageId).map { it.toDomain() }

    override suspend fun saveStroke(
        noteId: Long,
        pageId: Long,
        stroke: StrokeDraft,
    ): Stroke = database.withTransaction {
        require(stroke.points.size >= 2) { "A stroke requires at least two points" }
        val now = System.currentTimeMillis()
        val strokeIndex = nextElementIndex(pageId)
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

    override suspend fun saveStrokes(
        noteId: Long,
        pageId: Long,
        strokes: List<StrokeDraft>,
    ): List<Stroke> {
        if (strokes.isEmpty()) return emptyList()
        return database.withTransaction {
            var nextIndex = nextElementIndex(pageId)
            val now = System.currentTimeMillis()
            strokes.map { stroke ->
                require(stroke.points.size >= 2)
                val entity = StrokeEntity(
                    pageId = pageId,
                    strokeIndex = nextIndex++,
                    toolType = stroke.tool.name,
                    colorArgb = stroke.colorArgb,
                    strokeWidth = stroke.width,
                    points = StrokePointCodec.encode(stroke.points),
                    createdAt = now,
                )
                entity.copy(id = strokeDao.insert(entity)).toDomainOrNull()
                    ?: error("Saved stroke could not be decoded")
            }.also { noteDao.touch(noteId, now) }
        }
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

    override suspend fun replaceStrokes(
        noteId: Long,
        removed: List<Stroke>,
        replacements: List<StrokeDraft>,
    ): List<Stroke> {
        if (removed.isEmpty()) return emptyList()
        return database.withTransaction {
            val pageId = removed.first().pageId
            require(removed.all { it.pageId == pageId })
            strokeDao.deleteByIds(removed.map(Stroke::id).distinct())
            var nextIndex = nextElementIndex(pageId)
            val now = System.currentTimeMillis()
            val inserted = replacements.map { draft ->
                require(draft.points.size >= 2)
                val entity = StrokeEntity(
                    pageId = pageId,
                    strokeIndex = nextIndex++,
                    toolType = draft.tool.name,
                    colorArgb = draft.colorArgb,
                    strokeWidth = draft.width,
                    points = StrokePointCodec.encode(draft.points),
                    createdAt = now,
                )
                entity.copy(id = strokeDao.insert(entity)).toDomainOrNull()
                    ?: error("Replacement stroke could not be decoded")
            }
            noteDao.touch(noteId, now)
            inserted
        }
    }

    override suspend fun clearStrokes(noteId: Long, pageId: Long) {
        database.withTransaction {
            strokeDao.deleteByPage(pageId)
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun addText(
        noteId: Long,
        pageId: Long,
        draft: CanvasTextDraft,
    ): CanvasText = database.withTransaction {
        val now = System.currentTimeMillis()
        val entity = CanvasTextEntity(
            pageId = pageId,
            elementIndex = nextElementIndex(pageId),
            x = draft.x,
            y = draft.y,
            boxWidth = draft.boxWidth,
            content = draft.content,
            colorArgb = draft.colorArgb,
            textSizeSp = draft.textSizeSp,
            createdAt = now,
            updatedAt = now,
        )
        val saved = entity.copy(id = canvasTextDao.insert(entity)).toDomain()
        noteDao.touch(noteId, now)
        saved
    }

    override suspend fun updateStrokes(noteId: Long, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        database.withTransaction {
            strokeDao.updateAll(strokes.map { it.toEntity() })
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun updateTexts(noteId: Long, texts: List<CanvasText>) {
        if (texts.isEmpty()) return
        database.withTransaction {
            canvasTextDao.updateAll(texts.map { it.toEntity() })
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun deleteTexts(noteId: Long, texts: List<CanvasText>) {
        if (texts.isEmpty()) return
        database.withTransaction {
            canvasTextDao.deleteByIds(texts.map(CanvasText::id))
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun restoreTexts(noteId: Long, texts: List<CanvasText>) {
        if (texts.isEmpty()) return
        database.withTransaction {
            canvasTextDao.insertAll(texts.map { it.toEntity() })
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun updateElements(
        noteId: Long,
        strokes: List<Stroke>,
        texts: List<CanvasText>,
    ) {
        if (strokes.isEmpty() && texts.isEmpty()) return
        database.withTransaction {
            if (strokes.isNotEmpty()) strokeDao.updateAll(strokes.map { it.toEntity() })
            if (texts.isNotEmpty()) canvasTextDao.updateAll(texts.map { it.toEntity() })
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun deleteElements(
        noteId: Long,
        strokes: List<Stroke>,
        texts: List<CanvasText>,
    ) {
        if (strokes.isEmpty() && texts.isEmpty()) return
        database.withTransaction {
            if (strokes.isNotEmpty()) strokeDao.deleteByIds(strokes.map(Stroke::id))
            if (texts.isNotEmpty()) canvasTextDao.deleteByIds(texts.map(CanvasText::id))
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun restoreElements(
        noteId: Long,
        strokes: List<Stroke>,
        texts: List<CanvasText>,
    ) {
        if (strokes.isEmpty() && texts.isEmpty()) return
        database.withTransaction {
            if (strokes.isNotEmpty()) {
                check(strokeDao.insertAll(strokes.map { it.toEntity() }).none { it == -1L })
            }
            if (texts.isNotEmpty()) canvasTextDao.insertAll(texts.map { it.toEntity() })
            noteDao.touch(noteId, System.currentTimeMillis())
        }
    }

    override suspend fun copyElements(
        noteId: Long,
        pageId: Long,
        strokes: List<StrokeDraft>,
        texts: List<CanvasTextDraft>,
    ): Pair<List<Stroke>, List<CanvasText>> = database.withTransaction {
        val now = System.currentTimeMillis()
        var elementIndex = nextElementIndex(pageId)
        val savedStrokes = strokes.map { draft ->
            val entity = StrokeEntity(
                pageId = pageId,
                strokeIndex = elementIndex++,
                toolType = draft.tool.name,
                colorArgb = draft.colorArgb,
                strokeWidth = draft.width,
                points = StrokePointCodec.encode(draft.points),
                createdAt = now,
            )
            entity.copy(id = strokeDao.insert(entity)).toDomainOrNull()
                ?: error("Copied stroke could not be decoded")
        }
        val savedTexts = texts.map { draft ->
            val entity = CanvasTextEntity(
                pageId = pageId,
                elementIndex = elementIndex++,
                x = draft.x,
                y = draft.y,
                boxWidth = draft.boxWidth,
                content = draft.content,
                colorArgb = draft.colorArgb,
                textSizeSp = draft.textSizeSp,
                createdAt = now,
                updatedAt = now,
            )
            entity.copy(id = canvasTextDao.insert(entity)).toDomain()
        }
        noteDao.touch(noteId, now)
        savedStrokes to savedTexts
    }

    private suspend fun nextElementIndex(pageId: Long): Int =
        maxOf(strokeDao.maximumIndex(pageId), canvasTextDao.maximumIndex(pageId)) + 1

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
        deletedAt = deletedAt,
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

    private fun CanvasTextEntity.toDomain() = CanvasText(
        id, pageId, elementIndex, x, y, boxWidth, content, colorArgb, textSizeSp, createdAt, updatedAt,
    )

    private fun CanvasText.toEntity() = CanvasTextEntity(
        id, pageId, elementIndex, x, y, boxWidth, content, colorArgb, textSizeSp, createdAt, updatedAt,
    )
}
