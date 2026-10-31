package com.circpros.micahpod.network

import com.circpros.micahpod.data.AddPodcastResponse
import com.circpros.micahpod.data.EpisodesResponse
import com.circpros.micahpod.data.ItunesSearchResponse
import com.circpros.micahpod.data.LoginResponse
import com.circpros.micahpod.data.PodcastsResponse
import com.circpros.micahpod.data.SubscriptionsResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

interface ApiService {
    @POST("users.php?action=login")
    suspend fun login(@Body body: Map<String, String>): Response<LoginResponse>

    @GET("users.php")
    suspend fun getMe(@Query("action") action: String = "me"): Response<LoginResponse>

    @GET("podcasts.php")
    suspend fun getPodcasts(
        @Query("catalog") catalog: String? = "curated"
    ): Response<PodcastsResponse>

    @GET("podcasts.php")
    suspend fun getCommunityPodcasts(
        @Query("action") action: String = "community"
    ): Response<PodcastsResponse>

    @POST("podcasts.php")
    suspend fun addUserPodcast(
        @Query("action") action: String = "add_user",
        @Body body: Map<String, String>
    ): Response<AddPodcastResponse>

    @GET("subscriptions.php")
    suspend fun getSubscriptions(): Response<SubscriptionsResponse>

    @POST("subscriptions.php")
    suspend fun updateSubscription(@Body body: Map<String, String>): Response<ResponseBody>

    @GET("episodes.php")
    suspend fun getEpisodeById(@Query("id") id: Int): Response<ResponseBody>

    @GET("episodes.php")
    suspend fun getEpisodes(
        @Query("podcast_id") podcastId: String,
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 50,
        @Query("sort") sort: String = "newest"
    ): Response<EpisodesResponse>

    @GET("episodes.php")
    suspend fun getFeed(@Query("per_page") perPage: Int = 50): Response<EpisodesResponse>

    @GET("player_state.php")
    suspend fun getPlayerState(): Response<ResponseBody>

    @POST("player_state.php?beacon=1")
    suspend fun savePlayerState(@Body body: Map<String, String>): Response<ResponseBody>

    @POST("progress.php?beacon=1")
    suspend fun saveProgress(@Body body: Map<String, String>): Response<ResponseBody>

    @GET("progress.php")
    suspend fun getInProgress(
        @Query("action") action: String = "in_progress",
        @Query("limit") limit: Int = 12
    ): Response<ResponseBody>

    @GET("tags.php")
    suspend fun getTags(@Query("episode_id") episodeId: Int): Response<ResponseBody>

    @GET("comments.php")
    suspend fun getComments(@Query("episode_id") episodeId: Int): Response<ResponseBody>

    @POST("comments.php")
    suspend fun postComment(@Body body: Map<String, String>): Response<ResponseBody>

    @POST("ratings.php")
    suspend fun postRating(@Body body: Map<String, String>): Response<ResponseBody>

    @GET("comments.php")
    suspend fun getCommentCounts(
        @Query("action") action: String = "counts",
        @Query("episode_ids") episodeIds: String
    ): Response<ResponseBody>

    @GET("search.php")
    suspend fun searchPodcasts(@Query("q") query: String): Response<ItunesSearchResponse>

    @GET("episodes.php")
    suspend fun getFilteredEpisodes(
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 50,
        @Query("sort") sort: String = "newest",
        @Query("podcast_id") podcastId: String? = null,
        @Query("tag") tag: String? = null,
        @Query("q") query: String? = null,
        @Query("rated") rated: String? = null,
        @Query("played") played: String? = null,
        @Query("recommended") recommended: String? = null
    ): Response<EpisodesResponse>

    @GET
    suspend fun fetchRaw(@Url url: String): Response<ResponseBody>

    @POST("playback_log.php?beacon=1")
    suspend fun submitPlaybackLog(@Body body: Map<String, String>): Response<ResponseBody>

    @GET("admin.php")
    suspend fun getAdminStats(@Query("action") action: String = "stats"): Response<ResponseBody>

    @GET("admin.php")
    suspend fun getDigestLog(@Query("action") action: String = "digest_log"): Response<ResponseBody>

    @POST("admin.php")
    suspend fun postAdminAction(
        @Query("action") action: String,
        @Body body: Map<String, String> = emptyMap()
    ): Response<ResponseBody>

    @POST("podcasts.php")
    suspend fun refreshAllFeeds(
        @Query("action") action: String = "refresh_all",
        @Body body: Map<String, String> = emptyMap()
    ): Response<ResponseBody>
}
