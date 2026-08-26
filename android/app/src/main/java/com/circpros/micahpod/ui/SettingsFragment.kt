package com.circpros.micahpod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.circpros.micahpod.BuildConfig
import com.circpros.micahpod.R
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.databinding.FragmentSettingsBinding
import com.circpros.micahpod.playback.PlaybackLogger
import com.circpros.micahpod.repository.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().title = getString(R.string.settings)
        binding.versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        binding.logoutButton.setOnClickListener { handleLogout() }
        binding.viewQueueButton.setOnClickListener {
            findNavController().navigate(R.id.queueFragment)
        }
        binding.viewPlaybackLogButton.setOnClickListener {
            binding.playbackLogText.text = PlaybackLogger.readRecent(requireContext())
            binding.playbackLogText.isVisible = true
        }
        binding.submitPlaybackLogButton.setOnClickListener { submitPlaybackLog() }
        binding.clearPlaybackLogButton.setOnClickListener {
            PlaybackLogger.clear(requireContext())
            binding.playbackLogText.text = "Log cleared."
            binding.playbackLogText.isVisible = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun submitPlaybackLog() {
        val logText = PlaybackLogger.readRecent(requireContext())
        if (logText.isBlank() || logText.startsWith("No playback events")) {
            Toast.makeText(requireContext(), R.string.playback_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.submitPlaybackLogButton.isEnabled = false
        binding.submitPlaybackLogButton.text = getString(R.string.submitting_playback_log)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ServiceLocator.repository.submitPlaybackLog(
                    logText = logText,
                    appVersion = BuildConfig.VERSION_NAME
                )
            }
            if (_binding == null) return@launch
            binding.submitPlaybackLogButton.isEnabled = true
            binding.submitPlaybackLogButton.text = getString(R.string.submit_playback_log)
            when (result) {
                is RepositoryResult.Success -> {
                    Toast.makeText(requireContext(), R.string.playback_log_submitted, Toast.LENGTH_LONG).show()
                    binding.playbackLogText.text = "Submitted to server.\n\n$logText"
                    binding.playbackLogText.isVisible = true
                }
                is RepositoryResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleLogout() {
        ServiceLocator.authManager.logout()
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        val navOptions = NavOptions.Builder()
            .setPopUpTo(findNavController().graph.id, true)
            .build()
        findNavController().navigate(R.id.loginFragment, null, navOptions)
    }
}
