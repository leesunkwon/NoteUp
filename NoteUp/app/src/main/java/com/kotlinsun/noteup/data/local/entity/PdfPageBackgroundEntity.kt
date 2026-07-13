package com.kotlinsun.noteup.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "pdf_page_backgrounds",
    primaryKeys = ["pageId"],
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ImportedPdfEntity::class,
            parentColumns = ["id"],
            childColumns = ["pdfId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pdfId"), Index(value = ["pdfId", "sourcePageIndex"], unique = true)],
)
data class PdfPageBackgroundEntity(
    val pageId: Long,
    val pdfId: Long,
    val sourcePageIndex: Int,
    val widthPoints: Int,
    val heightPoints: Int,
)
