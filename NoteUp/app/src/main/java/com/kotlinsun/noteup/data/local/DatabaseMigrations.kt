package com.kotlinsun.noteup.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `strokes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `pageId` INTEGER NOT NULL,
                    `strokeIndex` INTEGER NOT NULL,
                    `toolType` TEXT NOT NULL,
                    `colorArgb` INTEGER NOT NULL,
                    `strokeWidth` REAL NOT NULL,
                    `points` BLOB NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_strokes_pageId_strokeIndex`
                ON `strokes` (`pageId`, `strokeIndex`)
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `notes` ADD COLUMN `deletedAt` INTEGER")
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notes_deletedAt` ON `notes` (`deletedAt`)",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `canvas_texts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pageId` INTEGER NOT NULL, `elementIndex` INTEGER NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, `boxWidth` REAL NOT NULL, `content` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `textSizeSp` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_canvas_texts_pageId_elementIndex` ON `canvas_texts` (`pageId`, `elementIndex`)")
        }
    }
}
