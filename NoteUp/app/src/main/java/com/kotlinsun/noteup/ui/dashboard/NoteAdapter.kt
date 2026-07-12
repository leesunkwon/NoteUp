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
import java.text.DateFormat
import java.util.Date

class NoteAdapter(
    private val onClick: (Note) -> Unit,
    private val onMoreClick: (View, Note) -> Unit,
) : ListAdapter<Note, NoteAdapter.NoteViewHolder>(DiffCallback) {

    private val dateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

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
        fun bind(note: Note) = with(binding) {
            noteTitle.text = note.title
            noteModifiedAt.text = root.context.getString(
                R.string.note_modified_at,
                dateFormatter.format(Date(note.updatedAt)),
            )
            noteCard.setOnClickListener { onClick(note) }
            noteMoreButton.setOnClickListener { onMoreClick(it, note) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}
