package com.kotlinsun.noteup.data.recovery

import com.kotlinsun.noteup.data.thumbnail.PageThumbnailService
import com.kotlinsun.noteup.domain.model.RecoverySummary
import com.kotlinsun.noteup.domain.repository.NoteRepository
import com.kotlinsun.noteup.data.version.PageVersionService
import com.kotlinsun.noteup.domain.model.PageVersionReason
import java.util.concurrent.atomic.AtomicBoolean

class RecoveryService(
    private val repository: NoteRepository,
    private val journal: RecoveryJournal,
    private val thumbnailService: PageThumbnailService,
    private val versionService: PageVersionService,
) {
    private val running = AtomicBoolean(false)

    suspend fun inspect(): RecoverySummary? = journal.summary()

    suspend fun recoverAll(): RecoveryResult {
        if (!running.compareAndSet(false, true)) return RecoveryResult(0, 0)
        return try {
            var recovered = 0
            var skipped = 0
            val completed = mutableListOf<String>()
            val changedPages = mutableSetOf<Long>()
            journal.entries().forEach { entry ->
                runCatching {
                    repository.applyRecoveredStroke(
                        entry.operationId,
                        entry.noteId,
                        entry.pageId,
                        entry.stroke,
                    )
                }.onSuccess { inserted ->
                    if (inserted) {
                        recovered++
                        changedPages += entry.pageId
                    } else {
                        skipped++
                    }
                    completed += entry.operationId
                }
            }
            journal.delete(completed)
            changedPages.forEach { pageId ->
                thumbnailService.request(pageId)
                runCatching {
                    versionService.capture(pageId, PageVersionReason.RECOVERY, force = true)
                }
            }
            repository.pruneAppliedRecoveryOperations(
                System.currentTimeMillis() - APPLIED_OPERATION_RETENTION_MILLIS,
            )
            RecoveryResult(recovered, skipped)
        } finally {
            running.set(false)
        }
    }

    suspend fun discardAll() = journal.discardAll()

    data class RecoveryResult(val recovered: Int, val skipped: Int)

    private companion object {
        const val APPLIED_OPERATION_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
