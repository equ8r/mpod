<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'];
if ($method === 'POST') {
    require_admin();
    verify_csrf();
    $data = get_json_body();
    $podcastId = (int)($data['podcast_id'] ?? 0);
    $title = trim($data['title'] ?? '');
    $pubDate = $data['pub_date'] ?? '';
    if ($pubDate && strpos($pubDate, 'T') !== false) {
        $pubDate = str_replace('T', ' ', $pubDate);
        if (strlen($pubDate) === 16) {
            $pubDate .= ':00';
        }
    }
    if (!$podcastId || $title === '' || $pubDate === '') {
        json_response(['error' => 'Missing episode fields'], 400);
    }
    $stmt = $pdo->prepare(
        "INSERT INTO episodes (podcast_id, guid, title, description, description_html, pub_date, duration_seconds, audio_url, artwork_url, episode_number, season_number, is_manual)
         VALUES (?, NULL, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 1)"
    );
    $stmt->execute([
        $podcastId,
        $title,
        $data['description'] ?? null,
        $data['description'] ?? null,
        $pubDate,
        $data['audio_url'] ?? null,
        $data['artwork_url'] ?? null,
    ]);
    json_response(['ok' => true, 'id' => (int)$pdo->lastInsertId()]);
}

if ($method !== 'GET') {
    json_response(['error' => 'Method not allowed'], 405);
}
$user = current_user();
$userId = $user ? (int)$user['id'] : null;

$id = isset($_GET['id']) ? (int)$_GET['id'] : null;
if ($id) {
    $stmt = $pdo->prepare(
        "SELECT e.*, p.title AS podcast_title, p.artwork_url AS podcast_artwork,
                er.rating AS user_rating, rs.score AS rec_score, rs.factors_json
         FROM episodes e
         JOIN podcasts p ON p.id = e.podcast_id
         LEFT JOIN episode_ratings er ON er.episode_id = e.id AND er.user_id = ?
         LEFT JOIN recommendation_scores rs ON rs.episode_id = e.id AND rs.user_id = ?
         WHERE e.id = ? AND p.active = 1"
    );
    $stmt->execute([$userId, $userId, $id]);
    $episode = $stmt->fetch();
    if (!$episode) {
        json_response(['error' => 'Not found'], 404);
    }
    $tagStmt = $pdo->prepare(
        "SELECT t.id, t.name, t.type, et.source, et.confidence
         FROM episode_tags et
         JOIN tags t ON t.id = et.tag_id
         WHERE et.episode_id = ?"
    );
    $tagStmt->execute([$id]);
    $episode['tags'] = $tagStmt->fetchAll();
    json_response(['episode' => $episode]);
}

$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = max(1, min(100, (int)($_GET['per_page'] ?? 50)));
$sort = $_GET['sort'] ?? 'newest';
$podcastId = isset($_GET['podcast_id']) ? (int)$_GET['podcast_id'] : null;
$tag = $_GET['tag'] ?? null;
$rated = $_GET['rated'] ?? null;
$recommended = $_GET['recommended'] ?? null;
$showAll = ($_GET['show_all'] ?? '0') === '1';
$search = trim($_GET['q'] ?? '');

$where = [];
$params = [$userId, $userId];

$where[] = 'p.active = 1';

if ($podcastId) {
    $where[] = 'e.podcast_id = ?';
    $params[] = $podcastId;
}
if ($userId && !$podcastId && !$showAll) {
    $subCountStmt = $pdo->prepare("SELECT COUNT(*) FROM user_subscriptions WHERE user_id = ?");
    $subCountStmt->execute([$userId]);
    $subCount = (int)$subCountStmt->fetchColumn();
    if ($subCount > 0) {
        $where[] = 'e.podcast_id IN (SELECT podcast_id FROM user_subscriptions WHERE user_id = ?)';
        $params[] = $userId;
    }
}
if ($search !== '') {
    $where[] = '(e.title LIKE ? OR e.description LIKE ? OR p.title LIKE ?)';
    $like = '%' . $search . '%';
    $params[] = $like;
    $params[] = $like;
    $params[] = $like;
}
if ($rated === '1') {
    $where[] = 'er.rating IS NOT NULL';
}
if ($rated === '0') {
    $where[] = 'er.rating IS NULL';
}
if ($recommended === '1') {
    $where[] = 'rs.score >= 0.7';
}
if ($tag) {
    if (ctype_digit($tag)) {
        $where[] = 'et.tag_id = ?';
        $params[] = (int)$tag;
    } else {
        $where[] = 't.name = ?';
        $params[] = $tag;
    }
}

$whereSql = $where ? ('WHERE ' . implode(' AND ', $where)) : '';

$sortSql = 'e.pub_date DESC';
if ($sort === 'rating') {
    $sortSql = '(er.rating IS NULL) ASC, er.rating DESC, e.pub_date DESC';
} elseif ($sort === 'recommended') {
    $sortSql = '(rs.score IS NULL) ASC, rs.score DESC, e.pub_date DESC';
}

$offset = ($page - 1) * $perPage;

$countSql = "SELECT COUNT(DISTINCT e.id)
             FROM episodes e
             JOIN podcasts p ON p.id = e.podcast_id
             LEFT JOIN episode_ratings er ON er.episode_id = e.id AND er.user_id = ?
             LEFT JOIN recommendation_scores rs ON rs.episode_id = e.id AND rs.user_id = ?
             LEFT JOIN episode_tags et ON et.episode_id = e.id
             LEFT JOIN tags t ON t.id = et.tag_id
             {$whereSql}";

$countStmt = $pdo->prepare($countSql);
$countStmt->execute($params);
$total = (int)$countStmt->fetchColumn();

$sql = "SELECT DISTINCT e.*, p.title AS podcast_title, p.artwork_url AS podcast_artwork,
               er.rating AS user_rating, rs.score AS rec_score, rs.factors_json
        FROM episodes e
        JOIN podcasts p ON p.id = e.podcast_id
        LEFT JOIN episode_ratings er ON er.episode_id = e.id AND er.user_id = ?
        LEFT JOIN recommendation_scores rs ON rs.episode_id = e.id AND rs.user_id = ?
        LEFT JOIN episode_tags et ON et.episode_id = e.id
        LEFT JOIN tags t ON t.id = et.tag_id
        {$whereSql}
        ORDER BY {$sortSql}
        LIMIT {$perPage} OFFSET {$offset}";

$stmt = $pdo->prepare($sql);
$stmt->execute($params);
$episodes = $stmt->fetchAll();

if ($episodes) {
    $episodeIds = array_map(fn($row) => (int)$row['id'], $episodes);
    $inClause = implode(',', array_fill(0, count($episodeIds), '?'));
    $tagStmt = $pdo->prepare(
        "SELECT et.episode_id, t.id, t.name, t.type, et.source, et.confidence
         FROM episode_tags et
         JOIN tags t ON t.id = et.tag_id
         WHERE et.episode_id IN ({$inClause})"
    );
    $tagStmt->execute($episodeIds);
    $tagsByEpisode = [];
    foreach ($tagStmt->fetchAll() as $tagRow) {
        $eid = (int)$tagRow['episode_id'];
        if (!isset($tagsByEpisode[$eid])) {
            $tagsByEpisode[$eid] = [];
        }
        $tagsByEpisode[$eid][] = $tagRow;
    }
    foreach ($episodes as &$episode) {
        $eid = (int)$episode['id'];
        $episode['tags'] = $tagsByEpisode[$eid] ?? [];
    }
}

json_response([
    'episodes' => $episodes,
    'page' => $page,
    'per_page' => $perPage,
    'total' => $total,
]);
