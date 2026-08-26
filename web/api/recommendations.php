<?php
require_once __DIR__ . '/config.php';

$user = require_login();
$pdo = db();

$limit = max(1, min(100, (int)($_GET['limit'] ?? 50)));

$stmt = $pdo->prepare(
    "SELECT e.*, p.title AS podcast_title, p.artwork_url AS podcast_artwork,
            rs.score, rs.factors_json
     FROM recommendation_scores rs
     JOIN episodes e ON e.id = rs.episode_id
     JOIN podcasts p ON p.id = e.podcast_id
     LEFT JOIN episode_ratings er ON er.episode_id = e.id AND er.user_id = ?
     WHERE rs.user_id = ? AND er.id IS NULL
     ORDER BY rs.score DESC
     LIMIT {$limit}"
);
$stmt->execute([(int)$user['id'], (int)$user['id']]);

json_response(['recommendations' => $stmt->fetchAll()]);
