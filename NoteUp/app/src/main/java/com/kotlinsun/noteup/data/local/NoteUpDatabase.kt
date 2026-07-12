package com.kotlinsun.noteup.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kotlinsun.noteup.data.local.dao.NoteDao
import com.kotlinsun.noteup.data.local.dao.NotebookDao
import com.kotlinsun.noteup.data.local.dao.PageDao
import com.kotlinsun.noteup.data.local.entity.NoteEntity
import com.kotlinsun.noteup.data.local.entity.NotebookEntity
import com.kotlinsun.noteup.data.local.entity.PageEntity

@Database(
    entities = [NotebookEntity::class, NoteEntity::class, PageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NoteUpDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun noteDao(): NoteDao
    abstract fun pageDao(): PageDao
}
