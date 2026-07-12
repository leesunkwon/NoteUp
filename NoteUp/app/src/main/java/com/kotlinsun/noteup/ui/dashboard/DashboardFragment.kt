package com.kotlinsun.noteup.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.databinding.FragmentDashboardBinding

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = checkNotNull(_binding)

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
        binding.openCanvasButton.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_canvas)
        }
        binding.openSettingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
