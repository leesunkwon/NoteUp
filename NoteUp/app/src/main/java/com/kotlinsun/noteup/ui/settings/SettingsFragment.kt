package com.kotlinsun.noteup.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotlinsun.noteup.NoteUpApplication
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.data.preferences.TrashRetention
import com.kotlinsun.noteup.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.trashRetentionCard.setOnClickListener { showRetentionDialog() }
        val store = (requireActivity().application as NoteUpApplication).container.trashRetentionStore
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.retention.collect { binding.trashRetentionSummary.setText(it.labelRes()) }
            }
        }
    }

    private fun showRetentionDialog() {
        val container = (requireActivity().application as NoteUpApplication).container
        val values = TrashRetention.entries
        val labels = values.map { getString(it.labelRes()) }.toTypedArray()
        val selected = values.indexOf(container.trashRetentionStore.current())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_retention_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                container.trashRetentionStore.set(values[which])
                container.trashCleanupService.request()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun TrashRetention.labelRes(): Int = when (this) {
        TrashRetention.DAYS_7 -> R.string.trash_retention_7_days
        TrashRetention.DAYS_30 -> R.string.trash_retention_30_days
        TrashRetention.DAYS_90 -> R.string.trash_retention_90_days
        TrashRetention.NEVER -> R.string.trash_retention_never
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
