package com.circpros.micahpod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import coil.load
import com.circpros.micahpod.BuildConfig
import com.circpros.micahpod.MicahPodApp
import com.circpros.micahpod.R
import com.circpros.micahpod.data.ItunesPodcastResult
import com.circpros.micahpod.data.ServiceLocator
import com.circpros.micahpod.data.Subscription
import com.circpros.micahpod.databinding.FragmentSettingsBinding
import com.circpros.micahpod.playback.PlaybackLogger
import com.circpros.micahpod.repository.RepositoryResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val repository get() = ServiceLocator.repository

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
        binding.adminButton.isVisible = ServiceLocator.authManager.isAdmin()
        binding.adminButton.setOnClickListener {
            findNavController().navigate(R.id.adminFragment)
        }
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
        binding.librarySearchButton.setOnClickListener { runSearch() }
        binding.librarySearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
        binding.libraryAddFeedButton.setOnClickListener { addFeedUrl() }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ServiceLocator.repository.refreshUserRole()
            }
            if (_binding == null) return@launch
            binding.adminButton.isVisible = ServiceLocator.authManager.isAdmin()
        }
        loadLibrary()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setStatus(message: String) {
        if (_binding == null) return
        binding.libraryStatus.text = message
    }

    private fun loadLibrary() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.getSubscriptions(forceRefresh = true)
            }
            if (_binding == null) return@launch
            when (result) {
                is RepositoryResult.Success -> renderLibrary(result.data)
                is RepositoryResult.Error -> setStatus(result.message)
            }
        }
    }

    private fun renderLibrary(subscriptions: List<Subscription>) {
        binding.libraryList.removeAllViews()
        if (subscriptions.isEmpty()) {
            val empty = TextView(requireContext())
            empty.text = getString(R.string.library_empty)
            empty.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            empty.alpha = 0.7f
            binding.libraryList.addView(empty)
            return
        }
        subscriptions.forEach { sub ->
            binding.libraryList.addView(buildLibraryRow(sub))
        }
    }

    private fun buildLibraryRow(sub: Subscription): View {
        val row = layoutInflater.inflate(R.layout.item_library_subscription, binding.libraryList, false)
        val art = row.findViewById<ImageView>(R.id.libraryArt)
        val title = row.findViewById<TextView>(R.id.libraryTitle)
        val meta = row.findViewById<TextView>(R.id.libraryMeta)
        val feedSwitch = row.findViewById<MaterialSwitch>(R.id.libraryFeedSwitch)
        val removeButton = row.findViewById<MaterialButton>(R.id.libraryRemoveButton)

        title.text = sub.title ?: getString(R.string.podcasts)
        val curatedLabel = if ((sub.curated ?: 1) == 1) {
            getString(R.string.featured_label)
        } else {
            getString(R.string.user_added_label)
        }
        val count = sub.episodeCount ?: 0
        meta.text = getString(R.string.library_meta_format, curatedLabel, count)
        art.load(sub.artworkUrl) { crossfade(true) }

        feedSwitch.isChecked = (sub.includeInFeed ?: 1) != 0
        feedSwitch.setOnCheckedChangeListener { _, isChecked ->
            feedSwitch.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    repository.setIncludeInFeed(sub.id, isChecked)
                }
                if (_binding == null) return@launch
                feedSwitch.isEnabled = true
                when (result) {
                    is RepositoryResult.Success -> setStatus(
                        if (isChecked) getString(R.string.shown_in_feed) else getString(R.string.hidden_from_feed)
                    )
                    is RepositoryResult.Error -> {
                        feedSwitch.isChecked = !isChecked
                        setStatus(result.message)
                    }
                }
            }
        }

        removeButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.remove_from_library)
                .setMessage(getString(R.string.remove_library_confirm, sub.title ?: "this podcast"))
                .setPositiveButton(R.string.remove) { _, _ ->
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            repository.updateSubscription(sub.id, subscribe = false)
                        }
                        if (_binding == null) return@launch
                        when (result) {
                            is RepositoryResult.Success -> loadLibrary()
                            is RepositoryResult.Error -> setStatus(result.message)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return row
    }

    private fun runSearch() {
        val q = binding.librarySearchInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            setStatus(getString(R.string.enter_search_term))
            return
        }
        setStatus(getString(R.string.searching))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.searchPodcasts(q) }
            if (_binding == null) return@launch
            when (result) {
                is RepositoryResult.Success -> {
                    setStatus("")
                    renderSearchResults(result.data)
                }
                is RepositoryResult.Error -> setStatus(result.message)
            }
        }
    }

    private fun renderSearchResults(results: List<ItunesPodcastResult>) {
        binding.librarySearchResults.removeAllViews()
        if (results.isEmpty()) {
            val empty = TextView(requireContext())
            empty.text = getString(R.string.no_matches)
            empty.alpha = 0.7f
            binding.librarySearchResults.addView(empty)
            return
        }
        results.take(12).forEach { item ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            val art = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(96, 96)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            art.load(item.artworkUrl600 ?: item.artworkUrl100) { crossfade(true) }
            val info = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 24
                    marginEnd = 16
                }
            }
            val title = TextView(requireContext()).apply {
                text = item.collectionName ?: item.trackName ?: "Podcast"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            }
            val author = TextView(requireContext()).apply {
                text = item.artistName.orEmpty()
                alpha = 0.7f
            }
            info.addView(title)
            info.addView(author)
            val addBtn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.add)
                setOnClickListener { addSearchResult(item, this) }
            }
            row.addView(art)
            row.addView(info)
            row.addView(addBtn)
            binding.librarySearchResults.addView(row)
        }
    }

    private fun addSearchResult(item: ItunesPodcastResult, button: MaterialButton) {
        val feedUrl = item.feedUrl.orEmpty()
        if (feedUrl.isBlank()) {
            setStatus(getString(R.string.missing_feed_url))
            return
        }
        button.isEnabled = false
        setStatus(getString(R.string.adding_podcast))
        lifecycleScope.launch {
            val payload = mutableMapOf(
                "source" to "user_search",
                "title" to (item.collectionName ?: item.trackName ?: "Podcast"),
                "feed_url" to feedUrl
            )
            item.artistName?.let { payload["author"] = it }
            (item.artworkUrl600 ?: item.artworkUrl100)?.let { payload["artwork_url"] = it }
            (item.collectionId ?: item.trackId)?.let { payload["itunes_id"] = it.toString() }
            val result = withContext(Dispatchers.IO) { repository.addUserPodcast(payload) }
            if (_binding == null) return@launch
            button.isEnabled = true
            when (result) {
                is RepositoryResult.Success -> {
                    setStatus(
                        if (result.data.created == true) getString(R.string.podcast_added)
                        else getString(R.string.podcast_subscribed_existing)
                    )
                    loadLibrary()
                }
                is RepositoryResult.Error -> setStatus(result.message)
            }
        }
    }

    private fun addFeedUrl() {
        val feedUrl = binding.libraryFeedUrl.text?.toString()?.trim().orEmpty()
        if (feedUrl.isEmpty()) {
            setStatus(getString(R.string.paste_feed_url))
            return
        }
        setStatus(getString(R.string.adding_podcast))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.addUserPodcast(
                    mapOf(
                        "source" to "user_rss",
                        "feed_url" to feedUrl
                    )
                )
            }
            if (_binding == null) return@launch
            when (result) {
                is RepositoryResult.Success -> {
                    binding.libraryFeedUrl.setText("")
                    setStatus(getString(R.string.podcast_added))
                    loadLibrary()
                }
                is RepositoryResult.Error -> setStatus(result.message)
            }
        }
    }

    private fun submitPlaybackLog() {
        val logText = PlaybackLogger.readRecent(requireContext())
        val crashText = runCatching {
            requireContext().filesDir.resolve(MicahPodApp.CRASH_LOG_NAME).takeIf { it.exists() }?.readText()
        }.getOrNull()
        if ((logText.isBlank() || logText.startsWith("No playback events")) && crashText.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.playback_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val payload = buildString {
            appendLine("=== playback_events ===")
            appendLine(logText)
            if (!crashText.isNullOrBlank()) {
                appendLine()
                appendLine("=== last_crash.txt ===")
                appendLine(crashText)
            }
        }
        binding.submitPlaybackLogButton.isEnabled = false
        binding.submitPlaybackLogButton.text = getString(R.string.submitting_playback_log)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ServiceLocator.repository.submitPlaybackLog(
                    logText = payload,
                    appVersion = BuildConfig.VERSION_NAME
                )
            }
            if (_binding == null) return@launch
            binding.submitPlaybackLogButton.isEnabled = true
            binding.submitPlaybackLogButton.text = getString(R.string.submit_playback_log)
            when (result) {
                is RepositoryResult.Success -> {
                    Toast.makeText(requireContext(), R.string.playback_log_submitted, Toast.LENGTH_LONG).show()
                    binding.playbackLogText.text = "Submitted to server.\n\n$payload"
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
