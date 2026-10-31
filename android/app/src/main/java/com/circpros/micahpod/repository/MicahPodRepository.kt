package com.circpros.micahpod.repository

import android.content.Context
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.EpisodesResponse
import com.circpros.micahpod.data.InProgressResponse
import com.circpros.micahpod.data.Podcast
import com.circpros.micahpod.data.PodcastsResponse
import com.circpros.micahpod.data.Subscription
import com.circpros.micahpod.data.SubscriptionsResponse
import com.circpros.micahpod.data.TimedListCache
import com.circpros.micahpod.network.ApiService
import com.circpros.micahpod.network.AuthManager
import com.google.gson.Gson
import okhttp3.ResponseBody

class MicahPodRepository(
    context: Context,
    private val apiService: ApiService,
    private val authManager: AuthManager,
    private val gson: Gson
) {
    private val listCache = TimedListCache(
        context.getSharedPreferences("micahpod_cache", Context.MODE_PRIVATE),
        gson
    )

    companion object {
        const val KEY_PODCASTS = "podcasts"
        const val KEY_SUBSCRIPTIONS = "subscriptions"
        const val KEY_FEED_DEFAULT = "feed:newest"
        const val KEY_CONTINUE = "feed:continue"

        fun feedCacheKey(
            sort: String = "newest",
            query: String? = null,
            recommended: Boolean = false,
            continueOnly: Boolean = false
        ): String {
            if (continueOnly) return KEY_CONTINUE
            if (!query.isNullOrBlank()) return "feed:q:${query.trim().lowercase()}"
            if (recommended) return "feed:recommended"
            return "feed:$sort"
        }

        fun episodesCacheKey(podcastId: Int) = "episodes_$podcastId"
    }

    fun peekEpisodes(cacheKey: String): List<Episode>? {
        return when {
            cacheKey == KEY_CONTINUE -> {
                listCache.get<InProgressResponse>(cacheKey)?.first?.episodes
                    ?.map { it.copy(id = it.resolvedId()) }
            }
            else -> listCache.get<EpisodesResponse>(cacheKey)?.first?.episodes
        }
    }

    fun peekPodcasts(): List<Podcast>? =
        listCache.get<PodcastsResponse>(KEY_PODCASTS)?.first?.podcasts

    fun peekSubscriptions(): List<Subscription>? =
        listCache.get<SubscriptionsResponse>(KEY_SUBSCRIPTIONS)?.first?.subscriptions

    fun isCacheFresh(cacheKey: String, ttlMs: Long = TimedListCache.TTL_DEFAULT_MS): Boolean =
        listCache.isFresh(cacheKey, ttlMs)

    fun continueTtlMs(): Long = TimedListCache.TTL_CONTINUE_MS

    fun invalidateContinueCache() {
        listCache.invalidate(KEY_CONTINUE)
    }

    suspend fun login(username: String, password: String): RepositoryResult<Unit> {
        // Always refresh CSRF before login — stale tokens cause silent auth failures.
        authManager.setCsrfToken(null)
        val csrfResult = ensureCsrfToken()
        if (csrfResult is RepositoryResult.Error) {
            return csrfResult
        }
        val response = apiService.login(
            mapOf(
                "email" to username,
                "password" to password,
                "remember" to "1"
            )
        )
        if (!response.isSuccessful) {
            return RepositoryResult.Error("Login failed (${response.code()})")
        }
        val body = response.body()
        if (body?.ok == true) {
            authManager.saveCredentials(username, password)
            // Pull a fresh CSRF for any non-beacon POSTs after login.
            authManager.setCsrfToken(null)
            ensureCsrfToken()
            return RepositoryResult.Success(Unit)
        }
        return RepositoryResult.Error(body?.error ?: "Login failed")
    }

    /**
     * Validate the PHP session; if expired, re-login with stored credentials.
     */
    suspend fun ensureAuthenticated(): RepositoryResult<Unit> {
        if (authManager.getPhpSessionId().isNullOrBlank()) {
            return reLoginWithStoredCredentials()
        }
        val ping = apiService.getPlayerState()
        if (ping.isSuccessful) {
            return RepositoryResult.Success(Unit)
        }
        if (ping.code() == 401 || ping.code() == 403) {
            authManager.clearSession()
            return reLoginWithStoredCredentials()
        }
        // Network blip — keep existing session.
        return RepositoryResult.Success(Unit)
    }

    private suspend fun reLoginWithStoredCredentials(): RepositoryResult<Unit> {
        val username = authManager.getUsername()
        val password = authManager.getPassword()
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return RepositoryResult.Error("Not logged in")
        }
        return login(username, password)
    }

    private suspend fun <T> withAuthRetry(
        call: suspend () -> retrofit2.Response<T>
    ): retrofit2.Response<T> {
        val first = call()
        if (first.code() != 401 && first.code() != 403) return first
        authManager.clearSession()
        val relogin = reLoginWithStoredCredentials()
        if (relogin is RepositoryResult.Error) return first
        return call()
    }

    suspend fun getPodcasts(forceRefresh: Boolean = false): RepositoryResult<List<Podcast>> {
        if (!forceRefresh && listCache.isFresh(KEY_PODCASTS)) {
            peekPodcasts()?.let { return RepositoryResult.Success(it) }
        }
        return try {
            val response = apiService.getPodcasts()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    listCache.put(KEY_PODCASTS, gson.toJson(body))
                    return RepositoryResult.Success(body.podcasts)
                }
            }
            peekPodcasts()?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Unable to load podcasts")
        } catch (e: Exception) {
            android.util.Log.e("MicahPodRepository", "getPodcasts parsing failed", e)
            peekPodcasts()?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Podcast data error: ${e.message?.take(80)}")
        }
    }

    suspend fun getSubscriptions(forceRefresh: Boolean = false): RepositoryResult<List<Subscription>> {
        if (!forceRefresh && listCache.isFresh(KEY_SUBSCRIPTIONS)) {
            peekSubscriptions()?.let { return RepositoryResult.Success(it) }
        }
        return try {
            val response = apiService.getSubscriptions()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    listCache.put(KEY_SUBSCRIPTIONS, gson.toJson(body))
                    return RepositoryResult.Success(body.subscriptions)
                }
            }
            peekSubscriptions()?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Unable to load subscriptions")
        } catch (e: Exception) {
            android.util.Log.e("MicahPodRepository", "getSubscriptions parsing failed", e)
            peekSubscriptions()?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Subscription data error: ${e.message?.take(80)}")
        }
    }

    suspend fun updateSubscription(podcastId: Int, subscribe: Boolean): RepositoryResult<Unit> {
        val response = apiService.updateSubscription(
            mapOf(
                "podcast_id" to podcastId.toString(),
                "action" to if (subscribe) "subscribe" else "unsubscribe"
            )
        )
        return if (response.isSuccessful) {
            listCache.invalidate(KEY_SUBSCRIPTIONS)
            RepositoryResult.Success(Unit)
        } else {
            RepositoryResult.Error("Unable to update subscription")
        }
    }

    suspend fun getFeed(forceRefresh: Boolean = false): RepositoryResult<List<Episode>> {
        val key = KEY_FEED_DEFAULT
        if (!forceRefresh && listCache.isFresh(key)) {
            peekEpisodes(key)?.let { return RepositoryResult.Success(it) }
        }
        return try {
            val response = apiService.getFeed(50)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    listCache.put(key, gson.toJson(body))
                    // Keep legacy "feed" key in sync for older builds' leftovers.
                    listCache.put("feed", gson.toJson(body))
                    return RepositoryResult.Success(body.episodes)
                }
            }
            peekEpisodes(key)?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Unable to load feed")
        } catch (e: Exception) {
            android.util.Log.e("MicahPodRepository", "getFeed parsing failed", e)
            peekEpisodes(key)?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Feed data error: ${e.message?.take(80)}")
        }
    }

    suspend fun getEpisodes(podcastId: Int, forceRefresh: Boolean = false): RepositoryResult<List<Episode>> {
        val key = episodesCacheKey(podcastId)
        if (!forceRefresh && listCache.isFresh(key)) {
            peekEpisodes(key)?.let { return RepositoryResult.Success(it) }
        }
        return try {
            val response = apiService.getEpisodes(podcastId.toString())
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    listCache.put(key, gson.toJson(body))
                    return RepositoryResult.Success(body.episodes)
                }
            }
            peekEpisodes(key)?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Unable to load episodes")
        } catch (e: Exception) {
            android.util.Log.e("MicahPodRepository", "getEpisodes parsing failed for podcast $podcastId", e)
            peekEpisodes(key)?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Episode data error: ${e.message?.take(80)}")
        }
    }

    suspend fun fetchPlayerState(): RepositoryResult<String> {
        val response = withAuthRetry { apiService.getPlayerState() }
        if (response.isSuccessful) {
            val body = response.body()?.string().orEmpty()
            listCache.put("player_state", body)
            return RepositoryResult.Success(body)
        }
        return RepositoryResult.Error("Unable to load player state")
    }

    suspend fun getEpisodeById(episodeId: Int): RepositoryResult<Episode> {
        val response = withAuthRetry { apiService.getEpisodeById(episodeId) }
        if (response.isSuccessful) {
            val json = response.body()?.string().orEmpty()
            val parsed = runCatching {
                gson.fromJson(json, com.circpros.micahpod.data.EpisodeSingleResponse::class.java)
            }.getOrNull()?.episode
            if (parsed != null) {
                return RepositoryResult.Success(parsed.copy(id = parsed.resolvedId()))
            }
        }
        return RepositoryResult.Error("Unable to load episode")
    }

    suspend fun saveQueue(
        queueJson: String,
        queueUpdatedAt: String,
        clearQueue: Boolean = false
    ): RepositoryResult<Unit> {
        val payload = mutableMapOf(
            "queue" to queueJson,
            "queue_updated_at" to queueUpdatedAt,
            "force_queue" to "1",
            "queue_only" to "1",
            "skip_progress" to "1",
            "source" to "manual",
            "client_updated_at" to clientTimestamp()
        )
        if (clearQueue || queueJson == "[]") {
            payload["clear_queue"] = "1"
        }
        return savePlayerState(payload)
    }

    suspend fun submitPlaybackLog(
        logText: String,
        appVersion: String,
        deviceLabel: String = android.os.Build.MODEL
    ): RepositoryResult<String> {
        if (logText.isBlank()) {
            return RepositoryResult.Error("Nothing to submit")
        }
        val response = withAuthRetry {
            apiService.submitPlaybackLog(
                mapOf(
                    "log" to logText,
                    "app_version" to appVersion,
                    "device" to deviceLabel
                )
            )
        }
        return if (response.isSuccessful) {
            val body = response.body()?.string().orEmpty()
            RepositoryResult.Success(body.ifBlank { "ok" })
        } else {
            RepositoryResult.Error("Unable to submit log (${response.code()})")
        }
    }

    suspend fun savePlayerState(payload: Map<String, String>): RepositoryResult<Unit> {
        val safe = payload.filterKeys { it != "queue" || payload[it].orEmpty().isNotBlank() && payload[it] != "[]" }
        val response = withAuthRetry { apiService.savePlayerState(safe) }
        return if (response.isSuccessful) {
            RepositoryResult.Success(Unit)
        } else {
            RepositoryResult.Error("Unable to save player state (${response.code()})")
        }
    }

    suspend fun saveProgress(
        episodeId: String,
        positionMs: Long,
        durationMs: Long = 0L,
        source: String = "auto",
        force: Boolean = false
    ): RepositoryResult<Unit> {
        val positionSeconds = (positionMs.coerceAtLeast(0) / 1000.0)
        val payload = mutableMapOf(
            "episode_id" to episodeId,
            "position_seconds" to String.format(java.util.Locale.US, "%.3f", positionSeconds),
            "source" to source,
            "client_updated_at" to clientTimestamp()
        )
        if (force || source == "manual") {
            payload["force"] = "1"
            payload["source"] = "manual"
        }
        if (durationMs > 0) {
            payload["duration_seconds"] = String.format(java.util.Locale.US, "%.3f", durationMs / 1000.0)
        }
        val response = withAuthRetry { apiService.saveProgress(payload) }
        return if (response.isSuccessful) {
            invalidateContinueCache()
            RepositoryResult.Success(Unit)
        } else {
            RepositoryResult.Error("Unable to save progress (${response.code()})")
        }
    }

    suspend fun markPlayed(episodeId: Int): RepositoryResult<Unit> {
        val response = withAuthRetry {
            apiService.saveProgress(
                mapOf(
                    "action" to "mark_played",
                    "episode_id" to episodeId.toString(),
                    "client_updated_at" to clientTimestamp()
                )
            )
        }
        return if (response.isSuccessful) {
            invalidateContinueCache()
            RepositoryResult.Success(Unit)
        } else {
            RepositoryResult.Error("Unable to mark played (${response.code()})")
        }
    }

    suspend fun getInProgress(limit: Int = 12, forceRefresh: Boolean = false): RepositoryResult<List<Episode>> {
        val key = KEY_CONTINUE
        if (!forceRefresh && listCache.isFresh(key, TimedListCache.TTL_CONTINUE_MS)) {
            peekEpisodes(key)?.let { return RepositoryResult.Success(it) }
        }
        val response = withAuthRetry { apiService.getInProgress(limit = limit) }
        if (response.isSuccessful) {
            val json = response.body()?.string().orEmpty()
            val parsed = runCatching {
                gson.fromJson(json, InProgressResponse::class.java)
            }.getOrNull()
            val episodes = parsed?.episodes.orEmpty().map { ep ->
                ep.copy(id = ep.resolvedId())
            }
            listCache.put(key, gson.toJson(InProgressResponse(episodes)))
            return RepositoryResult.Success(episodes)
        }
        return peekEpisodes(key)?.let { RepositoryResult.Success(it) }
            ?: RepositoryResult.Error("Unable to load continue listening")
    }

    private fun clientTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    suspend fun getTags(episodeId: Int): RepositoryResult<List<com.circpros.micahpod.data.Tag>> {
        val response = apiService.getTags(episodeId)
        if (response.isSuccessful) {
            val json = response.body()?.string().orEmpty()
            val parsed = runCatching {
                gson.fromJson(json, com.circpros.micahpod.data.TagsResponse::class.java)
            }.getOrNull()
            if (parsed != null) return RepositoryResult.Success(parsed.tags)
        }
        return RepositoryResult.Success(emptyList())
    }

    suspend fun getComments(episodeId: Int): RepositoryResult<List<com.circpros.micahpod.data.Comment>> {
        val response = apiService.getComments(episodeId)
        if (response.isSuccessful) {
            val json = response.body()?.string().orEmpty()
            val parsed = runCatching {
                gson.fromJson(json, com.circpros.micahpod.data.CommentsResponse::class.java)
            }.getOrNull()
            if (parsed != null) return RepositoryResult.Success(parsed.comments)
        }
        return RepositoryResult.Success(emptyList())
    }

    suspend fun postRating(episodeId: Int, rating: Int): RepositoryResult<Unit> {
        val response = apiService.postRating(
            mapOf("episode_id" to episodeId.toString(), "rating" to rating.toString())
        )
        return if (response.isSuccessful) RepositoryResult.Success(Unit)
        else RepositoryResult.Error("Failed to save rating")
    }

    suspend fun getFilteredFeed(
        page: Int = 1,
        sort: String = "newest",
        podcastId: String? = null,
        tag: String? = null,
        query: String? = null,
        recommended: Boolean = false,
        forceRefresh: Boolean = false
    ): RepositoryResult<List<Episode>> {
        val key = feedCacheKey(sort = sort, query = query, recommended = recommended)
        if (!forceRefresh && listCache.isFresh(key)) {
            peekEpisodes(key)?.let { return RepositoryResult.Success(it) }
        }
        return try {
            val response = apiService.getFilteredEpisodes(
                page = page,
                sort = sort,
                podcastId = podcastId,
                tag = tag,
                query = query,
                recommended = if (recommended) "1" else null
            )
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    listCache.put(key, gson.toJson(body))
                    return RepositoryResult.Success(body.episodes)
                }
            }
            peekEpisodes(key)?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Unable to load episodes")
        } catch (e: Exception) {
            android.util.Log.e("MicahPodRepository", "getFilteredFeed parsing failed", e)
            peekEpisodes(key)?.let { RepositoryResult.Success(it) }
                ?: RepositoryResult.Error("Episode data error: ${e.message?.take(80)}")
        }
    }

    suspend fun postComment(episodeId: Int, body: String, parentId: Int? = null): RepositoryResult<Unit> {
        val payload = mutableMapOf<String, String>(
            "episode_id" to episodeId.toString(),
            "body" to body
        )
        if (parentId != null) payload["parent_id"] = parentId.toString()
        val response = apiService.postComment(payload)
        return if (response.isSuccessful) RepositoryResult.Success(Unit)
        else RepositoryResult.Error("Failed to post comment")
    }

    private suspend fun ensureCsrfToken(): RepositoryResult<Unit> {
        if (!authManager.getCsrfToken().isNullOrBlank()) {
            return RepositoryResult.Success(Unit)
        }
        val response = apiService.fetchRaw("https://micahyoung.us/micahpod/")
        if (!response.isSuccessful) {
            return RepositoryResult.Error("Unable to fetch CSRF token")
        }
        val token = parseCsrfToken(response.body())
        if (token.isNullOrBlank()) {
            return RepositoryResult.Error("CSRF token not found")
        }
        authManager.setCsrfToken(token)
        return RepositoryResult.Success(Unit)
    }

    private fun parseCsrfToken(body: ResponseBody?): String? {
        val html = body?.string().orEmpty()
        val regex = Regex("name=[\"']csrf-token[\"']\\s+content=[\"']([^\"']+)[\"']")
        return regex.find(html)?.groupValues?.getOrNull(1)
    }
}
