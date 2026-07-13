package com.kotlinsun.noteup.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.ItemNoteBinding
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.data.thumbnail.PageThumbnailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class NoteAdapter(
    private val onClick: (Note) -> Unit,
    private val onMoreClick: (View, Note) -> Unit,
    private val thumbnailStore: PageThumbnailStore,
) : ListAdapter<DashboardNoteItem, NoteAdapter.NoteViewHolder>(DiffCallback) {

    private val dateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(
        private val binding: ItemNoteBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var thumbnailJob: Job? = null
        private var boundPageId: Long? = null

        fun bind(item: DashboardNoteItem) = with(binding) {
            val note = item.note
            noteTitle.text = note.title
            noteModifiedAt.text = root.context.getString(
                R.string.note_modified_at,
                dateFormatter.format(Date(note.updatedAt)),
            )
            noteCard.setOnClickListener { onClick(note) }
            noteMoreButton.setOnClickListener { onMoreClick(it, note) }
            boundPageId = item.firstPageId
            thumbnailJob?.cancel()
            notePreview.setImageDrawable(null)
            notePreview.setBackgroundResource(R.drawable.bg_note_preview)
            item.firstPageId?.let { pageId ->
                thumbnailJob = scope.launch {
                    val bitmap = withContext(Dispatchers.IO) { thumbnailStore.load(pageId) }
                    if (boundPageId == pageId && bitmap != null) notePreview.setImageBitmap(bitmap)
                }
            }
        }

        fun recycle() { thumbnailJob?.cancel(); boundPageId = null }
    }

    override fun onViewRecycled(holder: NoteViewHolder) { holder.recycle() }

    private object DiffCallback : DiffUtil.ItemCallback<DashboardNoteItem>() {
        override fun areItemsTheSame(oldItem: DashboardNoteItem, newItem: DashboardNoteItem) =
            oldItem.note.id == newItem.note.id
        override fun areContentsTheSame(oldItem: DashboardNoteItem, newItem: DashboardNoteItem) =
            oldItem == newItem
    }
}
