package com.circpros.micahpod.data

import com.google.gson.annotations.SerializedName

data class Podcast(
    val id: Int,
    val title: String,
    val description: String?,
    @SerializedName("artwork_url")
    val artworkUrl: String?,
    val author: String?,
    @SerializedName("episode_count")
    val episodeCount: Int = 0,
    @SerializedName("feed_url")
    val feedUrl: String? = null,
    val curated: Int? = 1,
    @SerializedName("subscriber_count")
    val subscriberCount: Int? = null,
    @SerializedName("latest_episode")
    val latestEpisode: String? = null
)

data class Episode(
    val id: Int,
    val title: String,
    @SerializedName("pub_date")
    val pubDate: String?,
    @SerializedName("duration_seconds")
    val durationSeconds: Int?,
    @SerializedName("audio_url")
    val audioUrl: String?,
    @SerializedName("artwork_url")
    val artworkUrl: String?,
    @SerializedName("podcast_title")
    val podcastTitle: String?,
    @SerializedName("podcast_artwork")
    val podcastArtwork: String?,
    val description: String?,
    @SerializedName("description_html")
    val descriptionHtml: String?,
    @SerializedName("user_rating")
    val userRating: Int?,
    @SerializedName("avg_rating")
    val avgRating: Double?,
    val completed: Boolean?,
    @SerializedName("rec_score")
    val recScore: Double?,
    @SerializedName("comment_count")
    var commentCount: Int?,
    val tags: List<Tag>?,
    @SerializedName("position_seconds")
    val positionSeconds: Double? = null,
    @SerializedName("podcast_id")
    val podcastId: Int? = null,
    @SerializedName("episode_id")
    val episodeId: Int? = null
) {
    fun resolvedId(): Int = episodeId ?: id
}

data class Subscription(
    val id: Int,
    val title: String?,
    @SerializedName("artwork_url")
    val artworkUrl: String?,
    val description: String? = null,
    val author: String? = null,
    val curated: Int? = 1,
    @SerializedName("include_in_feed")
    val includeInFeed: Int? = 1,
    @SerializedName("episode_count")
    val episodeCount: Int? = 0,
    @SerializedName("feed_url")
    val feedUrl: String? = null
)

data class PlayerState(
    @SerializedName("episode_id")
    val episodeId: String?,
    val position: Long,
    val queue: List<String>
)

data class PlayerStateResponse(
    val state: PlayerStateDetails?
)

data class PlayerStateDetails(
    @SerializedName("episode_id")
    val episodeId: Int?,
    @SerializedName("position_seconds")
    val positionSeconds: Double?,
    val title: String?,
    @SerializedName("audio_url")
    val audioUrl: String?,
    @SerializedName("artwork_url")
    val artworkUrl: String?,
    @SerializedName("podcast_title")
    val podcastTitle: String?,
    @SerializedName("podcast_artwork")
    val podcastArtwork: String?,
    val queue: List<QueueEpisode>? = null,
    @SerializedName("queue_updated_at")
    val queueUpdatedAt: String? = null
)

/** Queue item shape shared with the web player. */
data class QueueEpisode(
    val id: Int? = null,
    val title: String? = null,
    @SerializedName("podcast_title")
    val podcastTitle: String? = null,
    @SerializedName("artwork_url")
    val artworkUrl: String? = null,
    @SerializedName("podcast_artwork")
    val podcastArtwork: String? = null,
    @SerializedName("audio_url")
    val audioUrl: String? = null,
    @SerializedName("duration_seconds")
    val durationSeconds: Int? = null
) {
    fun key(): String = "${id ?: ""}::${audioUrl ?: ""}"

    fun toEpisode(): Episode = Episode(
        id = id ?: 0,
        title = title ?: "Untitled episode",
        pubDate = null,
        durationSeconds = durationSeconds,
        audioUrl = audioUrl,
        artworkUrl = artworkUrl,
        podcastTitle = podcastTitle,
        podcastArtwork = podcastArtwork,
        description = null,
        descriptionHtml = null,
        userRating = null,
        avgRating = null,
        completed = null,
        recScore = null,
        commentCount = null,
        tags = null,
        episodeId = id
    )

    companion object {
        fun fromEpisode(episode: Episode): QueueEpisode = QueueEpisode(
            id = episode.resolvedId(),
            title = episode.title,
            podcastTitle = episode.podcastTitle,
            artworkUrl = episode.artworkUrl,
            podcastArtwork = episode.podcastArtwork,
            audioUrl = episode.audioUrl,
            durationSeconds = episode.durationSeconds
        )
    }
}

