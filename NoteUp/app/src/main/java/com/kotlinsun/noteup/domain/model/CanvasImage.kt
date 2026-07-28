package com.kotlinsun.noteup.domain.model

data class CanvasImage(
    val id: Long,
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

data class CanvasImageDraft(
    val storageName: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val orientationDegrees: Int,
    val x: Float,
    val y: Float,
    val boxWidth: Float,
    val boxHeight: Float,
)

data class ImportedCanvasImage(
    val storageName: String,
    val width: Int,
    val height: Int,
    val orientationDegrees: Int,
)

data class CopiedCanvasElements(
    val strokes: List<Stroke>,
    val texts: List<CanvasText>,
    val images: List<CanvasImage>,
)
