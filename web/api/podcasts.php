<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'];
$action = $_GET['action'] ?? null;

function podcast_has_curated_column(PDO $pdo): bool {
    static $checked = null;
    if ($checked !== null) {
        return $checked;
    }
    try {
        $pdo->query('SELECT curated FROM podcasts LIMIT 1');
        $checked = true;
    } catch (Throwable $e) {
        $checked = false;
    }
    return $checked;
}

function normalize_feed_url(?string $url): ?string {
    if ($url === null) {
        return null;
    }
    $url = trim($url);
    if ($url === '') {
        return null;
    }
    return rtrim($url, "/ \t\n\r\0\x0B");
}

function find_existing_podcast(PDO $pdo, ?string $feedUrl, $itunesId): ?array {
    if ($itunesId) {
        $stmt = $pdo->prepare('SELECT * FROM podcasts WHERE itunes_id = ? LIMIT 1');
        $stmt->execute([(int)$itunesId]);
        $row = $stmt->fetch();
        if ($row) {
            return $row;
        }
    }
    $feedUrl = normalize_feed_url($feedUrl);
    if ($feedUrl) {
        $stmt = $pdo->prepare('SELECT * FROM podcasts WHERE feed_url = ? LIMIT 1');
        $stmt->execute([$feedUrl]);
        $row = $stmt->fetch();
        if ($row) {
            return $row;
        }
        // Loose match without trailing slash / scheme differences
        $stmt = $pdo->prepare('SELECT * FROM podcasts WHERE TRIM(TRAILING "/" FROM feed_url) = ? LIMIT 1');
        $stmt->execute([rtrim($feedUrl, '/')]);
        $row = $stmt->fetch();
        if ($row) {
            return $row;
        }
    }
    return null;
}

function ensure_subscription(PDO $pdo, int $userId, int $podcastId): void {
    $stmt = $pdo->prepare(
        'INSERT IGNORE INTO user_subscriptions (user_id, podcast_id, include_in_feed) VALUES (?, ?, 1)'
    );
    try {
        $stmt->execute([$userId, $podcastId]);
    } catch (Throwable $e) {
        // Pre-migration fallback without include_in_feed
        $pdo->prepare('INSERT IGNORE INTO user_subscriptions (user_id, podcast_id) VALUES (?, ?)')
            ->execute([$userId, $podcastId]);
    }
}

function log_podcast_introduction(PDO $pdo, int $podcastId, int $userId, string $source): void {
    try {
        $stmt = $pdo->prepare(
            'INSERT IGNORE INTO podcast_introductions (podcast_id, user_id, source) VALUES (?, ?, ?)'
        );
        $stmt->execute([$podcastId, $userId, $source]);
    } catch (Throwable $e) {
        // Table may not exist until migration runs.
    }
}

function fetch_feed_metadata(string $feedUrl): array {
    $ctx = stream_context_create([
        'http' => [
            'timeout' => 20,
            'user_agent' => 'MicahPod/1.0',
        ],
    ]);
    $xml = @file_get_contents($feedUrl, false, $ctx);
    if (!$xml) {
        return [];
    }
    libxml_use_internal_errors(true);
    $feed = simplexml_load_string($xml);
    if (!$feed) {
        return [];
    }
    $channel = $feed->channel ?? $feed;
    $itunes = $channel->children('http://www.itunes.com/dtds/podcast-1.0.dtd');
    $artwork = (string)($itunes->image['href'] ?? '');
    if ($artwork === '' && isset($channel->image->url)) {
        $artwork = (string)$channel->image->url;
    }
    return [
        'title' => trim((string)($channel->title ?? '')) ?: null,
        'description' => trim(strip_tags((string)($channel->description ?? ''))) ?: null,
        'website_url' => trim((string)($channel->link ?? '')) ?: null,
        'artwork_url' => $artwork !== '' ? $artwork : null,
        'author' => trim((string)($itunes->author ?? $channel->author ?? '')) ?: null,
    ];
}

