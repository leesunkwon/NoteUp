package com.kotlinsun.noteup.data.trash

import com.kotlinsun.noteup.data.preferences.TrashRetentionStore
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailService
import com.kotlinsun.noteup.domain.repository.NoteRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrashCleanupService(
    private val repository: NoteRepository,
    private val retentionStore: TrashRetentionStore,
    private val thumbnailService: PageThumbnailService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)

    fun request() {
        if (!running.compareAndSet(false, true)) {
            rerunRequested.set(true)
            return
        }
        scope.launch {
            do {
                rerunRequested.set(false)
                runCatching { cleanup() }
            } while (rerunRequested.get())
            running.set(false)
            if (rerunRequested.get()) request()
        }
    }

    private suspend fun cleanup() {
        val days = retentionStore.current().days ?: return
        val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
        repository.purgeExpiredNotes(cutoff).forEach { pageId ->
            runCatching { thumbnailService.delete(pageId) }
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
