<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'];
$action = $_GET['action'] ?? null;

if ($method === 'GET') {
    $user = current_user();
    $all = ($_GET['all'] ?? '0') === '1';
    if ($all && (!$user || $user['role'] !== 'admin')) {
        json_response(['error' => 'Forbidden'], 403);
    }
    $sql = $all
        ? 'SELECT p.*, COUNT(e.id) AS episode_count FROM podcasts p LEFT JOIN episodes e ON e.podcast_id = p.id GROUP BY p.id ORDER BY p.title'
        : 'SELECT p.*, COUNT(e.id) AS episode_count FROM podcasts p LEFT JOIN episodes e ON e.podcast_id = p.id WHERE p.active = 1 GROUP BY p.id ORDER BY p.title';
    $stmt = $pdo->query($sql);
    json_response(['podcasts' => $stmt->fetchAll()]);
}

if ($method === 'POST' && $action) {
    require_admin();
    verify_csrf();
    if ($action === 'refresh_all') {
        $result = refresh_podcast_feeds(null);
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
        $result = refresh_podcast_feeds((int)$_GET['id']);
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
    $stmt = $pdo->prepare(
        "INSERT INTO podcasts (title, description, feed_url, website_url, artwork_url, author, category, itunes_id, is_manual, active, added_by)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)"
    );
    $stmt->execute([
        $data['title'] ?? '',
        $data['description'] ?? null,
        $data['feed_url'] ?? null,
        $data['website_url'] ?? null,
        $data['artwork_url'] ?? null,
        $data['author'] ?? null,
        $data['category'] ?? null,
        $data['itunes_id'] ?? null,
        !empty($data['is_manual']) ? 1 : 0,
        $_SESSION['user_id'] ?? null,
    ]);
    json_response(['ok' => true, 'id' => (int)$pdo->lastInsertId()]);
}

if ($method === 'PUT') {
    require_admin();
    verify_csrf();
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    $data = get_json_body();
    $stmt = $pdo->prepare(
        "UPDATE podcasts SET title = ?, description = ?, feed_url = ?, website_url = ?, artwork_url = ?, author = ?, category = ?, itunes_id = ?, is_manual = ?, active = ?
         WHERE id = ?"
    );
    $stmt->execute([
        $data['title'] ?? '',
        $data['description'] ?? null,
        $data['feed_url'] ?? null,
        $data['website_url'] ?? null,
        $data['artwork_url'] ?? null,
        $data['author'] ?? null,
        $data['category'] ?? null,
        $data['itunes_id'] ?? null,
        !empty($data['is_manual']) ? 1 : 0,
        isset($data['active']) ? (int)$data['active'] : 1,
        $id,
    ]);
    json_response(['ok' => true]);
}

if ($method === 'DELETE' && $action === 'purge') {
    require_admin();
    verify_csrf();
    $id = isset($_GET['id']) ? (int)$_GET['id'] : 0;
    if (!$id) {
        json_response(['error' => 'Missing id'], 400);
    }
    // Delete episodes first (cascade should handle it, but be explicit)
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
