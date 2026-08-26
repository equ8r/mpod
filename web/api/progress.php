<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$user = require_login();
$method = $_SERVER['REQUEST_METHOD'];
$userId = (int)$user['id'];

if ($method === 'GET') {
    $action = trim((string)($_GET['action'] ?? ''));
    if ($action === 'in_progress') {
        $limit = max(1, min(50, (int)($_GET['limit'] ?? 20)));
        $stmt = $pdo->prepare(
            "SELECT uep.episode_id, uep.position_seconds, uep.completed, uep.updated_at,
                    uep.started_at, uep.last_played_at, uep.saved_manually_at, uep.save_source,
                    e.title, e.audio_url, e.artwork_url, e.duration_seconds, e.podcast_id,
                    p.title AS podcast_title, p.artwork_url AS podcast_artwork
             FROM user_episode_progress uep
             INNER JOIN episodes e ON e.id = uep.episode_id
             INNER JOIN podcasts p ON p.id = e.podcast_id
             WHERE uep.user_id = ?
               AND uep.completed = 0
               AND uep.position_seconds >= 30
               AND e.audio_url IS NOT NULL
               AND e.audio_url != ''
               AND NOT (
                    e.duration_seconds IS NOT NULL
                    AND e.duration_seconds > 0
                    AND uep.position_seconds >= (e.duration_seconds * 0.9)
               )
             ORDER BY COALESCE(uep.last_played_at, uep.updated_at) DESC
             LIMIT {$limit}"
        );
        $stmt->execute([$userId]);
        $rows = $stmt->fetchAll();
        $episodes = [];
        foreach ($rows as $row) {
            $episodes[] = [
                'id' => (int)$row['episode_id'],
                'episode_id' => (int)$row['episode_id'],
                'podcast_id' => (int)$row['podcast_id'],
                'title' => $row['title'],
                'audio_url' => $row['audio_url'],
                'artwork_url' => $row['artwork_url'],
                'podcast_title' => $row['podcast_title'],
                'podcast_artwork' => $row['podcast_artwork'],
                'duration_seconds' => $row['duration_seconds'] !== null ? (int)$row['duration_seconds'] : null,
                'position_seconds' => (float)$row['position_seconds'],
                'completed' => (int)$row['completed'],
                'updated_at' => $row['updated_at'],
                'started_at' => $row['started_at'],
                'last_played_at' => $row['last_played_at'],
                'saved_manually_at' => $row['saved_manually_at'],
                'save_source' => $row['save_source'],
            ];
        }
        json_response(['episodes' => $episodes]);
    }

    $idsParam = trim($_GET['episode_ids'] ?? '');
    if ($idsParam === '') {
        json_response(['progress' => []]);
    }
    $ids = array_values(array_filter(array_map('intval', explode(',', $idsParam))));
    if (!$ids) {
        json_response(['progress' => []]);
    }
    $placeholders = implode(',', array_fill(0, count($ids), '?'));
    $params = array_merge([$userId], $ids);
    $stmt = $pdo->prepare(
        "SELECT episode_id, position_seconds, completed, updated_at, started_at, last_played_at, saved_manually_at, save_source
         FROM user_episode_progress
         WHERE user_id = ? AND episode_id IN ({$placeholders})"
    );
    $stmt->execute($params);
    $rows = $stmt->fetchAll();
    $progress = [];
    foreach ($rows as $row) {
        $eid = (int)$row['episode_id'];
        $progress[$eid] = [
            'episode_id' => $eid,
            'position_seconds' => (float)($row['position_seconds'] ?? 0),
            'completed' => (int)($row['completed'] ?? 0),
            'updated_at' => $row['updated_at'],
            'started_at' => $row['started_at'],
            'last_played_at' => $row['last_played_at'],
            'saved_manually_at' => $row['saved_manually_at'],
            'save_source' => $row['save_source'],
        ];
    }
    json_response(['progress' => $progress]);
}

