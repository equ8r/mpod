package com.circpros.micahpod.repository

import android.content.Context
import com.circpros.micahpod.data.AddPodcastResponse
import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.EpisodesPage
import com.circpros.micahpod.data.EpisodesResponse
import com.circpros.micahpod.data.FeedPage
import com.circpros.micahpod.data.InProgressResponse
import com.circpros.micahpod.data.ItunesPodcastResult
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
    private val longApiService: ApiService,
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
            continueOnly: Boolean = false,
            played: String? = null
        ): String {
            if (continueOnly) return KEY_CONTINUE
            if (!query.isNullOrBlank()) return "feed:q:${query.trim().lowercase()}:played:${played ?: "all"}"
            if (recommended) return "feed:recommended:played:${played ?: "all"}"
            return "feed:$sort:played:${played ?: "all"}"
        }

        fun episodesCacheKey(podcastId: Int, sort: String = "newest") =
            "episodes_${podcastId}_$sort"
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
            body.user?.role?.let { authManager.setUserRole(it) }
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
            refreshUserRole()
            return RepositoryResult.Success(Unit)
        }
        if (ping.code() == 401 || ping.code() == 403) {
            authManager.clearSession()
            return reLoginWithStoredCredentials()
        }
        // Network blip — keep existing session.
        return RepositoryResult.Success(Unit)
    }

    suspend fun refreshUserRole(): RepositoryResult<String> {
        val response = withAuthRetry { apiService.getMe() }
        if (!response.isSuccessful) {
            return RepositoryResult.Error("Unable to load profile (${response.code()})")
        }
        val role = response.body()?.user?.role
        if (!role.isNullOrBlank()) {
            authManager.setUserRole(role)
            return RepositoryResult.Success(role)
        }
        return RepositoryResult.Error("No user profile")
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
        val response = apiService.getPodcasts()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                listCache.put(KEY_PODCASTS, gson.toJson(body))
                return RepositoryResult.Success(body.podcasts)
            }
        }
        return peekPodcasts()?.let { RepositoryResult.Success(it) }
            ?: RepositoryResult.Error("Unable to load podcasts")
    }

    suspend fun getSubscriptions(forceRefresh: Boolean = false): RepositoryResult<List<Subscription>> {
        if (!forceRefresh && listCache.isFresh(KEY_SUBSCRIPTIONS)) {
            peekSubscriptions()?.let { return RepositoryResult.Success(it) }
        }
        val response = apiService.getSubscriptions()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                listCache.put(KEY_SUBSCRIPTIONS, gson.toJson(body))
                return RepositoryResult.Success(body.subscriptions)
            }
        }
        return peekSubscriptions()?.let { RepositoryResult.Success(it) }
            ?: RepositoryResult.Error("Unable to load subscriptions")
    }

    suspend fun updateSubscription(podcastId: Int, subscribe: Boolean): RepositoryResult<Unit> {
        ensureCsrfToken()
        val response = apiService.updateSubscription(
            mapOf(
                "podcast_id" to podcastId.toString(),
                "action" to if (subscribe) "subscribe" else "unsubscribe"
            )
        )
        return if (response.isSuccessful) {
            listCache.invalidate(KEY_SUBSCRIPTIONS)
            listCache.invalidate(KEY_FEED_DEFAULT)
            RepositoryResult.Success(Unit)
        } else {
            RepositoryResult.Error("Unable to update subscription")
        }
    }

    suspend fun setIncludeInFeed(podcastId: Int, includeInFeed: Boolean): RepositoryResult<Unit> {
        ensureCsrfToken()
        val response = apiService.updateSubscription(
            mapOf(
                "podcast_id" to podcastId.toString(),
                "action" to "set_include_in_feed",
                "include_in_feed" to if (includeInFeed) "1" else "0"
            )
        )
        return if (response.isSuccessful) {
            listCache.invalidate(KEY_SUBSCRIPTIONS)
            listCache.invalidate(KEY_FEED_DEFAULT)
            RepositoryResult.Success(Unit)
        } else {
            RepositoryResult.Error("Unable to update feed visibility")
        }
    }

    suspend fun getCommunityPodcasts(): RepositoryResult<List<Podcast>> {
        val response = apiService.getCommunityPodcasts()
        if (response.isSuccessful) {
            return RepositoryResult.Success(response.body()?.podcasts.orEmpty())
        }
        return RepositoryResult.Error("Unable to load community podcasts")
    }

    suspend fun searchPodcasts(query: String): RepositoryResult<List<ItunesPodcastResult>> {
        val response = apiService.searchPodcasts(query)
        if (response.isSuccessful) {
            return RepositoryResult.Success(response.body()?.results.orEmpty())
        }
        return RepositoryResult.Error("Search failed")
    }

    suspend fun addUserPodcast(payload: Map<String, String>): RepositoryResult<AddPodcastResponse> {
        ensureCsrfToken()
        val response = apiService.addUserPodcast(body = payload)
        if (response.isSuccessful) {
            listCache.invalidate(KEY_SUBSCRIPTIONS)
            listCache.invalidate(KEY_PODCASTS)
            listCache.invalidate(KEY_FEED_DEFAULT)
            val body = response.body()
            if (body != null) return RepositoryResult.Success(body)
        }
        val err = runCatching {
            gson.fromJson(response.errorBody()?.string().orEmpty(), AddPodcastResponse::class.java).error
        }.getOrNull()
        return RepositoryResult.Error(err ?: "Unable to add podcast")
    }

    suspend fun getFeed(forceRefresh: Boolean = false): RepositoryResult<List<Episode>> {
        val key = KEY_FEED_DEFAULT
        if (!forceRefresh && listCache.isFresh(key)) {
            peekEpisodes(key)?.let { return RepositoryResult.Success(it) }
        }
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
        return peekEpisodes(key)?.let { RepositoryResult.Success(it) }
            ?: RepositoryResult.Error("Unable to load feed")
    }

    suspend fun getEpisodes(
        podcastId: Int,
        page: Int = 1,
        perPage: Int = 50,
        sort: String = "newest",
        forceRefresh: Boolean = false
    ): RepositoryResult<EpisodesPage> {
        val normalizedSort = if (sort == "oldest") "oldest" else "newest"
        val key = episodesCacheKey(podcastId, normalizedSort)
        // Cache only covers page 1 for fast reopen; later pages always hit the network.
        if (page == 1 && !forceRefresh && listCache.isFresh(key)) {
            listCache.get<EpisodesResponse>(key)?.first?.let { cached ->
                return RepositoryResult.Success(
                    EpisodesPage(
                        episodes = cached.episodes,
                        page = cached.page ?: 1,
                        perPage = cached.perPage ?: perPage,
                        total = cached.total ?: cached.episodes.size
                    )
                )
            }
        }
        val response = apiService.getEpisodes(
            podcastId = podcastId.toString(),
            page = page,
            perPage = perPage,
            sort = normalizedSort
        )
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                if (page == 1) {
                    listCache.put(key, gson.toJson(body))
                }
                return RepositoryResult.Success(
                    EpisodesPage(
                        episodes = body.episodes,
                        page = body.page ?: page,
                        perPage = body.perPage ?: perPage,
                        total = body.total ?: body.episodes.size
                    )
                )
            }
        }
        if (page == 1) {
            listCache.get<EpisodesResponse>(key)?.first?.let { cached ->
                return RepositoryResult.Success(
                    EpisodesPage(
                        episodes = cached.episodes,
                        page = cached.page ?: 1,
                        perPage = cached.perPage ?: perPage,
                        total = cached.total ?: cached.episodes.size
                    )
                )
            }
        }
        return RepositoryResult.Error("Unable to load episodes")
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
            val err = runCatching { response.errorBody()?.string().orEmpty() }.getOrNull().orEmpty()
            RepositoryResult.Error(
                "Unable to submit log (${response.code()})" +
                    if (err.isNotBlank()) ": ${err.take(180)}" else ""
            )
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
            if (parsed == null) {
                android.util.Log.e(
                    "MicahPodRepository",
                    "Failed to parse in_progress response (len=${json.length}): ${json.take(240)}"
                )
                return peekEpisodes(key)?.let { RepositoryResult.Success(it) }
                    ?: RepositoryResult.Error("Unable to parse continue listening")
            }
            val episodes = parsed.episodes.orEmpty().map { ep ->
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
        played: String? = null,
        forceRefresh: Boolean = false
    ): RepositoryResult<FeedPage> {
        val key = feedCacheKey(
            sort = sort,
            query = query,
            recommended = recommended,
            played = played
        )
        if (!forceRefresh && listCache.isFresh(key)) {
            listCache.get<EpisodesResponse>(key)?.first?.let { cached ->
                return RepositoryResult.Success(
                    FeedPage(
                        episodes = cached.episodes,
                        libraryEmpty = cached.libraryEmpty == true,
                        feedHiddenOnly = cached.feedHiddenOnly == true
                    )
                )
            }
        }
        val response = apiService.getFilteredEpisodes(
            page = page,
            sort = sort,
            podcastId = podcastId,
            tag = tag,
            query = query,
            played = played,
            recommended = if (recommended) "1" else null
        )
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                listCache.put(key, gson.toJson(body))
                return RepositoryResult.Success(
                    FeedPage(
                        episodes = body.episodes,
                        libraryEmpty = body.libraryEmpty == true,
                        feedHiddenOnly = body.feedHiddenOnly == true
                    )
                )
            }
        }
        listCache.get<EpisodesResponse>(key)?.first?.let { cached ->
            return RepositoryResult.Success(
                FeedPage(
                    episodes = cached.episodes,
                    libraryEmpty = cached.libraryEmpty == true,
                    feedHiddenOnly = cached.feedHiddenOnly == true
                )
            )
        }
        return RepositoryResult.Error("Unable to load episodes")
    }

    suspend fun postComment(episodeId: Int, body: String, parentId: Int? = null): RepositoryResult<Unit> {
        ensureCsrfToken()
        val payload = mutableMapOf(
            "action" to "create",
            "episode_id" to episodeId.toString(),
            "body" to body
        )
        if (parentId != null) payload["parent_id"] = parentId.toString()
        val response = apiService.postComment(payload)
        if (response.isSuccessful) {
            return RepositoryResult.Success(Unit)
        }
        val err = runCatching {
            gson.fromJson(response.errorBody()?.string().orEmpty(), Map::class.java)["error"]?.toString()
        }.getOrNull()
        return RepositoryResult.Error(err ?: "Failed to post comment (${response.code()})")
    }

    suspend fun fetchAdminStats(): RepositoryResult<com.circpros.micahpod.data.AdminStats> {
        ensureCsrfToken()
        val response = withAuthRetry { apiService.getAdminStats() }
        if (!response.isSuccessful) {
            return RepositoryResult.Error(
                if (response.code() == 403) "Admin access required" else "Unable to load stats (${response.code()})"
            )
        }
        val parsed = runCatching {
            gson.fromJson(response.body()?.string().orEmpty(), com.circpros.micahpod.data.AdminStatsResponse::class.java)
        }.getOrNull()?.stats
        return if (parsed != null) RepositoryResult.Success(parsed)
        else RepositoryResult.Error("Unable to parse admin stats")
    }

    suspend fun refreshAllFeeds(): RepositoryResult<String> {
        ensureCsrfToken()
        val response = withAuthRetry { longApiService.refreshAllFeeds() }
        return parseAdminActionResponse(response, "Feed refresh failed")
    }

    suspend fun recomputeRecommendations(): RepositoryResult<String> {
        ensureCsrfToken()
        val response = withAuthRetry { longApiService.postAdminAction("recompute") }
        return parseAdminActionResponse(response, "Recompute failed")
    }

    suspend fun extractTags(): RepositoryResult<String> {
        ensureCsrfToken()
        val response = withAuthRetry { longApiService.postAdminAction("extract_tags") }
        return parseAdminActionResponse(response, "Tag extraction failed")
    }

    suspend fun fetchDigestLog(): RepositoryResult<String> {
        ensureCsrfToken()
        val response = withAuthRetry { apiService.getDigestLog() }
        if (!response.isSuccessful) {
            return RepositoryResult.Error("Unable to load digest log (${response.code()})")
        }
        val json = response.body()?.string().orEmpty()
        val root = runCatching {
            com.google.gson.JsonParser.parseString(json).asJsonObject
        }.getOrNull() ?: return RepositoryResult.Error("Unable to parse digest log")
        val rows = root.getAsJsonArray("digest_log") ?: return RepositoryResult.Success("No digest entries.")
        if (rows.size() == 0) return RepositoryResult.Success("No digest entries.")
        val lines = rows.take(40).map { el ->
            val o = el.asJsonObject
            val sent = o.get("sent_at")?.asString ?: "-"
            val email = o.get("email")?.asString ?: "user ${o.get("user_id")?.asString ?: "?"}"
            val count = o.get("episode_count")?.asString ?: "?"
            "$sent  $email  ($count eps)"
        }
        return RepositoryResult.Success(lines.joinToString("\n"))
    }

    private fun parseAdminActionResponse(
        response: retrofit2.Response<ResponseBody>,
        fallback: String
    ): RepositoryResult<String> {
        val json = response.body()?.string().orEmpty()
        if (!response.isSuccessful) {
            val err = runCatching {
                gson.fromJson(json, com.circpros.micahpod.data.AdminActionResponse::class.java)?.error
            }.getOrNull()
            return RepositoryResult.Error(err ?: "$fallback (${response.code()})")
        }
        val parsed = runCatching {
            gson.fromJson(json, com.circpros.micahpod.data.AdminActionResponse::class.java)
        }.getOrNull()
        val parts = mutableListOf<String>()
        parsed?.output?.takeIf { it.isNotBlank() }?.let { parts += it }
        parsed?.message?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (parsed?.episodesProcessed != null || parsed?.tagsInserted != null) {
            parts += "Episodes processed: ${parsed.episodesProcessed ?: 0}, tags inserted: ${parsed.tagsInserted ?: 0}"
        }
        if (parsed?.profiles != null || parsed?.scores != null) {
            parts += "Profiles: ${parsed.profiles ?: 0}, scores: ${parsed.scores ?: 0}"
        }
        if (parsed?.scoredUsers != null) {
            parts += "Scored users: ${parsed.scoredUsers}"
        }
        if (parsed?.newEpisodeIds != null) {
            parts += "New episodes: ${parsed.newEpisodeIds.size}"
        }
        if (parts.isEmpty() && json.isNotBlank()) {
            parts += json.take(2000)
        }
        return RepositoryResult.Success(parts.joinToString("\n").ifBlank { "Done." })
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