if ($method === 'GET' && $action === 'community') {
    require_login();
    if (!podcast_has_curated_column($pdo)) {
        json_response(['podcasts' => []]);
    }
    $sql = "SELECT p.id, p.title, p.description, p.artwork_url, p.author, p.category, p.feed_url,
                   p.curated, p.last_new_episode,
                   COUNT(DISTINCT us.user_id) AS subscriber_count,
                   COUNT(e.id) AS episode_count,
                   MAX(e.pub_date) AS latest_episode
            FROM podcasts p
            JOIN user_subscriptions us ON us.podcast_id = p.id
            LEFT JOIN episodes e ON e.podcast_id = p.id
            WHERE p.active = 1 AND COALESCE(p.curated, 1) = 0
            GROUP BY p.id
            HAVING subscriber_count > 1
               AND latest_episode IS NOT NULL
               AND latest_episode >= DATE_SUB(NOW(), INTERVAL 30 DAY)
            ORDER BY subscriber_count DESC, latest_episode DESC
            LIMIT 40";
    $rows = $pdo->query($sql)->fetchAll();
    json_response(['podcasts' => $rows]);
}

if ($method === 'GET' && $action === 'user_added_report') {
    require_admin();
    if (!podcast_has_curated_column($pdo)) {
        json_response(['podcasts' => []]);
    }
    $sql = "SELECT p.id, p.title, p.artwork_url, p.feed_url, p.curated, p.created_at, p.last_new_episode,
                   COUNT(DISTINCT us.user_id) AS subscriber_count,
                   COUNT(DISTINCT pi.user_id) AS introducer_count,
                   MIN(pi.created_at) AS first_added_at
            FROM podcasts p
            LEFT JOIN user_subscriptions us ON us.podcast_id = p.id
            LEFT JOIN podcast_introductions pi ON pi.podcast_id = p.id
            WHERE COALESCE(p.curated, 1) = 0
            GROUP BY p.id
            ORDER BY subscriber_count DESC, first_added_at DESC";
    json_response(['podcasts' => $pdo->query($sql)->fetchAll()]);
}

if ($method === 'GET') {
    $user = current_user();
    $all = ($_GET['all'] ?? '0') === '1';
    $catalog = $_GET['catalog'] ?? 'curated'; // curated | all_active | subscribed_mix
    if ($all && (!$user || $user['role'] !== 'admin')) {
        json_response(['error' => 'Forbidden'], 403);
    }

    $hasCurated = podcast_has_curated_column($pdo);
    if ($all) {
        $sql = 'SELECT p.*, COUNT(e.id) AS episode_count FROM podcasts p LEFT JOIN episodes e ON e.podcast_id = p.id GROUP BY p.id ORDER BY p.title';
        $stmt = $pdo->query($sql);
        json_response(['podcasts' => $stmt->fetchAll()]);
    }

    if ($catalog === 'all_active' || !$hasCurated) {
        $sql = 'SELECT p.*, COUNT(e.id) AS episode_count FROM podcasts p LEFT JOIN episodes e ON e.podcast_id = p.id WHERE p.active = 1 GROUP BY p.id ORDER BY p.title';
    } else {
        // Default catalog: curated shows only (user-added appear in community / subscriptions)
        $sql = 'SELECT p.*, COUNT(e.id) AS episode_count FROM podcasts p LEFT JOIN episodes e ON e.podcast_id = p.id WHERE p.active = 1 AND COALESCE(p.curated, 1) = 1 GROUP BY p.id ORDER BY p.title';
    }
    $stmt = $pdo->query($sql);
    json_response(['podcasts' => $stmt->fetchAll()]);
}

