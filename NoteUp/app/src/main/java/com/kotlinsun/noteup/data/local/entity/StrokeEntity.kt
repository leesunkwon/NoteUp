package com.kotlinsun.noteup.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["pageId", "strokeIndex"], unique = true),
    ],
)
data class StrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val strokeIndex: Int,
    val toolType: String,
    val colorArgb: Int,
    val strokeWidth: Float,
    val points: ByteArray,
    val createdAt: Long,
)
