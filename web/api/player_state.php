<?php
require_once __DIR__ . '/config.php';

$pdo = db();
$user = require_login();
$method = $_SERVER['REQUEST_METHOD'];
$userId = (int)$user['id'];

function decode_queue_value($queue): array {
    if ($queue === null || $queue === '') {
        return [];
    }
    if (is_array($queue)) {
        return array_values($queue);
    }
    if (is_string($queue)) {
        $decoded = json_decode($queue, true);
        return is_array($decoded) ? array_values($decoded) : [];
    }
    return [];
}

function encode_queue_value($queue): string {
    return json_encode(decode_queue_value($queue), JSON_UNESCAPED_SLASHES);
}

function fetch_player_state_row(PDO $pdo, int $userId): ?array {
    $stmt = $pdo->prepare(
        "SELECT ups.user_id, ups.episode_id, ups.position_seconds, ups.queue, ups.queue_updated_at, ups.speed, ups.volume, ups.updated_at,
                e.title, e.audio_url, e.artwork_url, e.duration_seconds, e.podcast_id,
                p.title AS podcast_title, p.artwork_url AS podcast_artwork,
                uep.completed AS progress_completed, uep.position_seconds AS progress_position
         FROM user_player_state ups
         LEFT JOIN episodes e ON e.id = ups.episode_id
         LEFT JOIN podcasts p ON p.id = e.podcast_id
         LEFT JOIN user_episode_progress uep ON uep.user_id = ups.user_id AND uep.episode_id = ups.episode_id
         WHERE ups.user_id = ?"
    );
    $stmt->execute([$userId]);
    $row = $stmt->fetch();
    if (!$row) {
        return null;
    }
    $row['episode_id'] = $row['episode_id'] !== null ? (int)$row['episode_id'] : null;
    $row['position_seconds'] = $row['position_seconds'] !== null ? (float)$row['position_seconds'] : 0.0;
    $row['speed'] = $row['speed'] !== null ? (float)$row['speed'] : 1.0;
    $row['volume'] = $row['volume'] !== null ? (float)$row['volume'] : 1.0;
    $row['duration_seconds'] = $row['duration_seconds'] !== null ? (int)$row['duration_seconds'] : null;
    $row['queue'] = decode_queue_value($row['queue'] ?? null);
    $row['queue_updated_at'] = $row['queue_updated_at'] ?? null;
    $completed = (int)($row['progress_completed'] ?? 0);
    $duration = (float)($row['duration_seconds'] ?? 0);
    $sessionPosition = (float)$row['position_seconds'];
    $progressPosition = isset($row['progress_position']) && $row['progress_position'] !== null
        ? (float)$row['progress_position']
        : null;
    // Prefer the stronger per-episode bookmark when the session was wiped to ~0.
    if ($progressPosition !== null && $progressPosition > ($sessionPosition + 2.0)) {
        $row['position_seconds'] = $progressPosition;
    }
    $position = (float)$row['position_seconds'];
    if (!$completed && $duration > 0 && $position >= ($duration * 0.9)) {
        $completed = 1;
    }
    $row['completed'] = $completed;
    unset($row['progress_completed'], $row['progress_position']);
    return $row;
}

/**
 * Decide whether incoming queue should replace stored queue.
 * Empty queues never wipe a nonempty server queue unless clear_queue is true.
 */
function should_apply_queue(?array $existing, array $incomingQueue, bool $clearQueue, bool $forceQueue, ?string $incomingQueueUpdatedAt): bool {
    $existingQueue = decode_queue_value($existing['queue'] ?? null);
    $existingUpdated = isset($existing['queue_updated_at']) ? strtotime((string)$existing['queue_updated_at']) : false;
    $incomingUpdated = $incomingQueueUpdatedAt ? strtotime($incomingQueueUpdatedAt) : false;

    if ($forceQueue || $clearQueue) {
        return true;
    }
    if (!$incomingQueue && $existingQueue) {
        // Allow intentional empties only when the client queue timestamp is newer.
        if ($incomingUpdated !== false && $existingUpdated !== false && $incomingUpdated > $existingUpdated) {
            return true;
        }
        // Protect against accidental empties from other devices / wiped localStorage.
        return false;
    }
    if (!$existing) {
        return true;
    }
    if ($incomingUpdated === false) {
        // No queue timestamp: only apply if server queue is empty.
        return !$existingQueue;
    }
    if ($existingUpdated === false) {
        return true;
    }
    return $incomingUpdated >= $existingUpdated;
}

