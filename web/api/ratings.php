<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $user = require_login();
    $userId = isset($_GET['user_id']) ? (int)$_GET['user_id'] : (int)$user['id'];
    if ($userId !== (int)$user['id'] && $user['role'] !== 'admin') {
        json_response(['error' => 'Forbidden'], 403);
    }
    $stmt = $pdo->prepare(
        "SELECT er.*, e.title AS episode_title, p.title AS podcast_title
         FROM episode_ratings er
         JOIN episodes e ON e.id = er.episode_id
         JOIN podcasts p ON p.id = e.podcast_id
         WHERE er.user_id = ?
         ORDER BY er.created_at DESC"
    );
    $stmt->execute([$userId]);
    json_response(['ratings' => $stmt->fetchAll()]);
}

if ($method === 'POST') {
    $user = require_login();
    verify_csrf();
    $type = $_GET['type'] ?? 'episode';
    $data = get_json_body();

    if ($type === 'tag') {
        $tagId = (int)($data['tag_id'] ?? 0);
        $rating = (int)($data['rating'] ?? 0);
        if (!$tagId || $rating < 1 || $rating > 5) {
            json_response(['error' => 'Invalid tag rating'], 400);
        }
        $stmt = $pdo->prepare(
            "INSERT INTO tag_ratings (user_id, tag_id, rating)
             VALUES (?, ?, ?)
             ON DUPLICATE KEY UPDATE rating = VALUES(rating)"
        );
        $stmt->execute([(int)$user['id'], $tagId, $rating]);
        json_response(['ok' => true]);
    }

    $episodeId = (int)($data['episode_id'] ?? 0);
    $rating = (int)($data['rating'] ?? 0);
    $notes = $data['notes'] ?? null;
    if (!$episodeId || $rating < 1 || $rating > 5) {
        json_response(['error' => 'Invalid episode rating'], 400);
    }
    $existsStmt = $pdo->prepare("SELECT id FROM episode_ratings WHERE user_id = ? AND episode_id = ?");
    $existsStmt->execute([(int)$user['id'], $episodeId]);
    $isNewRating = !$existsStmt->fetchColumn();
    $stmt = $pdo->prepare(
        "INSERT INTO episode_ratings (user_id, episode_id, rating, notes)
         VALUES (?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE rating = VALUES(rating), notes = VALUES(notes)"
    );
    $stmt->execute([(int)$user['id'], $episodeId, $rating, $notes]);
    $recomputed = false;
    if ($isNewRating) {
        $countStmt = $pdo->prepare("SELECT COUNT(*) FROM episode_ratings WHERE user_id = ?");
        $countStmt->execute([(int)$user['id']]);
        $count = (int)$countStmt->fetchColumn();
        if ($count > 0 && $count % 5 === 0) {
            require_once __DIR__ . '/taste_profile.php';
            require_once __DIR__ . '/score_episodes.php';
            compute_taste_profile($pdo, (int)$user['id']);
            score_episodes_for_user($pdo, (int)$user['id']);
            $recomputed = true;
        }
    }
    json_response(['ok' => true, 'recomputed' => $recomputed]);
}

json_response(['error' => 'Method not allowed'], 405);