if ($method === 'POST' && $action === 'add_user') {
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $userId = (int)$user['id'];
    $feedUrl = normalize_feed_url($data['feed_url'] ?? null);
    $itunesId = $data['itunes_id'] ?? null;
    $source = trim((string)($data['source'] ?? 'user_search'));
    if (!in_array($source, ['user_search', 'user_rss'], true)) {
        $source = 'user_search';
    }
    if (!$feedUrl && !$itunesId) {
        json_response(['error' => 'Provide a feed URL or iTunes result'], 400);
    }

    $existing = find_existing_podcast($pdo, $feedUrl, $itunesId);
    if ($existing) {
        $podcastId = (int)$existing['id'];
        if ((int)$existing['active'] !== 1) {
            $pdo->prepare('UPDATE podcasts SET active = 1 WHERE id = ?')->execute([$podcastId]);
        }
        ensure_subscription($pdo, $userId, $podcastId);
        log_podcast_introduction($pdo, $podcastId, $userId, $source);
        // Best-effort refresh so episodes appear quickly
        try {
            refresh_podcast_feeds($podcastId, false);
        } catch (Throwable $e) {
            // ignore
        }
        json_response([
            'ok' => true,
            'id' => $podcastId,
            'created' => false,
            'podcast' => $existing,
        ]);
    }

    $meta = $feedUrl ? fetch_feed_metadata($feedUrl) : [];
    $title = trim((string)($data['title'] ?? $meta['title'] ?? ''));
    if ($title === '') {
        $title = 'Untitled podcast';
    }
    $description = $data['description'] ?? $meta['description'] ?? null;
    $websiteUrl = $data['website_url'] ?? $meta['website_url'] ?? null;
    $artworkUrl = $data['artwork_url'] ?? $meta['artwork_url'] ?? null;
    $author = $data['author'] ?? $meta['author'] ?? null;
    $category = $data['category'] ?? null;

    if ($feedUrl === null) {
        json_response(['error' => 'A feed URL is required to add a new podcast'], 400);
    }

    if (podcast_has_curated_column($pdo)) {
        $stmt = $pdo->prepare(
            "INSERT INTO podcasts (title, description, feed_url, website_url, artwork_url, author, category, itunes_id, is_manual, curated, active, added_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 1, ?)"
        );
        $stmt->execute([
            $title,
            $description,
            $feedUrl,
            $websiteUrl,
            $artworkUrl,
            $author,
            $category,
            $itunesId ? (int)$itunesId : null,
            $userId,
        ]);
    } else {
        $stmt = $pdo->prepare(
            "INSERT INTO podcasts (title, description, feed_url, website_url, artwork_url, author, category, itunes_id, is_manual, active, added_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 1, ?)"
        );
        $stmt->execute([
            $title,
            $description,
            $feedUrl,
            $websiteUrl,
            $artworkUrl,
            $author,
            $category,
            $itunesId ? (int)$itunesId : null,
            $userId,
        ]);
    }
    $podcastId = (int)$pdo->lastInsertId();
    ensure_subscription($pdo, $userId, $podcastId);
    log_podcast_introduction($pdo, $podcastId, $userId, $source);

    $refresh = null;
    try {
        $refresh = refresh_podcast_feeds($podcastId, false);
    } catch (Throwable $e) {
        $refresh = ['ok' => false, 'error' => $e->getMessage()];
    }

    $stmt = $pdo->prepare('SELECT * FROM podcasts WHERE id = ?');
    $stmt->execute([$podcastId]);
    json_response([
        'ok' => true,
        'id' => $podcastId,
        'created' => true,
        'podcast' => $stmt->fetch(),
        'refresh' => $refresh,
    ]);
}

if ($method === 'POST' && $action === 'promote') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    $id = (int)($data['podcast_id'] ?? $_GET['id'] ?? 0);
    if (!$id) {
        json_response(['error' => 'Missing podcast_id'], 400);
    }
    if (!podcast_has_curated_column($pdo)) {
        json_response(['error' => 'Run migrations first'], 500);
    }
    $pdo->prepare('UPDATE podcasts SET curated = 1 WHERE id = ?')->execute([$id]);
    json_response(['ok' => true, 'curated' => true]);
}

if ($method === 'POST' && $action) {
    require_admin();
    verify_csrf();
    if ($action === 'refresh_all') {
        $result = refresh_podcast_feeds(null, false);
        $newEpisodeIds = $result['new_episode_ids'] ?? [];
        if ($newEpisodeIds) {
            require_once __DIR__ . '/score_episodes.php';
            $users = $pdo->query("SELECT user_id FROM taste_profiles")->fetchAll();
            foreach ($users as $row) {
                score_episodes_for_user($pdo, (int)$row['user_id'], ['episode_ids' => $newEpisodeIds]);
            }
            $result['scored_users'] = count($users);
        }
        json_response($result);
    }
    if ($action === 'refresh' && isset($_GET['id'])) {
        $result = refresh_podcast_feeds((int)$_GET['id'], false);
        $newEpisodeIds = $result['new_episode_ids'] ?? [];
        if ($newEpisodeIds) {
            require_once __DIR__ . '/score_episodes.php';
            $users = $pdo->query("SELECT user_id FROM taste_profiles")->fetchAll();
            foreach ($users as $row) {
                score_episodes_for_user($pdo, (int)$row['user_id'], ['episode_ids' => $newEpisodeIds]);
            }
            $result['scored_users'] = count($users);
        }
        json_response($result);
    }
}

