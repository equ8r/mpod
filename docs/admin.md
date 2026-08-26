# MicahPod Admin Tasks

## Adding a New Podcast

1. Log in as admin at https://micahyoung.us/micahpod/
2. Go to **Admin** panel
3. Use "Add Podcast" — paste the RSS feed URL
4. Submit and wait for feed parsing
5. Click "Refresh" on the new podcast to pull all episodes
6. Verify podcast appears with correct title, artwork, and episode count

## Refreshing Feeds

- **Single podcast:** Admin panel → click Refresh on the podcast row
- **All podcasts:** Admin panel → "Refresh All" button, or hit `api/refresh.php`
- **Cron:** "MicahPod Feed Refresh" runs hourly via Deepseek

### Feed Refresh Optimization
Feeds use conditional HTTP requests (ETag + Last-Modified headers). If a feed hasn't changed since last check, the server returns 304 and we skip it entirely. Additionally, only episodes newer than the last known new episode are processed. This means refreshing 20+ podcasts should take seconds, not minutes.

## Managing Users

- **Admin panel → Users section** shows all registered users
- Admin can manually verify unverified accounts
- Admin can change user roles

## Managing Tags

- **Admin panel → Tags section** for viewing, editing, merging, and deleting tags
- **Tag blacklist:** deleted tags are blacklisted to prevent auto-recreation during extraction
- **Extraction:** runs against new episodes; compares against existing vetted tags before creating new ones
- **Bulk cleanup:** admin can filter and mass-delete bad tag associations

## Managing Subscriptions (User-Facing)

- Users manage their own subscriptions via **My Podcasts** nav link
- Shows all active podcasts as cards with subscribe/unsubscribe toggle
- Episode feed automatically filters to subscribed podcasts
- "Show All Episodes" toggle in filters bypasses subscription filter

## Episode Discussions

- **Threaded comments** on every episode (up to 3 levels deep)
- Users click "Discuss" on episode cards to open discussion view
- Comment count badges visible on episode cards
- Admin can delete any comment; users can edit/delete their own
- Plain text only, no markdown, 5000 char max

## Troubleshooting

| Symptom | Check |
|---------|-------|
| UI won't load | DreamHost status, PHP errors in browser console |
| Episodes not appearing | Feed refresh — check if feed URL is valid |
| Slow refresh | Check if ETag/Last-Modified optimization is working (should skip unchanged feeds) |
| Missing tags | Run tag extraction from admin panel |
| Stale recommendations | Trigger taste profile rebuild + episode scoring |
| Player not syncing | Check `user_player_state` table; verify `player_state.php` API |
| Comments not loading | Check `episode_comments` table exists; verify API response |
| Subscription filter not working | Check `user_subscriptions` table; verify episodes API includes filter logic |
