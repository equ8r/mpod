<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'];

$user = require_login();
$userId = (int)$user['id'];

function subscriptions_have_include_in_feed(PDO $pdo): bool {
    static $checked = null;
    if ($checked !== null) {
        return $checked;
    }
    try {
        $pdo->query('SELECT include_in_feed FROM user_subscriptions LIMIT 1');
        $checked = true;
    } catch (Throwable $e) {
        $checked = false;
    }
    return $checked;
}

if ($method === 'GET') {
    $hasFeedFlag = subscriptions_have_include_in_feed($pdo);
    $feedSelect = $hasFeedFlag ? 'us.include_in_feed' : '1 AS include_in_feed';
    $curatedSelect = '1 AS curated';
    try {
        $pdo->query('SELECT curated FROM podcasts LIMIT 1');
        $curatedSelect = 'COALESCE(p.curated, 1) AS curated';
    } catch (Throwable $e) {
        // ignore
    }
    $stmt = $pdo->prepare(
        "SELECT p.id, p.title, p.description, p.artwork_url, p.author, p.category, p.active, p.feed_url,
                {$curatedSelect},
                us.subscribed_at, {$feedSelect},
                COUNT(e.id) AS episode_count
         FROM user_subscriptions us
         JOIN podcasts p ON p.id = us.podcast_id
         LEFT JOIN episodes e ON e.podcast_id = p.id
         WHERE us.user_id = ?
         GROUP BY p.id, us.subscribed_at" . ($hasFeedFlag ? ', us.include_in_feed' : '') . "
         ORDER BY p.title"
    );
    $stmt->execute([$userId]);
    $rows = $stmt->fetchAll();
    foreach ($rows as &$row) {
        $row['include_in_feed'] = (int)($row['include_in_feed'] ?? 1);
        $row['curated'] = (int)($row['curated'] ?? 1);
    }
    unset($row);
    json_response(['subscriptions' => $rows]);
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
    if (subscriptions_have_include_in_feed($pdo)) {
        $stmt = $pdo->prepare(
            "INSERT INTO user_subscriptions (user_id, podcast_id, include_in_feed)
             VALUES (?, ?, 1)
             ON DUPLICATE KEY UPDATE user_id = user_id"
        );
        $stmt->execute([$userId, $podcastId]);
    } else {
        $stmt = $pdo->prepare("INSERT IGNORE INTO user_subscriptions (user_id, podcast_id) VALUES (?, ?)");
        $stmt->execute([$userId, $podcastId]);
    }
    json_response(['ok' => true, 'subscribed' => true, 'include_in_feed' => 1]);
}

if ($action === 'unsubscribe') {
    if (!$podcastId) {
        json_response(['error' => 'Missing podcast_id'], 400);
    }
    $stmt = $pdo->prepare("DELETE FROM user_subscriptions WHERE user_id = ? AND podcast_id = ?");
    $stmt->execute([$userId, $podcastId]);
    json_response(['ok' => true, 'subscribed' => false]);
}

if ($action === 'set_include_in_feed') {
    if (!$podcastId) {
        json_response(['error' => 'Missing podcast_id'], 400);
    }
    if (!subscriptions_have_include_in_feed($pdo)) {
        json_response(['error' => 'Run migrations first'], 500);
    }
    $include = !empty($data['include_in_feed']) ? 1 : 0;
    $stmt = $pdo->prepare(
        "UPDATE user_subscriptions SET include_in_feed = ? WHERE user_id = ? AND podcast_id = ?"
    );
    $stmt->execute([$include, $userId, $podcastId]);
    if ($stmt->rowCount() === 0) {
        // Not subscribed yet — subscribe with requested flag
        $ins = $pdo->prepare(
            "INSERT INTO user_subscriptions (user_id, podcast_id, include_in_feed) VALUES (?, ?, ?)"
        );
        $ins->execute([$userId, $podcastId, $include]);
    }
    json_response(['ok' => true, 'include_in_feed' => $include]);
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
        if (subscriptions_have_include_in_feed($pdo)) {
            $insertStmt = $pdo->prepare(
                "INSERT INTO user_subscriptions (user_id, podcast_id, include_in_feed) VALUES (?, ?, 1)"
            );
        } else {
            $insertStmt = $pdo->prepare("INSERT INTO user_subscriptions (user_id, podcast_id) VALUES (?, ?)");
        }
        foreach ($cleanIds as $id) {
            $insertStmt->execute([$userId, $id]);
        }
    }
    $pdo->commit();
    json_response(['ok' => true, 'count' => count($cleanIds)]);
}

json_response(['error' => 'Invalid action'], 400);
