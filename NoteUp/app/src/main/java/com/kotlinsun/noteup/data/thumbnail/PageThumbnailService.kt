package com.kotlinsun.noteup.data.thumbnail

import android.graphics.Bitmap
import android.graphics.Canvas
import com.kotlinsun.noteup.domain.repository.NoteRepository
import com.kotlinsun.noteup.rendering.PageRenderer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class PageThumbnailService(
    private val repository: NoteRepository,
    private val store: PageThumbnailStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Long>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<Long>()
    private val renderer = PageRenderer()

    init {
        scope.launch {
            for (pageId in requests) {
                queued -= pageId
                runCatching { generate(pageId) }
            }
        }
    }

    fun request(pageId: Long) {
        if (queued.add(pageId)) requests.trySend(pageId)
    }

    fun ensure(pageIds: Collection<Long>) {
        pageIds.filterNot(store::exists).forEach(::request)
    }

    suspend fun delete(pageId: Long) = store.delete(pageId)

    private suspend fun generate(pageId: Long) {
        val page = repository.getPage(pageId)
        if (page == null) {
            store.delete(pageId)
            return
        }
        val strokes = repository.getStrokes(pageId)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap), WIDTH, HEIGHT, THUMBNAIL_DENSITY, page.templateType, strokes)
        store.write(pageId, bitmap)
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val THUMBNAIL_DENSITY = 0.75f
    }
}