if ($method === 'GET') {
    $row = fetch_player_state_row($pdo, $userId);
    json_response(['state' => $row]);
}

if ($method === 'POST') {
    // sendBeacon calls can't include CSRF headers, so skip CSRF for beacon saves
    // (session cookie still authenticates the user)
    $isBeacon = isset($_GET['beacon']);
    if (!$isBeacon) {
        verify_csrf();
    }
    $data = get_json_body();
    $episodeId = array_key_exists('episode_id', $data) ? (int)$data['episode_id'] : null;
    if ($episodeId === 0) {
        $episodeId = null;
    }
    $hasPosition = array_key_exists('position_seconds', $data);
    $positionSeconds = $hasPosition ? (float)$data['position_seconds'] : null;
    $hasSpeed = array_key_exists('speed', $data);
    $speed = $hasSpeed ? (float)$data['speed'] : null;
    $hasVolume = array_key_exists('volume', $data);
    $volume = $hasVolume ? (float)$data['volume'] : null;
    $hasQueue = array_key_exists('queue', $data);
    $incomingQueue = $hasQueue ? decode_queue_value($data['queue']) : null;
    $clearQueue = !empty($data['clear_queue']);
    $forceQueue = !empty($data['force_queue']) || $clearQueue;
    $force = !empty($data['force']) || (($data['source'] ?? '') === 'manual');
    $source = (string)($data['source'] ?? ($isBeacon ? 'beacon' : ($force ? 'manual' : 'auto')));
    $clientUpdatedAt = isset($data['client_updated_at']) ? (string)$data['client_updated_at'] : null;
    $incomingAt = parse_client_updated_at($clientUpdatedAt) ?? gmdate('Y-m-d H:i:s');
    $queueUpdatedAtRaw = isset($data['queue_updated_at']) ? (string)$data['queue_updated_at'] : null;
    $queueUpdatedAt = parse_client_updated_at($queueUpdatedAtRaw);

    $existing = fetch_player_state_row($pdo, $userId);
    $applied = true;
    $reason = 'ok';
    $queueApplied = false;
    $replaceSession = $force || $source === 'manual' || !empty($data['replace_session']);
    $clearSession = !empty($data['clear_session']);
    $skipProgress = !empty($data['skip_progress']) || !empty($data['queue_only']);

    $episodeChanging = false;
    if ($existing && array_key_exists('episode_id', $data)) {
        $episodeChanging = ((int)($existing['episode_id'] ?? 0) !== (int)($episodeId ?? 0));
    }

    // Auto/beacon saves must not steal the active session from another device.
    if ($existing && !$replaceSession && !$clearSession) {
        if ($episodeChanging) {
            $applied = false;
            $reason = 'session_locked';
        } elseif ($hasPosition) {
            $existingTs = strtotime((string)$existing['updated_at']);
            $incomingTs = strtotime($incomingAt);
            if ($existingTs !== false && $incomingTs !== false && $incomingTs < $existingTs) {
                $applied = false;
                $reason = 'stale';
            }
        }
    }

    $nextEpisodeId = $existing['episode_id'] ?? null;
    $nextPosition = $existing['position_seconds'] ?? 0.0;
    $nextSpeed = $existing['speed'] ?? 1.0;
    $nextVolume = $existing['volume'] ?? 1.0;
    $nextUpdatedAt = $existing['updated_at'] ?? gmdate('Y-m-d H:i:s');
    $nextQueue = encode_queue_value($existing['queue'] ?? []);
    $nextQueueUpdatedAt = $existing['queue_updated_at'] ?? null;

    if ($applied) {
        $sessionTouched = $clearSession
            || array_key_exists('episode_id', $data)
            || $hasPosition
            || $hasSpeed
            || $hasVolume;
        if ($clearSession) {
            $nextEpisodeId = null;
            $nextPosition = 0.0;
        } elseif (array_key_exists('episode_id', $data)) {
            $nextEpisodeId = $episodeId;
        }
        if ($hasPosition && !$clearSession) {
            $incomingPos = (float)$positionSeconds;
            $existingPos = (float)($existing['position_seconds'] ?? 0);
            // Same zero-regression guard for session state (auto/beacon only).
            if (
                !$force
                && $source !== 'manual'
                && $existingPos >= 30.0
                && $incomingPos < 15.0
                && ($existingPos - $incomingPos) >= 20.0
            ) {
                $nextPosition = $existingPos;
            } else {
                $nextPosition = $incomingPos;
            }
        }
        if ($hasSpeed) {
            $nextSpeed = (float)$speed;
        }
        if ($hasVolume) {
            $nextVolume = (float)$volume;
        }
        if ($sessionTouched) {
            $nextUpdatedAt = $force || $source === 'manual' || $replaceSession || $clearSession
                ? gmdate('Y-m-d H:i:s')
                : $incomingAt;
        }
    } else {
        $sessionTouched = false;
    }

    if ($hasQueue || $clearQueue) {
        $candidateQueue = $clearQueue ? [] : ($incomingQueue ?? []);
        if (should_apply_queue($existing, $candidateQueue, $clearQueue, $forceQueue, $queueUpdatedAt)) {
            $nextQueue = encode_queue_value($candidateQueue);
            $nextQueueUpdatedAt = $queueUpdatedAt ?? gmdate('Y-m-d H:i:s');
            $queueApplied = true;
        }
    }

    // Persist when playback fields applied and/or queue applied, or first insert.
    if ($applied || $queueApplied || !$existing) {
        if (!$existing && !$applied) {
            // First row with queue-only write.
            $nextEpisodeId = $episodeId;
            $nextPosition = $hasPosition ? (float)$positionSeconds : 0.0;
            $nextSpeed = $hasSpeed ? (float)$speed : 1.0;
            $nextVolume = $hasVolume ? (float)$volume : 1.0;
            $nextUpdatedAt = $incomingAt;
            $sessionTouched = true;
        }
        $stmt = $pdo->prepare(
            "INSERT INTO user_player_state (user_id, episode_id, position_seconds, queue, queue_updated_at, speed, volume, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             ON DUPLICATE KEY UPDATE
                episode_id = VALUES(episode_id),
                position_seconds = VALUES(position_seconds),
                queue = VALUES(queue),
                queue_updated_at = VALUES(queue_updated_at),
                speed = VALUES(speed),
                volume = VALUES(volume),
                updated_at = VALUES(updated_at)"
        );
        $writeUpdatedAt = !empty($sessionTouched)
            ? $nextUpdatedAt
            : ($existing['updated_at'] ?? $nextUpdatedAt);
        $stmt->execute([
            $userId,
            $nextEpisodeId,
            $nextPosition,
            $nextQueue,
            $nextQueueUpdatedAt,
            $nextSpeed,
            $nextVolume,
            $writeUpdatedAt,
        ]);
    }

    $progressResult = null;
    if (!$skipProgress && $applied && $nextEpisodeId && $hasPosition) {
        $progressResult = upsert_episode_progress(
            $pdo,
            $userId,
            (int)$nextEpisodeId,
            (float)$nextPosition,
            $force,
            $source,
            $clientUpdatedAt
        );
    } elseif (!$skipProgress && !$applied && $episodeId && $hasPosition) {
        $progressResult = upsert_episode_progress(
            $pdo,
            $userId,
            (int)$episodeId,
            (float)$positionSeconds,
            $force,
            $source,
            $clientUpdatedAt
        );
    }

    $row = fetch_player_state_row($pdo, $userId);
    json_response([
        'ok' => true,
        'applied' => $applied,
        'reason' => $reason,
        'queue_applied' => $queueApplied,
        'state' => $row,
        'progress' => $progressResult['progress'] ?? null,
        'progress_applied' => $progressResult['applied'] ?? null,
    ]);
}

json_response(['error' => 'Method not allowed'], 405);
