package com.circpros.micahpod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.circpros.micahpod.R
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.FragmentAdminBinding
import com.circpros.micahpod.repository.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminFragment : Fragment() {
    private var _binding: FragmentAdminBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().title = getString(R.string.admin)
        if (!ServiceLocator.authManager.isAdmin()) {
            Toast.makeText(requireContext(), R.string.admin_access_required, Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }
        binding.adminRefreshStatsButton.setOnClickListener { loadStats() }
        binding.adminRefreshFeedsButton.setOnClickListener {
            runAction(getString(R.string.admin_refresh_feeds)) {
                ServiceLocator.repository.refreshAllFeeds()
            }
        }
        binding.adminRecomputeButton.setOnClickListener {
            runAction(getString(R.string.admin_recompute)) {
                ServiceLocator.repository.recomputeRecommendations()
            }
        }
        binding.adminExtractTagsButton.setOnClickListener {
            runAction(getString(R.string.admin_extract_tags)) {
                ServiceLocator.repository.extractTags()
            }
        }
        binding.adminDigestLogButton.setOnClickListener {
            runAction(getString(R.string.admin_digest_log)) {
                ServiceLocator.repository.fetchDigestLog()
            }
        }
        loadStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadStats() {
        binding.adminStatsText.text = getString(R.string.admin_stats_loading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ServiceLocator.repository.fetchAdminStats()
            }
            if (_binding == null) return@launch
            when (result) {
                is RepositoryResult.Success -> {
                    val s = result.data
                    binding.adminStatsText.text = getString(
                        R.string.admin_stats_format,
                        s.totalEpisodes ?: 0,
                        s.totalRatings ?: 0,
                        s.activeUsers ?: 0,
                        s.lastRefresh ?: "—"
                    )
                }
                is RepositoryResult.Error -> {
                    binding.adminStatsText.text = result.message
                    if (result.message.contains("Admin", ignoreCase = true)) {
                        ServiceLocator.authManager.setUserRole("user")
                    }
                }
            }
        }
    }

    private fun runAction(label: String, block: suspend () -> RepositoryResult<String>) {
        setBusy(true)
        binding.adminOutputText.text = getString(R.string.admin_running, label)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            if (_binding == null) return@launch
            setBusy(false)
            when (result) {
                is RepositoryResult.Success -> {
                    binding.adminOutputText.text = result.data
                    loadStats()
                }
                is RepositoryResult.Error -> {
                    binding.adminOutputText.text = result.message
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.adminRefreshFeedsButton.isEnabled = !busy
        binding.adminRecomputeButton.isEnabled = !busy
        binding.adminExtractTagsButton.isEnabled = !busy
        binding.adminDigestLogButton.isEnabled = !busy
        binding.adminRefreshStatsButton.isEnabled = !busy
    }
}
