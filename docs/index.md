# MicahPod Overview

MicahPod is a curated podcast platform with AI-driven recommendations, user subscriptions, threaded episode discussions, and an embedded player with Chromecast support. PHP + vanilla JS web app with MySQL backend on DreamHost.

## Live Site

- https://micahyoung.us/micahpod/

## Core Features

- **User authentication** — registration, login, email verification, password reset
- **Podcast management** — admin adds podcasts via RSS feed URL; automatic feed parsing
- **User subscriptions** — users subscribe to specific podcasts; episode feed filters to subscribed shows (zero subscriptions = see all)
- **Episode discussions** — threaded comments on episodes (max 3 levels deep, old-school forum style)
- **Embedded player** — persistent bottom bar + fullscreen mode, queue management, Chromecast support, playback sync across devices
- **Star ratings** — rate episodes 1-5 stars
- **AI tag extraction** — auto-extracts guest names and topics from episode titles/descriptions
- **Taste profiles & recommendations** — builds user preference profiles from ratings; scores episodes by relevance
- **Daily digest emails** — personalized episode recommendations via email
- **Admin panel** — podcast CRUD, user management, tag cleanup, feed refresh

## Database Tables

| Table | Purpose |
|-------|---------|
| `users` | User accounts, roles, preferences |
| `podcasts` | Podcast metadata, feed URLs, artwork |
| `episodes` | Episode data parsed from RSS feeds |
| `user_subscriptions` | Which podcasts each user follows |
| `episode_comments` | Threaded discussion comments on episodes |
| `episode_ratings` | User star ratings per episode |
| `tags` | Extracted topic/guest tags |
| `episode_tags` | Tag-to-episode associations |
| `tag_blacklist` | Tags to never auto-create again |
| `taste_profiles` | User preference vectors |
| `recommendation_scores` | Per-user episode recommendation scores |
| `user_player_state` | Current playback state (episode, position, queue) |
| `user_episode_progress` | Per-episode playback progress and completion |

## Page Guide

- **Architecture** — system components, data flow, feed refresh optimization
- **Admin** — day-to-day operations (add podcasts, manage users/tags)
- **Cron Jobs** — scheduled tasks and their cadence
- **Deployment** — how to deploy changes to production
