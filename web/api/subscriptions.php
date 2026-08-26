<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'];

$user = require_login();
$userId = (int)$user['id'];

if ($method === 'GET') {
    $stmt = $pdo->prepare(
        "SELECT p.id, p.title, p.description, p.artwork_url, p.author, p.category, p.active,
                us.subscribed_at, COUNT(e.id) AS episode_count
         FROM user_subscriptions us
         JOIN podcasts p ON p.id = us.podcast_id
         LEFT JOIN episodes e ON e.podcast_id = p.id
         WHERE us.user_id = ?
         GROUP BY p.id, us.subscribed_at
         ORDER BY p.title"
    );
    $stmt->execute([$userId]);
    json_response(['subscriptions' => $stmt->fetchAll()]);
}

if ($method !== 'POST') {
    json_response(['error' => 'Method not allowed'], 405);
}

verify_csrf();
$data = get_json_body();
$action = $data['action'] ?? '';
$podcastId = (int)($data['podcast_id'] ?? 0);

if ($action === 'subscribe') {
    if (!$podcastId) {
        json_response(['error' => 'Missing podcast_id'], 400);
    }
    $stmt = $pdo->prepare("INSERT IGNORE INTO user_subscriptions (user_id, podcast_id) VALUES (?, ?)");
    $stmt->execute([$userId, $podcastId]);
    json_response(['ok' => true, 'subscribed' => true]);
}

if ($action === 'unsubscribe') {
    if (!$podcastId) {
        json_response(['error' => 'Missing podcast_id'], 400);
    }
    $stmt = $pdo->prepare("DELETE FROM user_subscriptions WHERE user_id = ? AND podcast_id = ?");
    $stmt->execute([$userId, $podcastId]);
    json_response(['ok' => true, 'subscribed' => false]);
}

if ($action === 'bulk') {
    $ids = $data['podcast_ids'] ?? [];
    if (!is_array($ids)) {
        json_response(['error' => 'podcast_ids must be an array'], 400);
    }
    $cleanIds = [];
    foreach ($ids as $id) {
        $intId = (int)$id;
        if ($intId > 0) {
            $cleanIds[] = $intId;
        }
    }
    $pdo->beginTransaction();
    $pdo->prepare("DELETE FROM user_subscriptions WHERE user_id = ?")->execute([$userId]);
    if ($cleanIds) {
        $insertStmt = $pdo->prepare("INSERT INTO user_subscriptions (user_id, podcast_id) VALUES (?, ?)");
        foreach ($cleanIds as $id) {
            $insertStmt->execute([$userId, $id]);
        }
    }
    $pdo->commit();
    json_response(['ok' => true, 'count' => count($cleanIds)]);
}

json_response(['error' => 'Invalid action'], 400);
