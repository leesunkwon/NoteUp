package com.kotlinsun.noteup.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.kotlinsun.noteup.NoteUpApplication
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentDashboardBinding
import com.kotlinsun.noteup.domain.model.Note
import com.kotlinsun.noteup.domain.model.Notebook
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val viewModel: DashboardViewModel by viewModels {
        val application = requireActivity().application as NoteUpApplication
        DashboardViewModel.Factory(
            this,
            application.container.noteRepository,
            application.container.pageThumbnailStore,
            application.container.pageThumbnailService,
        )
    }

    private val notebookAdapter = NotebookAdapter(
        onClick = { viewModel.selectNotebook(it.id) },
        onMoreClick = ::showNotebookMenu,
    )
    private val noteAdapter by lazy {
        val store = (requireActivity().application as NoteUpApplication).container.pageThumbnailStore
        NoteAdapter(
            onClick = ::openNote,
            onMoreClick = ::showNoteMenu,
            thumbnailStore = store,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLists()
        setupActions()
        observeState()
    }

    private fun setupLists() = with(binding) {
        notebookList.layoutManager = LinearLayoutManager(requireContext())
        notebookList.adapter = notebookAdapter

        val gridLayoutManager = GridLayoutManager(requireContext(), DEFAULT_GRID_SPAN_COUNT)
        noteGrid.layoutManager = gridLayoutManager
        noteGrid.adapter = noteAdapter
        noteGrid.doOnLayout {
            val minimumCardWidth = resources.getDimensionPixelSize(R.dimen.note_card_width)
            val spacing = resources.getDimensionPixelSize(R.dimen.spacing_medium)
            val spanCount = (noteGrid.width / (minimumCardWidth + spacing)).coerceAtLeast(2)
            gridLayoutManager.spanCount = spanCount
        }
    }

    private fun setupActions() = with(binding) {
        allNotesButton.setOnClickListener { viewModel.selectAllNotes() }
        unfiledNotesButton.setOnClickListener { viewModel.selectUnfiledNotes() }
        addNotebookButton.setOnClickListener {
            showNameDialog(
                titleRes = R.string.create_notebook,
                positiveRes = R.string.create,
                initialValue = "",
                onConfirm = viewModel::createNotebook,
            )
        }
        newNoteButton.setOnClickListener {
            viewModel.createNote(getString(R.string.default_note_title))
        }
        openSettingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch {
                    viewModel.errors.collect {
                        Snackbar.make(
                            binding.root,
                            R.string.data_operation_error,
                            Snackbar.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private fun render(state: DashboardUiState) = with(binding) {
        notebookAdapter.submitList(state.notebooks)
        notebookAdapter.selectedNotebookId =
            (state.filter as? DashboardFilter.NotebookFilter)?.notebookId
        noteAdapter.submitList(state.notes)

        allNotesButton.isActivated = state.filter == DashboardFilter.All
        unfiledNotesButton.isActivated = state.filter == DashboardFilter.Unfiled
        contentTitle.text = when (val filter = state.filter) {
            DashboardFilter.All -> getString(R.string.all_notes)
            DashboardFilter.Unfiled -> getString(R.string.unfiled_notes)
            is DashboardFilter.NotebookFilter -> state.notebooks
                .firstOrNull { it.id == filter.notebookId }
                ?.name
                ?: getString(R.string.all_notes)
        }
        emptyState.isVisible = state.notes.isEmpty()
        noteGrid.isVisible = state.notes.isNotEmpty()
    }

    private fun showNotebookMenu(anchor: View, notebook: Notebook) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.rename)
            menu.add(0, MENU_DELETE, 1, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> {
                        showNameDialog(
                            titleRes = R.string.rename_notebook,
                            positiveRes = R.string.save,
                            initialValue = notebook.name,
                        ) { viewModel.renameNotebook(notebook, it) }
                        true
                    }
                    MENU_DELETE -> {
                        confirmNotebookDeletion(notebook)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showNoteMenu(anchor: View, note: Note) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.rename)
            menu.add(0, MENU_MOVE, 1, R.string.move)
            menu.add(0, MENU_DELETE, 2, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> {
                        showNameDialog(
                            titleRes = R.string.rename_note,
                            positiveRes = R.string.save,
                            initialValue = note.title,
                        ) { viewModel.renameNote(note, it) }
                        true
                    }
                    MENU_MOVE -> {
                        showMoveNoteDialog(note)
                        true
                    }
                    MENU_DELETE -> {
                        confirmNoteDeletion(note)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showMoveNoteDialog(note: Note) {
        val notebooks = viewModel.uiState.value.notebooks
        val labels = listOf(getString(R.string.unfiled_notes)) + notebooks.map(Notebook::name)
        val checkedIndex = note.notebookId
            ?.let { id -> notebooks.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
            ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.move_note)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, index ->
                val notebookId = if (index == 0) null else notebooks[index - 1].id
                viewModel.moveNote(note, notebookId)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmNotebookDeletion(notebook: Notebook) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_notebook_title)
            .setMessage(R.string.delete_notebook_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteNotebook(notebook) }
            .show()
    }

    private fun confirmNoteDeletion(note: Note) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_note_title)
            .setMessage(R.string.delete_note_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteNote(note) }
            .show()
    }

    private fun showNameDialog(
        titleRes: Int,
        positiveRes: Int,
        initialValue: String,
        onConfirm: (String) -> Unit,
    ) {
        val input = TextInputEditText(requireContext()).apply {
            setText(initialValue)
            setSelection(text?.length ?: 0)
        }
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.name_hint)
            addView(input)
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(inputLayout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positiveRes, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    inputLayout.error = getString(R.string.required_name_error)
                } else {
                    onConfirm(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun openNote(note: Note) {
        findNavController().navigate(
            R.id.action_dashboard_to_canvas,
            bundleOf(NOTE_ID_ARGUMENT to note.id),
        )
    }

    override fun onDestroyView() {
        binding.notebookList.adapter = null
        binding.noteGrid.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_MOVE = 2
        const val MENU_DELETE = 3
        const val NOTE_ID_ARGUMENT = "noteId"
        const val DEFAULT_GRID_SPAN_COUNT = 3
    }
}
