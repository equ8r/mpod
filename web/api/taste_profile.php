<?php
require_once __DIR__ . '/config.php';

function normalize_rating_score(float $rating): float {
    $score = ($rating - 2.5) / 2.5;
    if ($score > 1.0) return 1.0;
    if ($score < -1.0) return -1.0;
    return $score;
}

function compute_taste_profile(PDO $pdo, int $userId): array {
    $podcastAffinity = [];
    $topicPreferences = [];
    $guestPreferences = [];

    $stmt = $pdo->prepare(
        "SELECT e.podcast_id, AVG(er.rating) AS avg_rating, COUNT(*) AS rating_count
         FROM episode_ratings er
         JOIN episodes e ON e.id = er.episode_id
         WHERE er.user_id = ?
         GROUP BY e.podcast_id"
    );
    $stmt->execute([$userId]);
    foreach ($stmt->fetchAll() as $row) {
        $podcastAffinity[(string)$row['podcast_id']] = [
            'score' => normalize_rating_score((float)$row['avg_rating']),
            'count' => (int)$row['rating_count'],
        ];
    }

    $tagStmt = $pdo->prepare(
        "SELECT t.name, t.type, AVG(er.rating) AS avg_rating, COUNT(*) AS rating_count
         FROM episode_ratings er
         JOIN episode_tags et ON et.episode_id = er.episode_id
         JOIN tags t ON t.id = et.tag_id
         WHERE er.user_id = ? AND t.type IN ('topic', 'guest')
         GROUP BY t.id, t.name, t.type"
    );
    $tagStmt->execute([$userId]);
    foreach ($tagStmt->fetchAll() as $row) {
        $bucket = $row['type'] === 'guest' ? 'guest' : 'topic';
        $payload = [
            'score' => normalize_rating_score((float)$row['avg_rating']),
            'count' => (int)$row['rating_count'],
        ];
        if ($bucket === 'guest') {
            $guestPreferences[$row['name']] = $payload;
        } else {
            $topicPreferences[$row['name']] = $payload;
        }
    }

    $directStmt = $pdo->prepare(
        "SELECT t.name, t.type, tr.rating
         FROM tag_ratings tr
         JOIN tags t ON t.id = tr.tag_id
         WHERE tr.user_id = ?"
    );
    $directStmt->execute([$userId]);
    foreach ($directStmt->fetchAll() as $row) {
        $bucket = $row['type'] === 'guest' ? 'guest' : 'topic';
        $rating = (int)$row['rating'];
        if ($bucket === 'guest') {
            $guestPreferences[$row['name']] = [
                'score' => normalize_rating_score((float)$rating),
                'count' => $guestPreferences[$row['name']]['count'] ?? 1,
                'direct_rating' => $rating,
            ];
        } else {
            $topicPreferences[$row['name']] = [
                'score' => normalize_rating_score((float)$rating),
                'count' => $topicPreferences[$row['name']]['count'] ?? 1,
                'direct_rating' => $rating,
            ];
        }
    }

    $stats = [
        'total_ratings' => 0,
        'avg_rating' => 0.0,
        'rating_distribution' => ['1' => 0, '2' => 0, '3' => 0, '4' => 0, '5' => 0],
    ];
    $statsStmt = $pdo->prepare("SELECT rating, COUNT(*) AS count FROM episode_ratings WHERE user_id = ? GROUP BY rating");
    $statsStmt->execute([$userId]);
    $total = 0;
    $sum = 0;
    foreach ($statsStmt->fetchAll() as $row) {
        $rating = (string)$row['rating'];
        $count = (int)$row['count'];
        if (isset($stats['rating_distribution'][$rating])) {
            $stats['rating_distribution'][$rating] = $count;
        }
        $total += $count;
        $sum += ((int)$row['rating']) * $count;
    }
    $stats['total_ratings'] = $total;
    $stats['avg_rating'] = $total > 0 ? round($sum / $total, 2) : 0.0;

    $profile = [
        'podcast_affinity' => $podcastAffinity,
        'topic_preferences' => $topicPreferences,
        'guest_preferences' => $guestPreferences,
        'rating_stats' => $stats,
        'computed_at' => gmdate('c'),
    ];

    $existingStmt = $pdo->prepare("SELECT version FROM taste_profiles WHERE user_id = ?");
    $existingStmt->execute([$userId]);
    $existing = $existingStmt->fetch();
    if ($existing) {
        $version = (int)$existing['version'] + 1;
        $update = $pdo->prepare(
            "UPDATE taste_profiles
             SET profile_json = ?, last_computed = NOW(), version = ?
             WHERE user_id = ?"
        );
        $update->execute([json_encode($profile, JSON_UNESCAPED_SLASHES), $version, $userId]);
    } else {
        $version = 1;
        $insert = $pdo->prepare(
            "INSERT INTO taste_profiles (user_id, profile_json, last_computed, version)
             VALUES (?, ?, NOW(), ?)"
        );
        $insert->execute([$userId, json_encode($profile, JSON_UNESCAPED_SLASHES), $version]);
    }

    return ['ok' => true, 'user_id' => $userId, 'version' => $version, 'profile' => $profile];
}

if (PHP_SAPI !== 'cli' && realpath(__FILE__) === realpath($_SERVER['SCRIPT_FILENAME'] ?? '')) {
    $pdo = db();
    require_method('POST');
    $user = require_login();
    verify_csrf();
    $data = get_json_body();
    $targetUserId = isset($data['user_id']) ? (int)$data['user_id'] : (int)$user['id'];
    if ($targetUserId !== (int)$user['id'] && $user['role'] !== 'admin') {
        json_response(['error' => 'Forbidden'], 403);
    }
    $result = compute_taste_profile($pdo, $targetUserId);
    json_response($result);
}
