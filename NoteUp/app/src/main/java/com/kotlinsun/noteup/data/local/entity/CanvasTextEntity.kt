package com.kotlinsun.noteup.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "canvas_texts",
    foreignKeys = [ForeignKey(
        entity = PageEntity::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["pageId", "elementIndex"], unique = true)],
)
data class CanvasTextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val elementIndex: Int,
    val x: Float,
    val y: Float,
    val boxWidth: Float,
    val content: String,
    val colorArgb: Int,
    val textSizeSp: Float,
    val createdAt: Long,
    val updatedAt: Long,
)