if ($method === 'POST') {
    $isBeacon = isset($_GET['beacon']);
    if (!$isBeacon) {
        verify_csrf();
    }
    $data = get_json_body();
    $action = trim((string)($data['action'] ?? ''));
    $episodeId = (int)($data['episode_id'] ?? 0);
    if (!$episodeId) {
        json_response(['error' => 'Missing episode_id'], 400);
    }

    if ($action === 'mark_played') {
        $durationStmt = $pdo->prepare('SELECT duration_seconds FROM episodes WHERE id = ?');
        $durationStmt->execute([$episodeId]);
        $duration = $durationStmt->fetchColumn();
        $durationVal = ($duration !== false && $duration !== null) ? (float)$duration : 0.0;

        $existingStmt = $pdo->prepare(
            'SELECT position_seconds, started_at, saved_manually_at
             FROM user_episode_progress
             WHERE user_id = ? AND episode_id = ?'
        );
        $existingStmt->execute([$userId, $episodeId]);
        $existing = $existingStmt->fetch();
        $position = $existing ? (float)$existing['position_seconds'] : 0.0;
        if ($durationVal > 0) {
            $position = max($position, $durationVal);
        }
        $startedAt = $existing['started_at'] ?? gmdate('Y-m-d H:i:s');
        $now = gmdate('Y-m-d H:i:s');
        $stmt = $pdo->prepare(
            'INSERT INTO user_episode_progress
                (user_id, episode_id, position_seconds, completed, started_at, last_played_at, saved_manually_at, save_source, updated_at)
             VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE
                position_seconds = VALUES(position_seconds),
                completed = 1,
                started_at = COALESCE(started_at, VALUES(started_at)),
                last_played_at = VALUES(last_played_at),
                save_source = VALUES(save_source),
                updated_at = VALUES(updated_at)'
        );
        $stmt->execute([
            $userId,
            $episodeId,
            $position,
            $startedAt,
            $now,
            $existing['saved_manually_at'] ?? null,
            'manual',
            $now,
        ]);

        // If this episode is the active player session, clear it.
        $sessionStmt = $pdo->prepare('SELECT episode_id FROM user_player_state WHERE user_id = ?');
        $sessionStmt->execute([$userId]);
        $activeEpisodeId = $sessionStmt->fetchColumn();
        if ((int)$activeEpisodeId === $episodeId) {
            $pdo->prepare(
                'UPDATE user_player_state
                 SET episode_id = NULL, position_seconds = 0, updated_at = ?
                 WHERE user_id = ?'
            )->execute([$now, $userId]);
        }

        $rowStmt = $pdo->prepare(
            'SELECT episode_id, position_seconds, completed, updated_at, started_at, last_played_at, saved_manually_at, save_source
             FROM user_episode_progress
             WHERE user_id = ? AND episode_id = ?'
        );
        $rowStmt->execute([$userId, $episodeId]);
        $row = $rowStmt->fetch();
        json_response([
            'ok' => true,
            'applied' => true,
            'reason' => 'ok',
            'completed' => 1,
            'progress' => $row ? [
                'episode_id' => (int)$row['episode_id'],
                'position_seconds' => (float)$row['position_seconds'],
                'completed' => (int)$row['completed'],
                'updated_at' => $row['updated_at'],
                'started_at' => $row['started_at'],
                'last_played_at' => $row['last_played_at'],
                'saved_manually_at' => $row['saved_manually_at'],
                'save_source' => $row['save_source'],
            ] : null,
        ]);
    }

    $positionSeconds = isset($data['position_seconds']) ? (float)$data['position_seconds'] : 0.0;
    $force = !empty($data['force']) || (($data['source'] ?? '') === 'manual');
    $source = (string)($data['source'] ?? ($isBeacon ? 'beacon' : ($force ? 'manual' : 'auto')));
    $clientUpdatedAt = isset($data['client_updated_at']) ? (string)$data['client_updated_at'] : null;
    $result = upsert_episode_progress(
        $pdo,
        $userId,
        $episodeId,
        $positionSeconds,
        $force,
        $source,
        $clientUpdatedAt
    );
    json_response([
        'ok' => true,
        'applied' => $result['applied'],
        'reason' => $result['reason'],
        'completed' => $result['completed'],
        'progress' => $result['progress'],
    ]);
}

json_response(['error' => 'Method not allowed'], 405);
