package com.kotlinsun.noteup.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kotlinsun.noteup.data.local.dao.NoteDao
import com.kotlinsun.noteup.data.local.dao.NotebookDao
import com.kotlinsun.noteup.data.local.dao.PageDao
import com.kotlinsun.noteup.data.local.dao.StrokeDao
import com.kotlinsun.noteup.data.local.dao.CanvasTextDao
import com.kotlinsun.noteup.data.local.dao.ImportedPdfDao
import com.kotlinsun.noteup.data.local.dao.PdfPageBackgroundDao
import com.kotlinsun.noteup.data.local.entity.CanvasTextEntity
import com.kotlinsun.noteup.data.local.entity.NoteEntity
import com.kotlinsun.noteup.data.local.entity.NotebookEntity
import com.kotlinsun.noteup.data.local.entity.PageEntity
import com.kotlinsun.noteup.data.local.entity.StrokeEntity
import com.kotlinsun.noteup.data.local.entity.ImportedPdfEntity
import com.kotlinsun.noteup.data.local.entity.PdfPageBackgroundEntity

@Database(
    entities = [NotebookEntity::class, NoteEntity::class, PageEntity::class, StrokeEntity::class,
        CanvasTextEntity::class, ImportedPdfEntity::class, PdfPageBackgroundEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class NoteUpDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun noteDao(): NoteDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun canvasTextDao(): CanvasTextDao
    abstract fun importedPdfDao(): ImportedPdfDao
    abstract fun pdfPageBackgroundDao(): PdfPageBackgroundDao
}
