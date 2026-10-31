package com.circpros.micahpod

import com.circpros.micahpod.data.Episode
import com.circpros.micahpod.data.EpisodesResponse
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for Episode JSON parsing edge cases.
 * These verify that malformed or unexpected data doesn't crash the app.
 */
class EpisodeParsingTest {
    private val gson = Gson()

    @Test
    fun `parse valid episode`() {
        val json = """
            {
                "id": 123,
                "title": "Test Episode",
                "duration_seconds": 3600,
                "audio_url": "https://example.com/audio.mp3"
            }
        """.trimIndent()
        
        val episode = gson.fromJson(json, Episode::class.java)
        assertEquals(123, episode.id)
        assertEquals("Test Episode", episode.title)
        assertEquals(3600, episode.durationSeconds)
    }

    @Test
    fun `parse episode with null duration`() {
        val json = """
            {
                "id": 456,
                "title": "No Duration",
                "duration_seconds": null
            }
        """.trimIndent()
        
        val episode = gson.fromJson(json, Episode::class.java)
        assertEquals(456, episode.id)
        assertNull(episode.durationSeconds)
    }

    @Test
    fun `parse episode with missing optional fields`() {
        val json = """
            {
                "id": 789,
                "title": "Minimal Episode"
            }
        """.trimIndent()
        
        val episode = gson.fromJson(json, Episode::class.java)
        assertEquals(789, episode.id)
        assertEquals("Minimal Episode", episode.title)
        assertNull(episode.durationSeconds)
        assertNull(episode.audioUrl)
        assertNull(episode.podcastTitle)
    }

    @Test
    fun `parse episodes response with empty list`() {
        val json = """{"episodes": []}"""
        val response = gson.fromJson(json, EpisodesResponse::class.java)
        assertTrue(response.episodes.isEmpty())
    }

    @Test
    fun `parse episodes response with valid episodes`() {
        val json = """
            {
                "episodes": [
                    {"id": 1, "title": "First"},
                    {"id": 2, "title": "Second"}
                ]
            }
        """.trimIndent()
        
        val response = gson.fromJson(json, EpisodesResponse::class.java)
        assertEquals(2, response.episodes.size)
        assertEquals(1, response.episodes[0].id)
        assertEquals(2, response.episodes[1].id)
    }

    @Test
    fun `resolvedId returns episodeId when present`() {
        val json = """
            {
                "id": 100,
                "episode_id": 200,
                "title": "Test"
            }
        """.trimIndent()
        
        val episode = gson.fromJson(json, Episode::class.java)
        assertEquals(200, episode.resolvedId())
    }

    @Test
    fun `resolvedId returns id when episodeId is null`() {
        val json = """
            {
                "id": 100,
                "title": "Test"
            }
        """.trimIndent()
        
        val episode = gson.fromJson(json, Episode::class.java)
        assertEquals(100, episode.resolvedId())
    }

    @Test
    fun `runCatching handles parsing exceptions`() {
        val badJson = """{"episodes": "not an array"}"""
        
        val result = runCatching {
            gson.fromJson(badJson, EpisodesResponse::class.java)
        }
        
        assertTrue(result.isFailure)
    }

    @Test
    fun `max duration value parses correctly`() {
        val json = """
            {
                "id": 1,
                "title": "Long Episode",
                "duration_seconds": 2147483647
            }
        """.trimIndent()
        
        val episode = gson.fromJson(json, Episode::class.java)
        assertEquals(Int.MAX_VALUE, episode.durationSeconds)
    }
}