if ($method === 'POST') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    if (podcast_has_curated_column($pdo)) {
        $stmt = $pdo->prepare(
            "INSERT INTO podcasts (title, description, feed_url, website_url, artwork_url, author, category, itunes_id, is_manual, curated, active, added_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?)"
        );
        $stmt->execute([
            $data['title'] ?? '',
            $data['description'] ?? null,
            normalize_feed_url($data['feed_url'] ?? null),
            $data['website_url'] ?? null,
            $data['artwork_url'] ?? null,
            $data['author'] ?? null,
            $data['category'] ?? null,
            $data['itunes_id'] ?? null,
            !empty($data['is_manual']) ? 1 : 0,
            $_SESSION['user_id'] ?? null,
        ]);
    } else {
        $stmt = $pdo->prepare(
            "INSERT INTO podcasts (title, description, feed_url, website_url, artwork_url, author, category, itunes_id, is_manual, active, added_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)"
        );
        $stmt->execute([
            $data['title'] ?? '',
            $data['description'] ?? null,
            normalize_feed_url($data['feed_url'] ?? null),
            $data['website_url'] ?? null,
            $data['artwork_url'] ?? null,
            $data['author'] ?? null,
            $data['category'] ?? null,
            $data['itunes_id'] ?? null,
            !empty($data['is_manual']) ? 1 : 0,
            $_SESSION['user_id'] ?? null,
        ]);
    }
    $id = (int)$pdo->lastInsertId();
    if (!empty($_SESSION['user_id'])) {
        log_podcast_introduction($pdo, $id, (int)$_SESSION['user_id'], 'admin');
    }
    json_response(['ok' => true, 'id' => $id]);
}

if ($method === 'PUT') {
    require_admin();
    verify_csrf();
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    $data = get_json_body();
    if (podcast_has_curated_column($pdo) && array_key_exists('curated', $data)) {
        $stmt = $pdo->prepare(
            "UPDATE podcasts SET title = ?, description = ?, feed_url = ?, website_url = ?, artwork_url = ?, author = ?, category = ?, itunes_id = ?, is_manual = ?, active = ?, curated = ?
             WHERE id = ?"
        );
        $stmt->execute([
            $data['title'] ?? '',
            $data['description'] ?? null,
            normalize_feed_url($data['feed_url'] ?? null),
            $data['website_url'] ?? null,
            $data['artwork_url'] ?? null,
            $data['author'] ?? null,
            $data['category'] ?? null,
            $data['itunes_id'] ?? null,
            !empty($data['is_manual']) ? 1 : 0,
            isset($data['active']) ? (int)$data['active'] : 1,
            !empty($data['curated']) ? 1 : 0,
            $id,
        ]);
    } else {
        $stmt = $pdo->prepare(
            "UPDATE podcasts SET title = ?, description = ?, feed_url = ?, website_url = ?, artwork_url = ?, author = ?, category = ?, itunes_id = ?, is_manual = ?, active = ?
             WHERE id = ?"
        );
        $stmt->execute([
            $data['title'] ?? '',
            $data['description'] ?? null,
            normalize_feed_url($data['feed_url'] ?? null),
            $data['website_url'] ?? null,
            $data['artwork_url'] ?? null,
            $data['author'] ?? null,
            $data['category'] ?? null,
            $data['itunes_id'] ?? null,
            !empty($data['is_manual']) ? 1 : 0,
            isset($data['active']) ? (int)$data['active'] : 1,
            $id,
        ]);
    }
    json_response(['ok' => true]);
}

if ($method === 'DELETE' && $action === 'purge') {
    require_admin();
    verify_csrf();
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    $pdo->prepare('DELETE FROM episodes WHERE podcast_id = ?')->execute([$id]);
    $pdo->prepare('DELETE FROM podcasts WHERE id = ?')->execute([$id]);
    json_response(['ok' => true]);
}

if ($method === 'DELETE') {
    require_admin();
    verify_csrf();
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    $stmt = $pdo->prepare('UPDATE podcasts SET active = 0 WHERE id = ?');
    $stmt->execute([$id]);
    json_response(['ok' => true]);
}

json_response(['error' => 'Method not allowed'], 405);
