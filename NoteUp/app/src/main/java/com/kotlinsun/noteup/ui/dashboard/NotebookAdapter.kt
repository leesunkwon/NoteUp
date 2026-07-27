package com.kotlinsun.noteup.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.ItemNotebookBinding
import com.kotlinsun.noteup.domain.model.Notebook

class NotebookAdapter(
    private val onClick: (Notebook) -> Unit,
    private val onMoreClick: (View, Notebook) -> Unit,
) : ListAdapter<Notebook, NotebookAdapter.NotebookViewHolder>(DiffCallback) {

    var selectedNotebookId: Long? = null
        set(value) {
            val previous = field
            field = value
            currentList.indexOfFirst { it.id == previous }
                .takeIf { it >= 0 }
                ?.let(::notifyItemChanged)
            currentList.indexOfFirst { it.id == value }
                .takeIf { it >= 0 }
                ?.let(::notifyItemChanged)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotebookViewHolder {
        val binding = ItemNotebookBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return NotebookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NotebookViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NotebookViewHolder(
        private val binding: ItemNotebookBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(notebook: Notebook) = with(binding) {
            notebookName.text = notebook.name
            val isSelected = notebook.id == selectedNotebookId
            notebookItem.isActivated = isSelected
            notebookName.isActivated = isSelected
            notebookMoreButton.isActivated = isSelected
            notebookItem.contentDescription = root.context.getString(
                R.string.accessibility_notebook_item,
                notebook.name,
            )
            notebookMoreButton.contentDescription = root.context.getString(
                R.string.accessibility_more_options_for,
                notebook.name,
            )
            ViewCompat.setStateDescription(
                notebookItem,
                root.context.getString(
                    if (isSelected) R.string.accessibility_selected
                    else R.string.accessibility_not_selected,
                ),
            )
            ViewCompat.setImportantForAccessibility(
                notebookName,
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO,
            )
            notebookItem.setOnClickListener { onClick(notebook) }
            notebookMoreButton.setOnClickListener { onMoreClick(it, notebook) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Notebook>() {
        override fun areItemsTheSame(oldItem: Notebook, newItem: Notebook) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Notebook, newItem: Notebook) =
            oldItem == newItem
    }
}
