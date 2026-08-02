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

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `imported_pdfs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, `storageName` TEXT NOT NULL, `displayName` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_imported_pdfs_noteId` ON `imported_pdfs` (`noteId`)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_imported_pdfs_storageName` ON `imported_pdfs` (`storageName`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `pdf_page_backgrounds` (`pageId` INTEGER NOT NULL, `pdfId` INTEGER NOT NULL, `sourcePageIndex` INTEGER NOT NULL, `widthPoints` INTEGER NOT NULL, `heightPoints` INTEGER NOT NULL, PRIMARY KEY(`pageId`), FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`pdfId`) REFERENCES `imported_pdfs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_pdf_page_backgrounds_pdfId` ON `pdf_page_backgrounds` (`pdfId`)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pdf_page_backgrounds_pdfId_sourcePageIndex` ON `pdf_page_backgrounds` (`pdfId`, `sourcePageIndex`)")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `canvas_images` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `pageId` INTEGER NOT NULL,
                    `elementIndex` INTEGER NOT NULL,
                    `storageName` TEXT NOT NULL,
                    `originalWidth` INTEGER NOT NULL,
                    `originalHeight` INTEGER NOT NULL,
                    `orientationDegrees` INTEGER NOT NULL,
                    `x` REAL NOT NULL,
                    `y` REAL NOT NULL,
                    `boxWidth` REAL NOT NULL,
                    `boxHeight` REAL NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_canvas_images_pageId_elementIndex`
                ON `canvas_images` (`pageId`, `elementIndex`)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_canvas_images_storageName`
                ON `canvas_images` (`storageName`)
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `page_versions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `pageId` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `reason` TEXT NOT NULL,
                    `snapshotName` TEXT NOT NULL,
                    `previewName` TEXT NOT NULL,
                    `elementCount` INTEGER NOT NULL,
                    FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_page_versions_pageId_createdAt` " +
                    "ON `page_versions` (`pageId`, `createdAt`)",
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_page_versions_snapshotName` " +
                    "ON `page_versions` (`snapshotName`)",
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `applied_recovery_operations` (
                    `operationId` TEXT NOT NULL,
                    `appliedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`operationId`)
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_applied_recovery_operations_appliedAt` " +
                    "ON `applied_recovery_operations` (`appliedAt`)",
            )
        }
    }
}
