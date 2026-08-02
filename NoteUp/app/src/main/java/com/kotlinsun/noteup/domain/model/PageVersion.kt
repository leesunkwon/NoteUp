package com.kotlinsun.noteup.domain.model

data class PageVersion(
    val id: Long,
    val pageId: Long,
    val createdAt: Long,
    val reason: PageVersionReason,
    val snapshotName: String,
    val previewName: String,
    val elementCount: Int,
)

enum class PageVersionReason {
    AUTOMATIC,
    BEFORE_RESTORE,
    RECOVERY,
}

data class PageSnapshot(
    val pageId: Long,
    val template: PageTemplate,
    val strokes: List<Stroke>,
    val texts: List<CanvasText>,
    val images: List<CanvasImage>,
)

data class RecoveryEntry(
    val operationId: String,
    val noteId: Long,
    val pageId: Long,
    val createdAt: Long,
    val stroke: StrokeDraft,
)

data class RecoverySummary(
    val entryCount: Int,
    val noteCount: Int,
    val newestAt: Long,
)

data class StorageUsage(
    val totalBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val recoveryBytes: Long = 0L,
    val versionBytes: Long = 0L,
    val availableBytes: Long = 0L,
)
