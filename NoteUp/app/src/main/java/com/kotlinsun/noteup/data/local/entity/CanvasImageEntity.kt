package com.kotlinsun.noteup.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "canvas_images",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["pageId", "elementIndex"], unique = true),
        Index(value = ["storageName"]),
    ],
)
data class CanvasImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val elementIndex: Int,
    val storageName: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val orientationDegrees: Int,
    val x: Float,
    val y: Float,
    val boxWidth: Float,
    val boxHeight: Float,
    val createdAt: Long,
    val updatedAt: Long,
)
