package com.kotlinsun.noteup.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "page_versions",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["pageId", "createdAt"]),
        Index(value = ["snapshotName"], unique = true),
    ],
)
data class PageVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val createdAt: Long,
    val reason: String,
    val snapshotName: String,
    val previewName: String,
    val elementCount: Int,
)
