package com.kotlinsun.noteup.data.version

import com.kotlinsun.noteup.data.preferences.VersionHistoryStore
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailService
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
import com.kotlinsun.noteup.domain.model.PageSnapshot
import com.kotlinsun.noteup.domain.model.PageVersion
import com.kotlinsun.noteup.domain.model.PageVersionReason
import com.kotlinsun.noteup.domain.repository.NoteRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PageVersionService(
    private val repository: NoteRepository,
    private val snapshotStore: PageSnapshotStore,
    private val thumbnailStore: PageThumbnailStore,
    private val thumbnailService: PageThumbnailService,
    private val settingsStore: VersionHistoryStore,
) {
    private val pageLocks = ConcurrentHashMap<Long, Mutex>()
    private val lastAutomaticCapture = ConcurrentHashMap<Long, Long>()

    suspend fun capture(
        pageId: Long,
        reason: PageVersionReason = PageVersionReason.AUTOMATIC,
        force: Boolean = false,
    ): PageVersion? {
        if (!settingsStore.current().enabled && reason == PageVersionReason.AUTOMATIC) return null
        return pageLocks.getOrPut(pageId) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            if (!force && reason == PageVersionReason.AUTOMATIC &&
                now - (lastAutomaticCapture[pageId] ?: 0L) < AUTOMATIC_INTERVAL_MILLIS
            ) return@withLock null
            val page = repository.getPage(pageId) ?: return@withLock null
            val snapshot = PageSnapshot(
                pageId = pageId,
                template = page.templateType,
                strokes = repository.getStrokes(pageId),
                texts = repository.getTexts(pageId),
                images = repository.getImages(pageId),
            )
            if (snapshot.strokes.isEmpty() && snapshot.texts.isEmpty() && snapshot.images.isEmpty()) {
                lastAutomaticCapture[pageId] = now
                return@withLock null
            }
            val preview = thumbnailStore.load(pageId)
            val stored = snapshotStore.write(snapshot, preview)
            val version = runCatching {
                repository.addPageVersion(
                    pageId = pageId,
                    createdAt = now,
                    reason = reason,
                    snapshotName = stored.snapshotName,
                    previewName = stored.previewName,
                    elementCount = snapshot.strokes.size + snapshot.texts.size + snapshot.images.size,
                )
            }.getOrElse { error ->
                snapshotStore.delete(listOf(stored.snapshotName), listOf(stored.previewName))
                throw error
            }
            lastAutomaticCapture[pageId] = now
            prune(pageId)
            version
        }
    }

    suspend fun restore(noteId: Long, versionId: Long) {
        val version = requireNotNull(repository.getPageVersion(versionId))
        val snapshot = requireNotNull(snapshotStore.read(version.snapshotName))
        capture(version.pageId, PageVersionReason.BEFORE_RESTORE, force = true)
        pageLocks.getOrPut(version.pageId) { Mutex() }.withLock {
            repository.replacePageContent(noteId, snapshot)
            thumbnailService.request(version.pageId)
        }
    }

    suspend fun loadPreview(version: PageVersion) = snapshotStore.loadPreview(version.previewName)

    suspend fun cleanupOrphans() {
        val versions = repository.getAllPageVersions()
        snapshotStore.cleanupOrphans(
            versions.flatMap { listOf(it.snapshotName, it.previewName) }
                .filter(String::isNotBlank)
                .toSet(),
        )
    }

    suspend fun referencedImageStorageNames(): Set<String> = snapshotStore.referencedImageNames(
        repository.getAllPageVersions().map(PageVersion::snapshotName),
    )

    fun sizeBytes(): Long = snapshotStore.sizeBytes()

    private suspend fun prune(pageId: Long) {
        val versions = repository.getPageVersions(pageId)
        val remove = versions.drop(settingsStore.current().maximumVersionsPerPage)
        if (remove.isEmpty()) return
        repository.deletePageVersions(remove.map(PageVersion::id))
        snapshotStore.delete(
            remove.map(PageVersion::snapshotName),
            remove.map(PageVersion::previewName),
        )
    }

    private companion object {
        const val AUTOMATIC_INTERVAL_MILLIS = 5L * 60 * 1000
    }
}