data class EpisodeSingleResponse(
    val episode: Episode?
)

data class LoginResponse(
    val ok: Boolean?,
    val error: String?,
    val user: LoginUser?
)

data class LoginUser(
    val id: Int,
    val username: String,
    val email: String,
    val role: String
)

data class PodcastsResponse(
    val podcasts: List<Podcast>
)

data class EpisodesResponse(
    val episodes: List<Episode>,
    @SerializedName("library_empty")
    val libraryEmpty: Boolean? = false,
    @SerializedName("feed_hidden_only")
    val feedHiddenOnly: Boolean? = false,
    val page: Int? = null,
    @SerializedName("per_page")
    val perPage: Int? = null,
    val total: Int? = null
)

data class FeedPage(
    val episodes: List<Episode>,
    val libraryEmpty: Boolean = false,
    val feedHiddenOnly: Boolean = false
)

data class EpisodesPage(
    val episodes: List<Episode>,
    val page: Int = 1,
    val perPage: Int = 50,
    val total: Int = 0
) {
    val hasMore: Boolean
        get() = page * perPage < total
}

data class SubscriptionsResponse(
    val subscriptions: List<Subscription>
)

data class ItunesSearchResponse(
    val results: List<ItunesPodcastResult> = emptyList()
)

data class ItunesPodcastResult(
    @SerializedName("collectionName")
    val collectionName: String? = null,
    @SerializedName("trackName")
    val trackName: String? = null,
    @SerializedName("artistName")
    val artistName: String? = null,
    @SerializedName("feedUrl")
    val feedUrl: String? = null,
    @SerializedName("artworkUrl600")
    val artworkUrl600: String? = null,
    @SerializedName("artworkUrl100")
    val artworkUrl100: String? = null,
    @SerializedName("collectionId")
    val collectionId: Long? = null,
    @SerializedName("trackId")
    val trackId: Long? = null
)

data class AddPodcastResponse(
    val ok: Boolean? = null,
    val id: Int? = null,
    val created: Boolean? = null,
    val error: String? = null,
    val podcast: Podcast? = null
)

data class Tag(
    val id: Int,
    val name: String,
    val type: String? // "guest" or "topic"
)

data class TagsResponse(
    val tags: List<Tag>
)

data class Comment(
    val id: Int,
    val username: String?,
    @SerializedName("user_id")
    val userId: Int?,
    val body: String,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("parent_id")
    val parentId: Int?,
    val replies: List<Comment>?
)

data class CommentsResponse(
    val comments: List<Comment>,
    @SerializedName("total_count")
    val totalCount: Int
)

data class RatingResponse(
    val ok: Boolean?,
    val error: String?
)

data class CommentCountsResponse(
    val counts: Map<String, Int>
)

data class InProgressResponse(
    val episodes: List<Episode>? = null
)

data class ProgressWriteResponse(
    val ok: Boolean? = null,
    val applied: Boolean? = null,
    val reason: String? = null,
    val completed: Int? = null,
    val error: String? = null
)

data class AdminStatsResponse(
    val stats: AdminStats?
)

data class AdminStats(
    @SerializedName("total_episodes")
    val totalEpisodes: Int? = null,
    @SerializedName("total_ratings")
    val totalRatings: Int? = null,
    @SerializedName("active_users")
    val activeUsers: Int? = null,
    @SerializedName("last_refresh")
    val lastRefresh: String? = null
)

data class AdminActionResponse(
    val ok: Boolean? = null,
    val output: String? = null,
    val error: String? = null,
    @SerializedName("episodes_processed")
    val episodesProcessed: Int? = null,
    @SerializedName("tags_inserted")
    val tagsInserted: Int? = null,
    val profiles: Int? = null,
    val scores: Int? = null,
    @SerializedName("new_episode_ids")
    val newEpisodeIds: List<Int>? = null,
    @SerializedName("scored_users")
    val scoredUsers: Int? = null,
    val refreshed: Int? = null,
    val message: String? = null
)
