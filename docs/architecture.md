# MicahPod Architecture

## System Components

| Component | Technology | Location |
|-----------|-----------|----------|
| Frontend | PHP templates + vanilla JS + CSS | `web/` directory |
| API | PHP REST endpoints | `web/api/` |
| Database | MySQL (InnoDB) | `comm.micahyoung.us` / `micahpod_db` |
| Hosting | DreamHost (PHP only, no Python) | `micahyoung.us/micahpod/` |
| Local project | Windows | `C:\Users\micah\Projects\micahpod\` |

## API Endpoints

| File | Purpose |
|------|---------|
| `config.php` | DB connection, auth helpers, CSRF, SMTP, feed refresh logic |
| `podcasts.php` | Podcast CRUD, feed refresh (single + all) |
| `episodes.php` | Episode listing with filters, subscription-aware, pagination |
| `subscriptions.php` | User podcast subscriptions (subscribe/unsubscribe/bulk) |
| `comments.php` | Threaded episode comments (CRUD, tree builder, count badges) |
| `ratings.php` | Episode star ratings |
| `tags.php` | Tag management (list, edit, merge, delete) |
| `tag_blacklist.php` | Prevent auto-recreation of bad tags |
| `extract_tags.php` | AI tag extraction from episode metadata |
| `taste_profile.php` | Build/update user taste profiles from ratings |
| `score_episodes.php` | Score episodes against taste profiles |
| `digest.php` | Generate and send daily digest emails |
| `player_state.php` | Sync playback state across devices |
| `progress.php` | Track per-episode playback progress |
| `settings.php` | User preference settings |
| `users.php` | User management (admin) |
| `admin.php` | Admin operations |
| `search.php` | Episode/podcast search |
| `recommendations.php` | Recommendation queries |
| `refresh.php` | Feed refresh trigger |

## Frontend Files

| File | Purpose |
|------|---------|
| `index.php` | Main app page — episode grid, filters, subscriptions panel, discussion view, player |
| `admin.php` | Admin panel — podcast management, user management, tag tools |
| `settings.php` | User settings page |
| `login.php` / `register.php` / `verify.php` / `reset.php` | Auth pages |
| `js/app.js` | Main app logic — episodes, subscriptions, discussions, filters |
| `js/player.js` | Audio player, queue, Chromecast, fullscreen, progress sync |
| `js/admin.js` | Admin panel logic |
| `js/settings.js` | Settings page logic |
| `js/auth.js` | Login/register form handling |
| `css/style.css` | All styles (dark theme) |

## Data Flow

### Player Queue
1. Queue lives in `localStorage` (`micahpod_queue`) plus `queue_updated_at` metadata.
2. Server stores queue on `user_player_state.queue` with its own `queue_updated_at`.
3. Progress heartbeats can include the queue, but empty queues never wipe a nonempty server queue unless the client timestamp is newer or `clear_queue` is set.
4. On page load, local and server queues are merged by timestamp; nonempty local queues are preserved and pushed up if newer.

### Episode Progress (cross-device bookmarks)
1. Playing an episode creates/updates `user_episode_progress` (listening profile).
2. Auto-saves run on pause, tab hide, page close (beacon), episode switch, and ~30s heartbeat.
3. **Save Progress** forces a bookmark write (`force=1` / `source=manual`) that always wins over stale auto saves.
4. Auto writes are rejected when `client_updated_at` is older than the stored `updated_at`.
5. Home **Continue Listening** loads unfinished bookmarks (`progress.php?action=in_progress`).
6. Play/Resume seeks from `position_seconds` when &gt; 30s and not completed.
7. `user_player_state` still stores the last player session (episode, queue, speed, volume).

### Episode Feed Refresh
1. Admin clicks "Refresh All" (or cron triggers `refresh.php`)
2. `refresh_podcast_feeds()` in `config.php` iterates active podcasts
3. For each podcast:
   - Sends conditional HTTP request (`If-None-Match` / `If-Modified-Since`)
   - If 304 Not Modified → skip (no changes)
   - Otherwise parses RSS XML, extracts episodes
   - Skips episodes older than `last_new_episode` (performance optimization)
   - `INSERT IGNORE` for each new episode (guid deduplication)
   - Stores response `ETag` and `Last-Modified` for next conditional fetch
4. New episodes are scored against all user taste profiles

### Subscription Filtering
1. User subscribes to podcasts via "My Podcasts" panel
2. Episode API checks `user_subscriptions` table
3. If user has ≥1 subscription → filter episodes to subscribed podcasts only
4. If zero subscriptions → show all episodes (default behavior)
5. `show_all=1` parameter bypasses filter for browsing

### Discussion Threading
1. User clicks "Discuss" on an episode card
2. Comments loaded as a tree (server builds nested structure via `parent_id`)
3. Top-level comments + replies nested up to 3 levels deep
4. Comment count badges batch-loaded for visible episode cards

## Migrations

All schema changes are documented in `migrations.php` as SQL comments. To run a migration on the live DB:

1. Create a temporary `run_migration.php` that reads `.env` from `dirname(__DIR__)` and executes the SQL
2. Upload via `upload_migration.py` helper script
3. Hit the URL: `https://micahyoung.us/micahpod/run_migration.php`
4. Verify success, then remove the script from server

**Note:** Direct DB connections from local machine are IP-restricted; always run migrations through the DreamHost PHP server.
