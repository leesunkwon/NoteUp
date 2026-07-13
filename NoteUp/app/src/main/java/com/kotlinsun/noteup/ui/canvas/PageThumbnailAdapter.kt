package com.kotlinsun.noteup.ui.canvas

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
import com.kotlinsun.noteup.databinding.ItemPageThumbnailBinding
import com.kotlinsun.noteup.domain.model.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PageThumbnailAdapter(
    private val store: PageThumbnailStore,
    private val onClick: (Page) -> Unit,
    private val onDelete: (Page) -> Unit,
    private val onOrderChanged: (List<Long>) -> Unit,
) : RecyclerView.Adapter<PageThumbnailAdapter.Holder>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val items = mutableListOf<PageListItem>()

    fun submitPages(pages: List<Page>, selectedPageId: Long, revisions: Map<Long, Long>) {
        val newItems = pages.map {
            PageListItem(it, it.id == selectedPageId, revisions[it.id] ?: 0L)
        }
        if (items == newItems) return
        items.clear()
        items += newItems
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun commitOrder() = onOrderChanged(items.map { it.page.id })

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemPageThumbnailBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position)
    override fun getItemCount() = items.size
    override fun onViewRecycled(holder: Holder) = holder.recycle()
    inner class Holder(private val binding: ItemPageThumbnailBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var job: Job? = null
        private var pageId: Long? = null

        fun bind(item: PageListItem, position: Int) = with(binding) {
            pageId = item.page.id
            pageNumber.text = root.context.getString(R.string.page_number, position + 1)
            pageTemplate.text = root.context.getString(
                when (item.page.templateType) {
                    com.kotlinsun.noteup.domain.model.PageTemplate.BLANK -> R.string.template_blank
                    com.kotlinsun.noteup.domain.model.PageTemplate.LINED -> R.string.template_lined
                    com.kotlinsun.noteup.domain.model.PageTemplate.GRID -> R.string.template_grid
                },
            )
            root.isActivated = item.selected
            deletePageButton.isEnabled = items.size > 1
            root.setOnClickListener { onClick(item.page) }
            deletePageButton.setOnClickListener { onDelete(item.page) }
            pagePreview.setImageDrawable(null)
            pagePreview.setBackgroundResource(R.drawable.bg_note_preview)
            job?.cancel()
            job = scope.launch {
                val bitmap = store.load(item.page.id)
                if (pageId == item.page.id && bitmap != null) pagePreview.setImageBitmap(bitmap)
            }
        }

        fun recycle() { job?.cancel(); pageId = null }
    }

    private data class PageListItem(val page: Page, val selected: Boolean, val revision: Long)
}
